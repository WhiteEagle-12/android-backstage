#!/usr/bin/env node
/**
 * Android Backstage relay — blind envelope forwarder.
 *
 * The relay never sees a tool name, argument, result, or file plaintext.
 * Pairing sessions exchange opaque SPAKE2 bytes; /v1/call stores envelopes.
 */
import http from "node:http";
import fs from "node:fs";
import { createDashboard } from "./dashboard.mjs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createStore, randomToken, OPAQUE_MAX, hashToken, hashesEqual } from "./store.mjs";
import { createQueue } from "./queue.mjs";
import { createUploads } from "./uploads.mjs";
import { createFiles } from "./files.mjs";
import { bearerToken, HttpError, requireAccount, requireAdmin, requireDeviceAuth } from "./auth.mjs";
import { createLearnResults, LEARN_RESULT_MAX_BODY_BYTES } from "./learn-results.mjs";
import { resolvePublicWebhook, deliverWebhook } from "./webhook.mjs";
import { createR2FromEnv } from "./r2.mjs";

export const VERSION = "2.0.0";
const MAX_ENVELOPE = 4 * 1024 * 1024;
const CALL_TIMEOUT_DEFAULT = 30_000;
const CALL_TIMEOUT_MIN = 1_000;
const CALL_TIMEOUT_MAX = 620_000;
const PAIR_POLL_MAX = 25_000;
const LEARN_ASSETS = new Map([
  ["/learn/", ["index.html", "text/html; charset=utf-8"]],
  ["/learn/app.js", ["app.js", "text/javascript; charset=utf-8"]],
  ["/learn/runner.js", ["runner.js", "text/javascript; charset=utf-8"]],
  ["/learn/style.css", ["style.css", "text/css; charset=utf-8"]],
]);
const LEARN_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), "learn");

function json(res, code, body, extraHeaders = {}) {
  const data = JSON.stringify(body);
  const headers = {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(data),
  };
  for (const [key, value] of Object.entries(extraHeaders)) if (value !== undefined) headers[key] = value;
  if (!Object.hasOwn(extraHeaders, "access-control-allow-origin")) headers["access-control-allow-origin"] = "*";
  res.writeHead(code, headers);
  res.end(data);
}

function sendError(res, status, message, extraHeaders = {}) {
  json(res, status, { error: String(message) }, extraHeaders);
}

function readBody(req, limit = 1_000_000) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (c) => {
      size += c.length;
      if (size > limit) {
        req.destroy();
        const err = new Error("payload too large");
        err.status = 413;
        reject(err);
        return;
      }
      chunks.push(c);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function parseJsonBody(raw) {
  if (!raw || !String(raw).trim()) return {};
  try {
    return JSON.parse(raw);
  } catch {
    const err = new Error("invalid JSON");
    err.status = 400;
    throw err;
  }
}

function corsPreflight(res) {
  res.writeHead(204, {
    "access-control-allow-origin": "*",
    "access-control-allow-headers":
      "authorization,content-type,accept,x-file-name,x-file-ttl-ms,x-file-single-use",
    "access-control-allow-methods": "GET,POST,PUT,PATCH,DELETE,OPTIONS",
  });
  res.end();
}

function learnOriginHeaders(req, fallback, publicUrl, requireOrigin = false) {
  const origin = String(req.headers.origin || "");
  const expected = publicBaseFromReq(req, fallback, publicUrl);
  if (origin && origin !== expected) throw new HttpError(403, "origin is not this relay");
  if (requireOrigin && !origin) throw new HttpError(403, "origin is required");
  if (!origin) return {};
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-headers": "authorization,content-type",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "vary": "Origin",
  };
}

function publicBaseFromReq(req, fallback, publicUrl) {
  if (publicUrl) return String(publicUrl).replace(/\/$/, "");
  const proto = String(req.headers["x-forwarded-proto"] || "http").split(",")[0].trim();
  const host = req.headers.host;
  if (host) return `${proto}://${host}`;
  return fallback;
}

function headerFlag(value, defaultTrue) {
  if (value == null || value === "") return defaultTrue;
  const v = String(value).trim().toLowerCase();
  if (["0", "false", "no"].includes(v)) return false;
  if (["1", "true", "yes"].includes(v)) return true;
  return defaultTrue;
}

function deviceNamesList(devices) {
  if (!devices.length) return "(none)";
  return devices.map((d) => d.name).join(", ");
}

function resolveNamedDevice(store, account, nameOrAlias, expectedDeviceId) {
  const wanted = String(nameOrAlias ?? "").trim();
  if (account.connection) {
    const device = store.deviceById(account.deviceId);
    if (!device || (wanted !== device.id && wanted !== device.name) || (expectedDeviceId && expectedDeviceId !== device.id))
      throw new HttpError(403, "This connection cannot access that phone.");
    return device;
  }
  const all = store.devicesForAccount(account.id);
  const device = store.findDevice(account.id, wanted);
  if (!device) {
    throw new HttpError(
      400,
      `unknown device "${wanted || "(empty)"}". Devices on this account: ${deviceNamesList(all)}. Not using another device.`,
    );
  }
  if (expectedDeviceId != null && expectedDeviceId !== device.id) {
    throw new HttpError(409, `device "${wanted}" no longer matches the paired device ID. Review its identity before re-linking. No command was queued.`);
  }
  return device;
}

export function formatAgo(iso) {
  if (!iso) return "never";
  const ms = Date.now() - Date.parse(iso);
  if (!Number.isFinite(ms)) return "never";
  if (ms < 0 || ms < 1000) return "just now";
  if (ms < 60_000) return `${Math.round(ms / 1000)}s ago`;
  if (ms < 3_600_000) return `${Math.round(ms / 60_000)}m ago`;
  if (ms < 86_400_000) return `${Math.round(ms / 3_600_000)}h ago`;
  return `${Math.round(ms / 86_400_000)}d ago`;
}

export function offlineMessage(name, lastSeen) {
  return `device "${name}" is offline (last seen ${formatAgo(lastSeen)}). Open Android Backstage on that phone and tap Start. Not using another device.`;
}

export function isRecentlySeen(device, offlineMs, queue) {
  if (queue?.isPolling?.(device.id)) return true;
  if (!device.lastSeen) return false;
  const ago = Date.now() - Date.parse(device.lastSeen);
  return Number.isFinite(ago) && ago <= offlineMs;
}

export function deviceTransport(device, offlineMs, queue) {
  if (isRecentlySeen(device, offlineMs, queue)) {
    return { online: true, transport: "poll" };
  }
  return { online: false, transport: "offline" };
}

export function publicDeviceRecord(device, { online, transport } = {}) {
  return {
    id: device.id,
    name: device.name,
    aliases: device.aliases || [],
    model: device.model,
    sdk: device.sdk,
    agentVersion: device.agentVersion,
    bootId: device.bootId || null,
    online: Boolean(online),
    lastSeen: device.lastSeen,
    inboundUrl: device.inboundUrl || null,
    // Addresses the phone reported at its last hello; the PC uses them for a direct route.
    lanAddresses: Array.isArray(device.lanAddresses) ? device.lanAddresses.filter((a) => typeof a === "string").slice(0, 8) : [],
    transport: transport || "offline",
    agents: Array.isArray(device.agents) ? device.agents : [],
  };
}

export function validateEnvelope(envelope, rawSize) {
  if (rawSize != null && rawSize > MAX_ENVELOPE) {
    throw new HttpError(400, "envelope too large");
  }
  if (!envelope || typeof envelope !== "object" || Array.isArray(envelope)) {
    throw new HttpError(400, "envelope must be a JSON object");
  }
  const { v, from, n, ct } = envelope;
  if (typeof from !== "string" || typeof n !== "string" || typeof ct !== "string") {
    throw new HttpError(400, "envelope requires string fields from, n, ct");
  }
  // The relay forwards opaque bytes; version may arrive as JSON number 1 or string "1".
  if (![1,2].includes(Number(v))) throw new HttpError(400, "envelope v must be 1 or 2");
  if (Number(v) === 2 && !/^[A-Za-z0-9_-]{16}$/.test(envelope.r || "")) throw new HttpError(400, "invalid random nonce");
  const serialized = Buffer.byteLength(JSON.stringify(envelope));
  if (serialized > MAX_ENVELOPE) throw new HttpError(400, "envelope too large");
  return envelope;
}

function opaqueString(value, field) {
  if (typeof value !== "string" || value.length === 0) {
    throw new HttpError(400, `${field} must be a non-empty string`);
  }
  if (Buffer.byteLength(value, "utf8") > OPAQUE_MAX) {
    throw new HttpError(400, `${field} too large`);
  }
  return value;
}

function clampTimeout(raw) {
  const n = raw == null || raw === "" ? CALL_TIMEOUT_DEFAULT : Number(raw);
  if (!Number.isFinite(n)) throw new HttpError(400, "timeoutMs must be a number");
  return Math.min(CALL_TIMEOUT_MAX, Math.max(CALL_TIMEOUT_MIN, Math.round(n)));
}

function sessionPath(url) {
  return /^\/v1\/pair\/session\/([^/]+)(?:\/([^/]+))?$/.exec(url.pathname);
}

/**
 * @param {{
 *   port?: number,
 *   host?: string,
 *   dataDir?: string,
 *   publicUrl?: string,
 *   adminToken?: string,
 *   pollMs?: number,
 *   offlineMs?: number,
 *   maxFileMb?: number,
 *   maxFileBytes?: number,
 *   pairPollMs?: number,
 *   log?: Console,
 * }} opts
 */
export async function createRelay(opts = {}) {
  const log = opts.log ?? console;
  const port = opts.port ?? Number(process.env.PORT || 18421);
  const host = opts.host ?? process.env.HOST ?? "0.0.0.0";
  const dataDir = path.resolve(opts.dataDir ?? process.env.RELAY_DATA_DIR ?? "./data");
  const publicUrl = (opts.publicUrl ?? process.env.RELAY_PUBLIC_URL ?? "").replace(/\/$/, "");
  const adminToken = opts.adminToken ?? process.env.RELAY_ADMIN_TOKEN ?? "";
  const pollMs = opts.pollMs ?? Number(process.env.RELAY_POLL_MS || 25_000);
  const offlineMs = opts.offlineMs ?? Number(process.env.RELAY_OFFLINE_MS || 45_000);
  // A phone that polled recently is usually mid-reconnect (Wi-Fi roam, daemon relaunch).
  // Queue its jobs instead of refusing them; they still expire at their own deadline.
  const reconnectGraceMs = Math.max(offlineMs, opts.reconnectGraceMs ?? Number(process.env.RELAY_RECONNECT_GRACE_MS || 180_000));
  const zeroRetention = opts.zeroRetention ?? (process.env.RELAY_ZERO_RETENTION === "1");
  const maxFileMb = opts.maxFileMb ?? Number(process.env.RELAY_MAX_FILE_MB || (zeroRetention ? 128 : 512));
  const maxFileBytes = opts.maxFileBytes ?? maxFileMb * 1024 * 1024;
  const pairPollMs = Math.min(PAIR_POLL_MAX, opts.pairPollMs ?? PAIR_POLL_MAX);
  const enableLearn = opts.enableLearn ?? (process.env.RELAY_ENABLE_LEARN === "1");
  const allowPublicPair = opts.allowPublicPair ?? (process.env.RELAY_ALLOW_PUBLIC_PAIR !== "0");
  const filesDir = opts.filesDir ?? (process.env.RELAY_FILES_DIR ? path.resolve(process.env.RELAY_FILES_DIR) : null);
  const r2 = opts.r2 !== undefined ? opts.r2 : createR2FromEnv();
  const webhookLookup = opts.webhookLookup;

  const store = createStore(dataDir, { log });
  if (zeroRetention) {
    fs.rmSync(path.join(dataDir, "jobs"), { recursive: true, force: true });
    fs.rmSync(path.join(dataDir, "dashboard-activity.json"), { force: true });
  }
  const queue = createQueue({
    dataDir: zeroRetention ? null : dataDir,
    retentionMs: zeroRetention ? 5 * 60_000 : 86_400_000,
    quotaBytes: zeroRetention ? 96 * 1024 * 1024 : 512 * 1024 * 1024,
  });
  const files = createFiles({
    dataDir,
    filesDir,
    maxBytes: maxFileBytes,
    quotaBytes: zeroRetention ? 160 * 1024 * 1024 : 2 * 1024 * 1024 * 1024,
    maxAccountBytes: zeroRetention ? 128 * 1024 * 1024 : 512 * 1024 * 1024,
    ttlMs: zeroRetention ? 5 * 60_000 : 15 * 60_000,
    shred: zeroRetention,
  });
  files.startSweeper();
  const uploads = createUploads(dataDir, {
    uploadsDir: filesDir ? path.join(filesDir, "uploads") : null,
    maxBytes: maxFileBytes,
    quotaBytes: zeroRetention ? 160 * 1024 * 1024 : 2 * 1024 * 1024 * 1024,
    maxAccountBytes: zeroRetention ? 128 * 1024 * 1024 : 512 * 1024 * 1024,
    ttlMs: zeroRetention ? 5 * 60_000 : 15 * 60_000,
    shred: zeroRetention,
  });
  const learnResults = enableLearn ? createLearnResults(dataDir, { log }) : null;
  const rates = new Map();
  const rateTimer = setInterval(() => rates.clear(), 60000); rateTimer.unref();
  function checkRate(key, limit) {
    const count = (rates.get(key) || 0) + 1;
    if (count > limit) throw new HttpError(429, "request rate limit exceeded");
    if (rates.size >= 25000 && !rates.has(key)) {
      const oldest = rates.keys().next().value;
      if (oldest !== undefined) rates.delete(oldest);
    }
    rates.set(key, count);
  }

  // Per-account pushed device event ring buffer, SSE streams, and optional webhooks.
  const eventsByAccount = new Map();
  const eventStreams = new Map();
  const webhooksFile = path.join(dataDir, "webhooks.json");
  let webhooks = {};
  if (!zeroRetention) {
    try { if (fs.existsSync(webhooksFile)) webhooks = JSON.parse(fs.readFileSync(webhooksFile, "utf8")); } catch {}
  } else {
    try { fs.rmSync(webhooksFile, { force: true }); } catch {}
  }
  let eventSeq = 0;
  const eventRetentionMs = zeroRetention ? 5 * 60_000 : 10 * 60_000;
  const eventTimer = setInterval(() => {
    const cutoff = Date.now() - eventRetentionMs;
    for (const [acctId, list] of eventsByAccount) {
      while (list.length && list[0].time < cutoff) list.shift();
      if (!list.length) eventsByAccount.delete(acctId);
    }
  }, 60_000);
  eventTimer.unref?.();

  function eventAccountId(account) {
    if (!account.connection) return account.id;
    return store.deviceById(account.deviceId)?.accountId || account.id;
  }
  function webhookKey(account) {
    return account.connection ? `dev:${account.deviceId}` : account.id;
  }
  function saveWebhooks() {
    if (zeroRetention) return;
    try {
      fs.mkdirSync(dataDir, { recursive: true, mode: 0o700 });
      fs.writeFileSync(webhooksFile, JSON.stringify(webhooks, null, 2) + "\n", { mode: 0o600 });
    } catch {}
  }

  function recordDeviceEvent(device, body) {
    const accountId = device.accountId;
    const entry = {
      id: `evt_${crypto.randomBytes(8).toString("hex")}`,
      seq: ++eventSeq,
      time: typeof body.time === "number" ? body.time : Date.now(),
      deviceId: device.id,
      device: device.name,
      type: String(body.type || "event").slice(0, 64),
      payload: body.payload && typeof body.payload === "object" ? body.payload : {},
    };
    let list = eventsByAccount.get(accountId);
    if (!list) { list = []; eventsByAccount.set(accountId, list); }
    const cutoff = Date.now() - eventRetentionMs;
    while (list.length && list[0].time < cutoff) list.shift();
    list.push(entry);
    if (list.length > 200) list.splice(0, list.length - 200);
    const streams = eventStreams.get(accountId);
    if (streams) {
      const frame = `id: ${entry.seq}\nevent: ${entry.type}\ndata: ${JSON.stringify(entry)}\n\n`;
      for (const client of [...streams]) {
        if (client.deviceId && client.deviceId !== device.id) continue;
        try { client.res.write(frame); } catch { streams.delete(client); }
      }
    }
    const targets = [webhooks[accountId], webhooks[`dev:${device.id}`]].filter((h) => h?.url);
    for (const hook of targets) {
      const raw = JSON.stringify(entry);
      const headers = { "content-type": "application/json" };
      if (hook.secret) {
        headers["x-backstage-signature"] = "sha256=" + crypto.createHmac("sha256", hook.secret).update(raw).digest("hex");
      }
      deliverWebhook(hook.url, { headers, body: raw, timeoutMs: 5000, lookup: webhookLookup }).catch(() => {});
    }
    return entry;
  }

  /** @type {Map<string, { resolve: Function, timer: NodeJS.Timeout }[]>} */
  const pairWaiters = new Map();

  function notifyPair(key, value) {
    const list = pairWaiters.get(key) || [];
    pairWaiters.delete(key);
    for (const waiter of list) {
      clearTimeout(waiter.timer);
      waiter.resolve(value);
    }
  }

  function waitPair(key, timeoutMs) {
    return new Promise((resolve) => {
      const list = pairWaiters.get(key) || [];
      const entry = { resolve: null, timer: null };
      entry.timer = setTimeout(() => {
        const idx = list.indexOf(entry);
        if (idx >= 0) list.splice(idx, 1);
        resolve(null);
      }, timeoutMs);
      entry.resolve = (value) => {
        clearTimeout(entry.timer);
        resolve(value);
      };
      list.push(entry);
      pairWaiters.set(key, list);
    });
  }

  function listedDevice(device) {
    const live = deviceTransport(device, offlineMs, queue);
    return publicDeviceRecord(device, live);
  }

  function requireSession(id) {
    const session = store.sessionById(id);
    if (!session) throw new HttpError(404, "unknown or expired pairing session");
    return session;
  }

  function requirePairAgent(req, session) {
    if (session.connectionId) {
      if (!hashesEqual(session.connectionHash, hashToken(bearerToken(req)))) throw new HttpError(401, "unauthorized");
    } else {
      const account = requireAccount(req, store);
      if (session.accountId !== account.id) throw new HttpError(404, "unknown or expired pairing session");
    }
  }

  const dashboard = createDashboard({dataDir,store,queue,listedDevice,version:VERSION,memoryOnly:zeroRetention,log});
  const server = http.createServer(async (req, res) => {
    try {
      const url = new URL(req.url, "http://127.0.0.1");
      // Bound unauthenticated pairing/JSON abuse without trusting forwarded IP headers.
      // Behind the loopback front, the client address arrives in X-Forwarded-For (set by the front from Cloudflare, never from the client).
      const forwarded = String(req.headers["x-forwarded-for"] || "").split(",")[0].trim();
      const loopback = req.socket.remoteAddress === "127.0.0.1" || req.socket.remoteAddress === "::1" || req.socket.remoteAddress === "::ffff:127.0.0.1";
      const ip = (loopback && forwarded) ? forwarded : (req.socket.remoteAddress || "unknown");
      const token=bearerToken(req);
      const principal=store.accountByKey(token)||store.deviceByToken(token)||store.connectionByKey(token);
      dashboard.observe(req,res,principal,url.pathname);
      const internalKey = String(req.headers["x-backstage-internal-key"] || "");
      const internalOwner = String(req.headers["x-backstage-owner"] || "").slice(0, 80);
      const internalAuthed = Boolean(adminToken && internalKey && hashesEqual(hashToken(internalKey), hashToken(adminToken)));
      const pairSubject = internalAuthed && internalOwner ? `owner:${internalOwner}` : ip;
      const rateKey = `${principal ? principal.id : pairSubject}:${url.pathname.startsWith('/v1/pair/') ? 'pair' : 'api'}`;
      const limit = url.pathname.startsWith('/v1/pair/') ? 120 : 3000;
      checkRate(rateKey, limit);
      if (url.pathname === '/v1/pair/connect' && req.method === 'POST') checkRate(`${pairSubject}:pair-connect`, 20);
      if (req.method === "OPTIONS") {
        if (enableLearn && url.pathname.startsWith("/v1/learn/")) {
          const headers = learnOriginHeaders(req, listenUrl, publicUrl, true);
          res.writeHead(204, headers).end();
          return;
        }
        corsPreflight(res);
        return;
      }

      if (dashboard.handle(req,res,url)) return;

      if (enableLearn) {
        if (url.pathname === "/v1/learn/activities" && req.method === "POST") {
          requireAdmin(req, adminToken);
          const body = parseJsonBody(await readBody(req, 16 * 1024));
          const created = learnResults.create({ accountId: "admin", ttlMs: body.ttlMs, maxAttempts: body.maxAttempts });
          json(res, 201, created, { "cache-control": "no-store" });
          return;
        }
        const learnResultPath = /^\/v1\/learn\/activities\/([^/]+)\/results$/.exec(url.pathname);
        if (learnResultPath && (req.method === "POST" || req.method === "GET")) {
          const activityId = decodeURIComponent(learnResultPath[1]);
          const headers = learnOriginHeaders(req, listenUrl, publicUrl, req.method === "POST");
          if (req.method === "POST") {
            const body = parseJsonBody(await readBody(req, LEARN_RESULT_MAX_BODY_BYTES));
            json(res, 201, learnResults.write(req, activityId, body), { ...headers, "access-control-allow-origin": headers["access-control-allow-origin"], "cache-control": "no-store" });
          } else {
            const after = url.searchParams.has("after") ? Number(url.searchParams.get("after")) : 0;
            json(res, 200, learnResults.read(req, activityId, after), { ...headers, "access-control-allow-origin": headers["access-control-allow-origin"], "cache-control": "no-store" });
          }
          return;
        }

        const learnAsset = LEARN_ASSETS.get(url.pathname);
        if (learnAsset) {
          if (req.method !== "GET") {
            res.writeHead(405, { allow: "GET", "cache-control": "no-store" }).end();
            return;
          }
          const [fileName, contentType] = learnAsset;
          const data = fs.readFileSync(path.join(LEARN_DIR, fileName));
          res.writeHead(200, {
            "content-type": contentType,
            "content-length": data.length,
            "cache-control": "public, max-age=300",
            "x-content-type-options": "nosniff",
            "referrer-policy": "no-referrer",
            "content-security-policy": "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
          });
          res.end(data);
          return;
        }
      }

      if (url.pathname === "/health" && req.method === "GET") {
        const devices = Object.values(store.snapshot().devices);
        const devicesOnline = devices.filter((d) => {
          if (queue.isPolling(d.id)) return true;
          if (!d.lastSeen) return false;
          const ago = Date.now() - Date.parse(d.lastSeen);
          return Number.isFinite(ago) && ago <= offlineMs;
        }).length;
        json(res, 200, {
          ok: true,
          service: "backstage-relay",
          version: VERSION,
          devicesOnline,
        });
        return;
      }

      const fileMatch = /^\/v1\/files\/([a-f0-9]{64})$/i.exec(url.pathname);
      if (fileMatch && (req.method === "GET" || req.method === "HEAD")) {
        const ok = files.serveDownload(req, res, fileMatch[1].toLowerCase());
        if (ok) return;
        sendError(res, 404, "no such file");
        return;
      }

      if (url.pathname === "/v1/uploads" && req.method === "POST") {
        const account=requireAccount(req,store), body=parseJsonBody(await readBody(req));
        json(res,201,uploads.begin(account.id,body.name,body.size));return;
      }
      const uploadPath=/^\/v1\/uploads\/([a-f0-9]{32})(?:\/(complete))?$/.exec(url.pathname);
      if(uploadPath){
        const account=requireAccount(req,store), id=uploadPath[1];
        if(req.method==='GET'){json(res,200,uploads.get(id,account.id));return;}
        if(req.method==='PUT'&&!uploadPath[2]){json(res,200,await uploads.append(id,account.id,Number(req.headers['x-upload-offset']),req));return;}
        if(req.method==='POST'&&uploadPath[2]){const file=await uploads.complete(id,account.id,files,{r2});
          json(res,200,{...file,url:file.url||files.fileUrl(publicBaseFromReq(req,listenUrl,publicUrl),file.id)});return;}
      }
      if (url.pathname === "/v1/files" && req.method === "POST") {
        const account = requireAccount(req, store);
        const ttlHeader = req.headers["x-file-ttl-ms"];
        const ttl = ttlHeader != null && String(ttlHeader).trim() !== "" ? Number(ttlHeader) : undefined;
        if (ttl != null && (!Number.isFinite(ttl) || ttl <= 0 || ttl > 86400000)) {
          sendError(res, 400, "invalid x-file-ttl-ms");
          return;
        }
        const singleUse = headerFlag(req.headers["x-file-single-use"], true);
        const entry = await files.saveUpload(req, {
          fileName: req.headers["x-file-name"],
          owner: account.id,
          ttlMs: ttl,
          singleUse,
        });
        if (r2) {
          const defaultTtlMs = zeroRetention ? 300_000 : 900_000;
          const effectiveTtlMs = zeroRetention ? Math.min(ttl ?? defaultTtlMs, 300_000) : (ttl ?? defaultTtlMs);
          try {
            const uploaded = await r2.uploadFile(entry.path, { ttlSec: Math.round(effectiveTtlMs / 1000) });
            json(res, 200, {
              id: uploaded.id,
              url: uploaded.url,
              expiresAt: new Date(uploaded.expiresAt).toISOString(),
            });
          } finally {
            files.removeEntry(entry.id);
          }
          return;
        }
        const base = publicBaseFromReq(req, listenUrl, publicUrl);
        json(res, 200, {
          id: entry.id,
          url: files.fileUrl(base, entry.id),
          expiresAt: new Date(entry.expiresAt).toISOString(),
        });
        return;
      }

      if (url.pathname === "/v1/device/hello" && req.method === "POST") {
        const device = requireDeviceAuth(req, store);
        const body = parseJsonBody(await readBody(req));
        const patch = {
          lastSeen: new Date().toISOString(),
        };
        if (body.bootId && device.bootId && device.bootId !== body.bootId) queue.recoverDevice(device.id);
        if (body.bootId) patch.bootId = String(body.bootId).slice(0,100);
        if (body.model != null) patch.model = String(body.model).slice(0, 128);
        if (body.sdk != null && Number.isInteger(Number(body.sdk))) patch.sdk = Number(body.sdk);
        if (body.agentVersion != null) patch.agentVersion = String(body.agentVersion).slice(0, 64);
        if (body.capabilities != null) {
          const capJson = JSON.stringify(body.capabilities);
          if (capJson && capJson.length <= 4096) patch.capabilities = body.capabilities;
        }
        if (Array.isArray(body.lanAddresses)) {
          patch.lanAddresses = body.lanAddresses.filter((a) => typeof a === "string" && a.length > 0 && a.length <= 64).slice(0, 8);
        }
        // Phones that run several jobs at once say how many; older phones run one.
        patch.parallelJobs = queue.setLanes(device.id, body.parallelJobs);

        const updated = store.updateDevice(device.id, patch);
        json(res, 200, { ok: true, name: updated.name, pollMs, parallelJobs: patch.parallelJobs });
        return;
      }

      if (url.pathname === "/v1/device/next" && req.method === "POST") {
        const device = requireDeviceAuth(req, store);
        const pollBody = parseJsonBody(await readBody(req) || "{}");
        // A poll left open by a daemon that has since restarted must not take a job:
        // that process is exiting and the job would be lost.
        if (pollBody.bootId && device.bootId && String(pollBody.bootId) !== device.bootId) {
          json(res, 200, { idle: true, stale: true });
          return;
        }
        // After a relay restart, restore the lane count the phone last reported.
        if (device.parallelJobs && queue.lanes(device.id) !== device.parallelJobs) queue.setLanes(device.id, device.parallelJobs);
        if (!queue.beginPoll(device.id)) {
          json(res, 200, { idle: true, duplicate: true });
          return;
        }
        store.touchDevice(device.id);
        const gone = new AbortController();
        const closed = () => { if (!res.writableFinished) gone.abort(); };
        res.on("close", closed);
        try {
          const job = await queue.waitForJob(device.id, pollMs, gone.signal);
          if (job && (gone.signal.aborted || res.destroyed)) {
            queue.release(job.id, device.id);
            return;
          }
          if (!job) {
            if (!res.destroyed) json(res, 200, { idle: true });
            return;
          }
          json(res, 200, { id: job.id, envelope: job.envelope });
        } finally {
          res.off("close", closed);
          queue.endPoll(device.id);
        }
        return;
      }

      if (url.pathname === "/v1/device/revoke-connection" && req.method === "POST") {
        const device = requireDeviceAuth(req, store);
        const body = parseJsonBody(await readBody(req));
        if (!/^agt_[0-9a-f]{16}$/.test(body.agentId || '')) throw new HttpError(400, "invalid connection");
        store.revokeConnection(device.id, body.agentId);
        json(res, 200, {ok:true}); return;
      }

      if (url.pathname === "/v1/device/events" && req.method === "POST") {
        const device = requireDeviceAuth(req, store);
        const body = parseJsonBody(await readBody(req, 64 * 1024));
        store.touchDevice(device.id);
        const entry = recordDeviceEvent(device, body);
        json(res, 200, { ok: true, id: entry.id, seq: entry.seq });
        return;
      }

      if (url.pathname === "/v1/events" && req.method === "GET") {
        const account = requireAccount(req, store);
        const since = Number(url.searchParams.get("since") || "0") || 0;
        const limit = Math.max(1, Math.min(100, Number(url.searchParams.get("limit") || "50") || 50));
        const devFilter = url.searchParams.get("device") || "";
        const scopeId = eventAccountId(account);
        const all = eventsByAccount.get(scopeId) || [];
        const matched = all.filter((e) => e.seq > since
          && (!account.connection || e.deviceId === account.deviceId)
          && (!devFilter || e.device === devFilter || e.deviceId === devFilter));
        const events = matched.slice(-limit);
        const nextCursor = events.length ? events[events.length - 1].seq : since;
        json(res, 200, { ok: true, events, nextCursor });
        return;
      }

      if (url.pathname === "/v1/events/stream" && req.method === "GET") {
        const account = requireAccount(req, store);
        const scopeId = eventAccountId(account);
        let totalStreams = 0;
        for (const s of eventStreams.values()) totalStreams += s.size;
        let streams = eventStreams.get(scopeId);
        if (totalStreams >= 200 || (streams && streams.size >= 4)) throw new HttpError(429, "too many open event streams");
        res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache", connection: "keep-alive" });
        res.write(": connected\n\n");
        if (!streams) { streams = new Set(); eventStreams.set(scopeId, streams); }
        const client = { res, deviceId: account.connection ? account.deviceId : null };
        streams.add(client);
        req.on("close", () => {
          streams.delete(client);
          if (!streams.size) eventStreams.delete(scopeId);
        });
        return;
      }

      if (url.pathname === "/v1/events/webhooks" && ["GET", "POST", "DELETE"].includes(req.method)) {
        const account = requireAccount(req, store);
        const key = webhookKey(account);
        if (req.method === "GET") {
          const hook = webhooks[key];
          json(res, 200, { ok: true, webhook: hook ? { url: hook.url, hasSecret: Boolean(hook.secret) } : null });
          return;
        }
        if (req.method === "DELETE") {
          delete webhooks[key];
          saveWebhooks();
          json(res, 200, { ok: true, webhook: null });
          return;
        }
        const body = parseJsonBody(await readBody(req, 8 * 1024));
        const { url: parsed } = await resolvePublicWebhook(body.url, { lookup: webhookLookup });
        webhooks[key] = { url: parsed.toString(), secret: typeof body.secret === "string" ? body.secret.slice(0, 256) : "" };
        saveWebhooks();
        json(res, 200, { ok: true, webhook: { url: parsed.toString(), hasSecret: Boolean(webhooks[key].secret) } });
        return;
      }

      if (url.pathname === "/v1/connection" && req.method === "DELETE") {
        const account = requireAccount(req, store);
        if (!account.connection) throw new HttpError(400, "not a connection token");
        const scopeId = eventAccountId(account);
        delete webhooks[`dev:${account.deviceId}`];
        saveWebhooks();
        const { droppedDeviceId } = store.revokeConnectionById(account.id);
        if (droppedDeviceId) {
          queue.dropDevice(droppedDeviceId);
          eventsByAccount.delete(scopeId);
        }
        json(res, 200, { ok: true, droppedDeviceId });
        return;
      }

      if (url.pathname === "/v1/device/result" && req.method === "POST") {
        const device = requireDeviceAuth(req, store);
        const raw = await readBody(req, MAX_ENVELOPE + 64_000);
        const body = parseJsonBody(raw);
        store.touchDevice(device.id);
        if (!body.id) throw new HttpError(400, "id is required");
        if (body.envelope) {
          validateEnvelope(body.envelope, Buffer.byteLength(raw));
          if (!queue.complete(body.id, { envelope: body.envelope }, device.id)) throw new HttpError(404, "unknown job for this device");
          json(res, 200, { ok: true });
          return;
        }
        if (body.error != null) {
          const message =
            typeof body.error === "string"
              ? body.error
              : body.error?.message || JSON.stringify(body.error);
          if (!queue.complete(body.id, { error: { message: String(message) } }, device.id)) throw new HttpError(404, "unknown job for this device");
          json(res, 200, { ok: true });
          return;
        }
        throw new HttpError(400, "result requires envelope or error");
      }

      if (url.pathname === "/v1/accounts" && req.method === "POST") {
        requireAdmin(req, adminToken);
        const body = parseJsonBody(await readBody(req));
        const created = store.createAccount({ name: body.name });
        json(res, 200, created);
        return;
      }

      const rotatePath=/^\/v1\/accounts\/([A-Za-z0-9_-]+)\/rotate-key$/.exec(url.pathname);
      if(rotatePath && req.method==="POST") {
        requireAdmin(req,adminToken);
        json(res,200,store.rotateAccountKey(rotatePath[1]));return;
      }

      if (url.pathname === "/v1/me" && req.method === "GET") {
        const account = requireAccount(req, store);
        json(res, 200, { accountId: account.id, name: account.name });
        return;
      }

      if (url.pathname === "/v1/devices" && req.method === "GET") {
        const account = requireAccount(req, store);
        const devices = (account.connection ? [store.deviceById(account.deviceId)] : store.devicesForAccount(account.id)).filter(Boolean).map((d) => listedDevice(d));
        json(res, 200, { devices });
        return;
      }

      if (url.pathname === "/v1/jobs" && req.method === "POST") {
        const account = requireAccount(req, store);
        const body = parseJsonBody(await readBody(req, MAX_ENVELOPE + 64000));
        const device = resolveNamedDevice(store, account, body.device, body.expectedDeviceId);
        validateEnvelope(body.envelope);
        if (account.connection && body.envelope.from !== account.agentId) throw new HttpError(403, "wrong connection identity");
        if (!/^[a-zA-Z0-9_-]{16,100}$/.test(body.id || "")) throw new HttpError(400, "id must be a unique 16–100 character idempotency key");
        const existing = queue.get(body.id);
        if (existing && existing.accountId !== account.id) throw new HttpError(409, "job id unavailable");
        if (!existing && !isRecentlySeen(device, reconnectGraceMs, queue)) throw new HttpError(409, offlineMessage(device.name, device.lastSeen));
        const job = queue.enqueue(device.id, { id: body.id, accountId: account.id,
          envelope: body.envelope, expiresAt: Date.now() + clampTimeout(body.timeoutMs) });
        json(res, 202, { id: job.id, state: job.state }); return;
      }
      const jobPath = /^\/v1\/jobs\/([a-zA-Z0-9_-]+)$/.exec(url.pathname);
      if (jobPath && ["GET", "DELETE"].includes(req.method)) {
        const account = requireAccount(req, store);
        let job = queue.get(jobPath[1]);
        if (!job || job.accountId !== account.id) throw new HttpError(404, "unknown job");
        if (req.method === "DELETE") job = queue.cancel(job.id);
        json(res, 200, { id: job.id, state: job.state, cancelRequested: !!job.cancelRequested,
          createdAt: job.createdAt, expiresAt: job.expiresAt, ...job.outcome }); return;
      }
      if (url.pathname === "/v1/metrics" && req.method === "GET") {
        requireAdmin(req, adminToken); json(res, 200, { jobs: queue.metrics(), files: files.publishedCount() }); return;
      }
      if (url.pathname === "/v1/call" && req.method === "POST") {
        const account = requireAccount(req, store);
        const raw = await readBody(req, MAX_ENVELOPE + 64_000);
        const body = parseJsonBody(raw);
        const device = resolveNamedDevice(store, account, body.device, body.expectedDeviceId);
        validateEnvelope(body.envelope, Buffer.byteLength(raw));
        if (account.connection && body.envelope.from !== account.agentId) throw new HttpError(403, "wrong connection identity");
        const timeoutMs = clampTimeout(body.timeoutMs);
        if (!isRecentlySeen(device, offlineMs, queue)) {
          json(res, 409, { error: offlineMessage(device.name, device.lastSeen) });
          return;
        }
        queue.capacity(device.id, account.id);
        const id = `job-${Date.now().toString(16)}-${crypto.randomBytes(6).toString("hex")}`;
        const resultPromise = queue.waitForResult(id, timeoutMs, { deviceId: device.id });
        queue.enqueue(device.id, { id, envelope: body.envelope, accountId: account.id });
        try {
          const outcome = await resultPromise;
          if (outcome?.error) {
            const message =
              typeof outcome.error === "string" ? outcome.error : outcome.error.message || "unauthorized";
            json(res, 403, { error: String(message) });
            return;
          }
          json(res, 200, { envelope: outcome.envelope });
        } catch (err) {
          if (err.code === "TIMEOUT") {
            json(res, 504, { error: err.message });
            return;
          }
          throw err;
        }
        return;
      }

      if (url.pathname === "/v1/pair/connect" && req.method === "POST") {
        if (!allowPublicPair) {
          const token = bearerToken(req);
          const hasAccount = Boolean(token && store.accountByKey(token));
          let hasAdmin = false;
          if (adminToken && token) {
            const given = crypto.createHash("sha256").update(token, "utf8").digest();
            const expected = crypto.createHash("sha256").update(String(adminToken), "utf8").digest();
            hasAdmin = crypto.timingSafeEqual(given, expected);
          }
          if (!hasAccount && !hasAdmin) {
            json(res, 403, {
              error: "account_required",
              message: "Sign in at android-backstage.com to use the hosted relay, or self-host your own relay from GitHub.",
            });
            return;
          }
        }
        const body = parseJsonBody(await readBody(req));
        const name = String(body.name || 'phone').trim();
        if (!name || name.length > 80) throw new HttpError(400, "invalid connection name");
        json(res, 200, store.createConnection(name, pairSubject));
        return;
      }

      if (url.pathname === "/v1/pair/session" && req.method === "POST") {
        const account = requireAccount(req, store);
        const body = parseJsonBody(await readBody(req));
        const pairing = store.createSession({ accountId: account.id, name: body.name });
        json(res, 200, pairing);
        return;
      }

      const pairMatch = sessionPath(url);
      if (pairMatch) {
        const sessionId = decodeURIComponent(pairMatch[1]);
        const action = pairMatch[2] || "";

        if (!action && req.method === "DELETE") {
          const session = requireSession(sessionId);
          requirePairAgent(req, session);
          if (session.connectionId && session.agentConfirm) throw new HttpError(409, "connection already approved");
          if (session.deviceId && !session.existingDevice) {
            queue.dropDevice(session.deviceId);
            store.deleteDevice(session.deviceId);
          }
          store.deleteSession(session.id);
          notifyPair(`${session.id}:device`, null);
          notifyPair(`${session.id}:confirm`, null);
          json(res, 200, { ok: true });
          return;
        }

        if (action === "agent" && req.method === "PUT") {
          const session = requireSession(sessionId);
          requirePairAgent(req, session);
          const body = parseJsonBody(await readBody(req));
          if (session.agentMsg || session.claimed) throw new HttpError(409, "agent message already set");
          store.updateSession(session.id, {
            agentId: opaqueString(body.agentId, "agentId"),
            agentMsg: opaqueString(body.msg, "msg"),
          });
          json(res, 200, { ok: true });
          return;
        }

        if (action === "device" && req.method === "POST") {
          const session = requireSession(sessionId);
          if (session.claimed) throw new HttpError(400, "pairing session already used");
          if (!session.agentMsg || !session.agentId) {
            throw new HttpError(400, "agent has not posted a SPAKE2 message yet");
          }
          const body = parseJsonBody(await readBody(req));
          if (session.claimed) throw new HttpError(409, "pairing session already used");
          const msg = opaqueString(body.msg, "msg");
          const confirm = body.confirm != null ? opaqueString(body.confirm, "confirm") : null;
          const existing = req.headers.authorization ? requireDeviceAuth(req, store) : null;
          if (session.connectionId) {
            const accountId = existing ? existing.accountId : store.createAccount({name:'phone'}).id;
            store.updateSession(session.id, {accountId});
          }
          if (existing && existing.accountId !== session.accountId) throw new HttpError(403, "device belongs to another account");
          const token = existing ? bearerToken(req) : randomToken("bsd_");
          const device = existing || store.createDevice({
            accountId: session.accountId,
            name: store.uniqueDeviceName(session.accountId, session.name),
            token,
            model: body.model,
            sdk: body.sdk,
            agentVersion: body.agentVersion,

            agents: [session.agentId],
          });
          store.updateSession(session.id, {
            claimed: true,
            existingDevice: !!existing,
            name: device.name,
            deviceId: device.id,
            deviceMsg: msg,
            deviceConfirm: confirm,
          });
          const account = store.accountById(session.accountId);
          if (confirm) notifyPair(`${session.id}:device`, true);
          json(res, 200, {
            deviceId: device.id,
            deviceToken: token,
            name: device.name,
            agentId: session.agentId,
            agentMsg: session.agentMsg,
            accountName: account?.name || session.accountId,
          });
          return;
        }

        if (action === "device-confirm" && req.method === "PUT") {
          const device = requireDeviceAuth(req, store);
          const session = store.sessionById(sessionId);
          if (!session || session.deviceId !== device.id) throw new HttpError(404, "unknown or expired pairing session");
          const body = parseJsonBody(await readBody(req));
          store.updateSession(session.id, { deviceConfirm: opaqueString(body.confirm, "confirm") });
          notifyPair(`${session.id}:device`, true);
          json(res, 200, { ok: true });
          return;
        }

        if (action === "device" && req.method === "GET") {
          let session = requireSession(sessionId);
          requirePairAgent(req, session);
          if (session.deviceMsg && session.deviceConfirm) {
            json(res, 200, { deviceId: session.deviceId, name: session.name, msg: session.deviceMsg, confirm: session.deviceConfirm });
            return;
          }
          await waitPair(`${session.id}:device`, pairPollMs);
          session = store.sessionById(sessionId);
          if (session?.deviceMsg && session?.deviceConfirm) {
            json(res, 200, { deviceId: session.deviceId, name: session.name, msg: session.deviceMsg, confirm: session.deviceConfirm });
            return;
          }
          json(res, 200, { pending: true });
          return;
        }

        if (action === "confirm" && req.method === "PUT") {
          const session = requireSession(sessionId);
          requirePairAgent(req, session);
          const body = parseJsonBody(await readBody(req));
          const confirm = opaqueString(body.confirm, "confirm");
          if (!session.deviceConfirm || !session.deviceId || (session.agentConfirm && session.agentConfirm !== confirm)) throw new HttpError(409, "connection not ready or already confirmed");
          if (session.agentConfirm) {json(res,200,{ok:true});return;}
          store.approveConnection(session, confirm);
          notifyPair(`${session.id}:confirm`, true);
          json(res, 200, { ok: true });
          return;
        }

        if (action === "confirm" && req.method === "GET") {
          const device = requireDeviceAuth(req, store);
          let session = store.sessionById(sessionId);
          if (!session || session.deviceId !== device.id) throw new HttpError(404, "unknown or expired pairing session");
          if (session.agentConfirm) {
            json(res, 200, { confirm: session.agentConfirm });
            return;
          }
          await waitPair(`${session.id}:confirm`, pairPollMs);
          session = store.sessionById(sessionId);
          if (session?.agentConfirm) {
            json(res, 200, { confirm: session.agentConfirm });
            return;
          }
          json(res, 200, { pending: true });
          return;
        }
      }

      const devicePath = /^\/v1\/devices\/([^/]+)$/.exec(url.pathname);
      if (devicePath && req.method === "PATCH") {
        const account = requireAccount(req, store);
        const device = resolveNamedDevice(store, account, decodeURIComponent(devicePath[1]));
        const body = parseJsonBody(await readBody(req));
        const patch = {};
        if (body.name != null) {
          const nextName = String(body.name).trim();
          if (!nextName) throw new HttpError(400, "name must not be empty");
          if (store.nameTaken(account.id, nextName, device.id)) {
            throw new HttpError(409, `device name "${nextName}" is already in use`);
          }
          patch.name = nextName;
        }
        if (body.aliases != null) {
          if (!Array.isArray(body.aliases)) throw new HttpError(400, "aliases must be an array");
          const aliases = body.aliases.map((a) => String(a).trim()).filter(Boolean);
          for (const alias of aliases) {
            if (store.nameTaken(account.id, alias, device.id)) {
              throw new HttpError(409, `alias "${alias}" is already in use`);
            }
          }
          patch.aliases = aliases;
        }
        if (Object.prototype.hasOwnProperty.call(body, "inboundUrl")) {
          patch.inboundUrl = body.inboundUrl ? String(body.inboundUrl).replace(/\/$/, "") : null;
        }
        const updated = store.updateDevice(device.id, patch);
        json(res, 200, listedDevice(updated));
        return;
      }

      if (devicePath && req.method === "DELETE") {
        const account = requireAccount(req, store);
        const device = resolveNamedDevice(store, account, decodeURIComponent(devicePath[1]));
        queue.dropDevice(device.id);
        store.deleteDevice(device.id);
        json(res, 200, { ok: true });
        return;
      }

      sendError(res, 404, "not found");
    } catch (err) {
      const status = err.status || 500;
      if (status >= 500) {
        log.error?.(err);
      }
      const pathname = (() => { try { return new URL(req.url, "http://127.0.0.1").pathname; } catch { return ""; } })();
      const resultError = /^\/v1\/learn\/activities\/[^/]+\/results$/.test(pathname);
      let errorHeaders = {};
      if (resultError) {
        try { errorHeaders = learnOriginHeaders(req, listenUrl, publicUrl); }
        catch { errorHeaders = { "access-control-allow-origin": undefined }; }
      }
      sendError(res, status, err.message || err, errorHeaders);
    }
  });

  let listenUrl = publicUrl || `http://127.0.0.1:${port}`;

  await new Promise((resolve, reject) => {
    const onError = (err) => reject(err);
    server.once("error", onError);
    server.listen(port, host, () => {
      server.off("error", onError);
      resolve();
    });
  });

  const addr = server.address();
  const actualPort = typeof addr === "object" && addr ? addr.port : port;
  listenUrl = publicUrl || `http://127.0.0.1:${actualPort}`;

  async function close() {
    files.stopSweeper();
    uploads.close();
    r2?.close?.();
    learnResults?.close();
    clearInterval(rateTimer);
    clearInterval(eventTimer);
    queue.close();
    for (const list of pairWaiters.values()) {
      for (const waiter of list) {
        clearTimeout(waiter.timer);
        waiter.resolve(null);
      }
    }
    pairWaiters.clear();
    await new Promise((resolve, reject) => {
      server.close((err) => (err ? reject(err) : resolve()));
    });
    dashboard.close();
  }

  return {
    server,
    close,
    url: listenUrl,
    port: actualPort,
    firstApiKey: store.firstApiKey,
    store,
    learnResults,
  };
}

function isMain() {
  const entry = process.argv[1];
  if (!entry) return false;
  try {
    return pathToFileURL(path.resolve(entry)).href === import.meta.url;
  } catch {
    return false;
  }
}

if (isMain()) {
  const zeroRetention = process.env.RELAY_ZERO_RETENTION !== "0";
  const relay = await createRelay({ zeroRetention });
  const bind = process.env.HOST || "0.0.0.0";
  console.log(`backstage-relay ${VERSION} listening on http://${bind}:${relay.port} (zero-retention=${zeroRetention ? "on" : "off"})`);
}
