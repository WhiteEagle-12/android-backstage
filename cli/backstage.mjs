#!/usr/bin/env node
/**
 * Android Backstage CLI — encrypts tool calls, talks to the blind relay.
 * Zero npm deps.
 */
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import {wireArguments} from './wire-arguments.mjs';
import {normalizeArguments} from './tool-aliases.mjs';
import { fileURLToPath, pathToFileURL } from "node:url";
import { Spake2, ALICE_NAME, BOB_NAME, randomCodeChars } from "./spake2.mjs";
import {
  linkKey as deriveLinkKey,
  callKey,
  resultKey,
  confirmA,
  confirmB,
  sealEnvelopeV2,
  openEnvelope,
  nextCounter,
  counterGt,
  b64url,
  unb64url,
  timingSafeEqualBuf,
  encryptFileTo,
} from "./crypto.mjs";
import { budgetMs, INSTRUCTIONS } from "./tools.mjs";
import { writePrivateJson, withConfigLock } from "./state.mjs";
import { beginConnection } from "./connect.mjs";
import { runWorkflow, compileReceipt } from "./workflows.mjs";
import { runMcpStdio } from "./mcp-stdio.mjs";
import { downloadFile } from './download.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, "..");
const IN_REPO = fs.existsSync(path.join(REPO_ROOT, "relay", "server.mjs"));
const USER_CONFIG = path.join(process.env.XDG_CONFIG_HOME || path.join(os.homedir(), ".config"), "android-backstage", "backstage.json");
const DEFAULT_CONFIG = IN_REPO ? path.join(REPO_ROOT, "config", "backstage.json") : USER_CONFIG;
const DEFAULT_PUBLIC_RELAY = "https://relay.android-backstage.com";
const CODE_RE = /^[ABCDEFGHJKMNPQRSTVWXYZ23456789]{4}-[ABCDEFGHJKMNPQRSTVWXYZ23456789]{6}$/;

function usage(message) {
  if (message) console.error(message);
  console.error(`usage:
  backstage devices
  backstage pair [--name phone] [--client-name "ChatGPT Work"]
  backstage rename <old> <new>
  backstage forget <name>
  backstage call <tool> [json-args]
  backstage shell <command...>
  backstage install <apk> [--launch <pkg>]
  backstage push <local> <remote>
  backstage pull <remote> <local>
  backstage self-update <apk>
  backstage mcp [--device name]
  backstage mcp-bridge [--device name] [--print-config]
  backstage mcp-config
  backstage health
  backstage job <id>
  backstage cancel <id>
  backstage workflow <flow.json> [variables-json]
  backstage compile <passed-receipt.json>
  backstage qa <package>

flags: --direct --relay-url --api-key --device|-d --name --launch --config
config: flags → BACKSTAGE_RELAY_URL / BACKSTAGE_API_KEY / BACKSTAGE_DEVICE / BACKSTAGE_CONFIG → ${DEFAULT_CONFIG}`);
  throw Object.assign(new Error(message || "Help requested"), {exitCode: message ? 2 : 0});
}

function parseArgv(argv) {
  const flags = {
    direct: null,
    device: null,
    relayUrl: null,
    apiKey: null,
    name: null,
    clientName: null,
    launch: null,
    config: null,
    printConfig: false,
    help: false,
  };
  const positionals = [];
  for (let i = 0; i < argv.length; i += 1) {
    const a = argv[i];
    const next = () => {
      const v = argv[i + 1];
      if (v == null) usage(`missing value for ${a}`);
      i += 1;
      return v;
    };
    if (a === "--") {
      positionals.push(...argv.slice(i + 1));
      break;
    }
    if (a === "-h" || a === "--help") flags.help = true;
    else if (a === "--print-config") flags.printConfig = true;
    else if (a === "--device" || a === "-d") flags.device = next();
    else if (a.startsWith("--device=")) flags.device = a.slice(9);
    else if (a.startsWith("-d=")) flags.device = a.slice(3);
    else if (a === "--direct") flags.direct = next();
    else if (a === "--relay-url") flags.relayUrl = next();
    else if (a.startsWith("--relay-url=")) flags.relayUrl = a.slice(12);
    else if (a === "--api-key") flags.apiKey = next();
    else if (a.startsWith("--api-key=")) flags.apiKey = a.slice(10);
    else if (a === "--client-name") flags.clientName = next();
    else if (a === "--name") flags.name = next();
    else if (a.startsWith("--name=")) flags.name = a.slice(7);
    else if (a === "--launch") flags.launch = next();
    else if (a.startsWith("--launch=")) flags.launch = a.slice(9);
    else if (a === "--config") flags.config = next();
    else if (a.startsWith("--config=")) flags.config = a.slice(9);
    else if (a.startsWith("-")) usage(`unknown flag: ${a}`);
    else positionals.push(a);
  }
  return { flags, positionals };
}

function configPath(flags = {}) {
  return path.resolve(flags.config || process.env.BACKSTAGE_CONFIG || DEFAULT_CONFIG);
}

function loadFileConfig(filePath, cfg = null) {
  if (cfg?.readConfig) return cfg.readConfig(filePath);
  try {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  } catch (err) {
    if (err.code === "ENOENT") return {};
    throw new Error(`Cannot read config ${filePath}: ${err.message}`);
  }
}

function saveFileConfig(filePath, data, cfg = null) {
  if (cfg?.writeConfig) return cfg.writeConfig(filePath, data);
  writePrivateJson(filePath, data);
}

function resolveConfig(flags) {
  const filePath = configPath(flags);
  const file = loadFileConfig(filePath);
  const fallbackRelay = DEFAULT_PUBLIC_RELAY;
  return {
    filePath,
    file,
    directUrl: flags.direct || null,
    relayUrl: String(flags.relayUrl || process.env.BACKSTAGE_RELAY_URL || file.relayUrl || fallbackRelay).replace(/\/$/, ""),
    apiKey: flags.apiKey || process.env.BACKSTAGE_API_KEY || file.apiKey || "",
    device: flags.device || process.env.BACKSTAGE_DEVICE || file.device || "",
    agentId: file.agentId || "",
    links: file.links && typeof file.links === "object" ? file.links : {},
  };
}

function persistConfig(cfg, patch) {
  const current = loadFileConfig(cfg.filePath, cfg);
  const next = {
    ...current,
    relayUrl: cfg.relayUrl || current.relayUrl || "",
    apiKey: cfg.apiKey || current.apiKey || "",
    device: cfg.device || current.device || "",
    agentId: cfg.agentId || current.agentId || "",
    links: cfg.links || current.links || {},
    ...patch,
  };
  if (patch.links) next.links = patch.links;
  saveFileConfig(cfg.filePath, next, cfg);
  cfg.file = next;
  if (next.agentId) cfg.agentId = next.agentId;
  if (next.links) cfg.links = next.links;
  if (next.device) cfg.device = cfg.device || next.device;
  return next;
}

function ensureAgentId(cfg) {
  if (cfg.agentId && /^agt_[0-9a-f]{16}$/.test(cfg.agentId)) return cfg.agentId;
  const agentId = `agt_${crypto.randomBytes(8).toString("hex")}`;
  cfg.agentId = agentId;
  persistConfig(cfg, { agentId });
  return agentId;
}

function printJson(value) {
  console.log(JSON.stringify(value, null, 2));
}

function dieTool(message, extra) {
  printJson(extra || { error: message });
  throw new Error(message);
}

async function readJson(res) {
  const text = await res.text();
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch {
    return { error: text };
  }
}

function effectiveApiKey(cfg, deviceName = cfg.device) {
  return (deviceName && cfg.links?.[deviceName]?.apiKey) || cfg.apiKey || "";
}

async function api(cfg, pathname, { method = "GET", body, headers = {}, timeoutMs = 30_000, rawBody, apiKey = null } = {}) {
  if (!cfg.relayUrl) usage("missing relay URL (set --relay-url, BACKSTAGE_RELAY_URL, or config/backstage.json)");
  // A host-local relay address (the hosted gateway's) is used for transport only, never saved.
  const url = `${cfg.transportRelayUrl || cfg.relayUrl}${pathname}`;
  const hdrs = { ...headers };
  const token = apiKey || effectiveApiKey(cfg);
  if (token && !hdrs.authorization) hdrs.authorization = `Bearer ${token}`;
  let payload = rawBody;
  if (payload == null && body != null) {
    hdrs["content-type"] = hdrs["content-type"] || "application/json";
    payload = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(url, {
      method,
      headers: hdrs,
      body: payload,
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (err) {
    throw new Error(err.message || String(err));
  }
  const parsed = await readJson(res);
  return { status: res.status, body: parsed, res };
}

async function requireApiKey(cfg) {
  if (!cfg.apiKey) usage("missing API key (set --api-key, BACKSTAGE_API_KEY, or config/backstage.json)");
}

function requireDeviceName(cfg) {
  if (!cfg.device) usage("missing device (set --device, BACKSTAGE_DEVICE, or config/backstage.json)");
  return cfg.device;
}

function linkFor(cfg, name) {
  const link = cfg.links?.[name];
  if (!link?.linkKey || !link?.deviceId) {
    throw new Error(`no link for device ${name}; run backstage pair`);
  }
  return link;
}

function saveLink(cfg, name, link) {
  const links = { ...(cfg.links || {}) };
  links[name] = link;
  cfg.links = links;
  persistConfig(cfg, { links, device: cfg.device || name, agentId: cfg.agentId });
}

async function encryptedCall(cfg, tool, args, options = {}) {
  const queuedAt=performance.now();
  // Hold the link lock only while numbering, sealing and submitting a call, so
  // several agents sharing one link can have calls running on the phone at once.
  // The phone accepts call counters inside a replay window, and results are
  // checked the same way, so out-of-order completion is safe.
  const submitted = await withConfigLock(cfg.filePath, async () => {
    // The hosted gateway can revoke one grant while it waits on a shared link.
    // Check after acquiring the link lock, before sending any phone command.
    options.authorize?.();
    const current = loadFileConfig(cfg.filePath, cfg);
    cfg.links = current.links || {};
    cfg.agentId = current.agentId || cfg.agentId;
    const queueMs=performance.now()-queuedAt,started=performance.now();
    if (cfg.directUrl || linkFor(cfg, options.device || requireDeviceName(cfg)).directUrl) {
      // The direct LAN path answers synchronously; keep the whole exchange locked.
      return {done:await encryptedCallLocked(cfg, tool, args, options),queueMs,started};
    }
    // A route found for this call only (the hosted gateway's tailnet path): seal now, send unlocked.
    if (options.directUrl) return {direct:sealCall(cfg, tool, args, options),queueMs,started};
    return {pending:await submitCall(cfg, tool, args, options),queueMs,started};
  }, { diskLock: !cfg.inProcessLockOnly });
  const result = submitted.done ?? (submitted.direct ? await directThenRelay(cfg, tool, args, options, submitted.direct) : await finishCall(cfg, submitted.pending));
  // Always report the link queue and relay round trip; the phone adds deviceTotalMs.
  if(result&&typeof result==='object'){
    const queueMs=Math.round(submitted.queueMs),roundTripMs=Math.round(performance.now()-submitted.started);
    result.timing={...(result.timing&&typeof result.timing==='object'?result.timing:{}),linkQueueMs:queueMs,relayRoundTripMs:roundTripMs,
      ...(args?.timing?{hostQueueMs:queueMs,hostRoundTripMs:roundTripMs,responseDeliveryMs:null,screenshotUploadMs:null}:{})};
  }
  return result;
}

// Results may complete out of order across agents sharing a link. Accept a
// counter once: above the highest seen, or inside the window and not yet seen.
const RESULT_WINDOW = 1024n;
export function acceptResultCounter(link, n) {
  const value = BigInt(n), highest = BigInt(link.lastResultCounter || "0");
  // Before the window existed, every counter up to the saved highest was used.
  if (!link.recentResultCounters) { link.resultCounterFloor = String(highest); link.recentResultCounters = []; }
  const recent = new Set(link.recentResultCounters);
  if (value <= BigInt(link.resultCounterFloor || "0") || value <= highest - RESULT_WINDOW || recent.has(String(value))) return false;
  recent.add(String(value));
  const floor = (value > highest ? value : highest) - RESULT_WINDOW;
  link.recentResultCounters = [...recent].filter(c => BigInt(c) > floor).slice(-1024);
  if (value > highest) link.lastResultCounter = String(value);
  return true;
}

/** Number, seal and persist one call. Caller holds the link lock. Reusing `callId` lets the phone's journal dedupe a resend. */
function sealCall(cfg, tool, args, { device, callId: reuse, deadline, subOwner } = {}) {
  args = wireArguments(tool,args);
  const name = device || requireDeviceName(cfg);
  const link = linkFor(cfg, name);
  const budget = deadline ? Math.max(1000, deadline - Date.now() - 5000) : budgetMs(tool);
  const callId = reuse || `call-${crypto.randomBytes(8).toString("hex")}`;
  const plaintext = JSON.stringify({
    id: callId, deviceId: link.deviceId, name: tool, arguments: args || {},
    ...(subOwner ? { subOwner } : {}),
    timeoutMs: budget, expiresAt: Date.now() + budget + 5000,
  });
  const n = String(link.callCounter || Date.now());
  const key = callKey(unb64url(link.linkKey));
  const envelope = sealEnvelopeV2({ key, from: link.agentId || ensureAgentId(cfg), n, plaintext });
  link.callCounter = nextCounter(n);
  saveLink(cfg, name, link);
  return {name, tool, callId, envelope, relayTimeout: budget + 5000, deadline: Date.now() + budget + 5000};
}

async function submitCall(cfg, tool, args, options = {}) {
  const {name, callId, envelope, relayTimeout} = sealCall(cfg, tool, args, options);
  let submitted;
  try {
    submitted = await api(cfg, "/v1/jobs", { method: "POST", body: {
      id: callId, device: name, expectedDeviceId: linkFor(cfg, name).deviceId, envelope, timeoutMs: relayTimeout }, timeoutMs: 15000 });
  } catch (err) {
    err.jobId=callId; err.inputDelivered=null;
    err.message = `job ${callId}: ${err.message}. Query job status before retrying an action`;
    throw err;
  }
  // A definitive submission rejection never created a job. Do not invent a
  // recoverable job ID or turn a queue limit into an uncertain phone action.
  if ([400,401,403,404,409,413,422,429].includes(submitted.status)) {
    const body=submitted.body;
    const error=new Error(typeof body?.error==='string'?body.error:`Relay rejected the request (HTTP ${submitted.status})`);
    error.inputDelivered=false;
    error.code=submitted.status===429?'relay_busy':'submission_rejected';
    throw error;
  }
  return {name, tool, callId, relayTimeout, status:submitted.status, body:submitted.body};
}

async function finishCall(cfg, {name, tool, callId, relayTimeout, status, body}) {
  try {
    if (status === 202) ({status, body} = await awaitJob(cfg, tool, callId, relayTimeout));
    return await openResult(cfg, name, callId, status, body);
  } catch (err) {
    if(err.inputDelivered===false)throw err;
    err.jobId=callId;
    err.inputDelivered=null;
    err.message = `job ${callId}: ${err.message}. Query job status before retrying an action`;
    throw err;
  }
}

const directAgent = new http.Agent({ keepAlive: true, keepAliveMsecs: 10_000, maxSockets: 16, maxFreeSockets: 8, timeout: 20_000 });

/**
 * POST one sealed call to the phone's own listener (tailnet or LAN). Resolves
 * {status, body}; rejects with `delivered` false when the phone was never reached.
 */
export function postDirect(endpoint, envelope, timeoutMs, connectTimeoutMs = 2500, reusePool = true) {
  return new Promise((resolve, reject) => {
    let url;
    try { url = new URL(endpoint.replace(/\/$/, "") + "/call"); } catch (e) { e.delivered = false; reject(e); return; }
    const payload = Buffer.from(JSON.stringify({ envelope }));
    let connected = false, settled = false;
    const finish = (fn, value) => { if (!settled) { settled = true; clearTimeout(connectTimer); clearTimeout(totalTimer); fn(value); } };
    const req = http.request({ host: url.hostname, port: url.port || 80, path: url.pathname, method: "POST", agent: reusePool ? directAgent : false,
      headers: { "content-type": "application/json", "content-length": payload.length, "connection": reusePool ? "keep-alive" : "close" } }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        let body = {};
        try { body = JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}"); } catch { body = { error: "invalid direct reply" }; }
        finish(resolve, { status: res.statusCode, body });
      });
      res.on("error", (e) => { e.delivered = true; finish(reject, e); });
    });
    const connectTimer = setTimeout(() => { if (!connected) req.destroy(Object.assign(new Error("direct route connect timeout"), { code: "ECONNTIMEOUT" })); }, connectTimeoutMs);
    const totalTimer = setTimeout(() => req.destroy(Object.assign(new Error("direct route timed out"), { code: "ETIMEDOUT_TOTAL" })), timeoutMs);
    req.on("socket", (socket) => {
      if (socket.connecting) socket.once("connect", () => { connected = true; });
      else connected = true;
    });
    req.on("error", (e) => {
      if (reusePool && req.reusedSocket && (e.code === "ECONNRESET" || e.code === "EPIPE") && !settled) {
        settled = true; clearTimeout(connectTimer); clearTimeout(totalTimer);
        postDirect(endpoint, envelope, timeoutMs, connectTimeoutMs, false).then(resolve, reject);
        return;
      }
      e.delivered = connected;
      finish(reject, e);
    });
    req.end(payload);
  });
}

/**
 * Send over the direct route; if that fails, send the same call ID through the relay.
 * A phone that never received the call runs it once from the relay. A phone that
 * did receive it answers the resend from its request journal, so a lost direct
 * reply never makes an action run twice.
 */
async function directThenRelay(cfg, tool, args, options, sealed) {
  const {name, callId, envelope, relayTimeout, deadline} = sealed;
  let delivered = false;
  try {
    const reply = await postDirect(options.directUrl, envelope, relayTimeout + 3000);
    delivered = true;
    if (reply.body?.envelope) {
      options.onRoute?.("direct");
      return await finishCall(cfg, {name, tool, callId, relayTimeout, status: 200, body: reply.body});
    }
    // Refused before execution (wrong phone at that address, link mismatch, HTTP error): nothing ran.
    delivered = false;
    options.onRoute?.("relay", `direct refused: ${typeof reply.body?.error === "string" ? reply.body.error : reply.body?.error?.message || "HTTP " + reply.status}`);
  } catch (e) {
    delivered = delivered || e.delivered === true;
    options.onRoute?.("relay", `direct failed: ${e.code || e.message}`);
  }
  for (;;) {
    const pending = await withConfigLock(cfg.filePath, async () => {
      options.authorize?.();
      const current = loadFileConfig(cfg.filePath, cfg);
      cfg.links = current.links || {};
      cfg.agentId = current.agentId || cfg.agentId;
      return submitCall(cfg, tool, args, {...options, callId, deadline});
    }, { diskLock: !(cfg.inProcessLockOnly || options.inProcessLockOnly) });
    try {
      return await finishCall(cfg, pending);
    } catch (e) {
      // The direct copy may still be running; its journal entry answers once it finishes.
      if (delivered && /outcome is unknown/i.test(String(e.message)) && Date.now() + 3000 < deadline) {
        await new Promise((r) => setTimeout(r, 2000));
        continue;
      }
      throw e;
    }
  }
}

async function awaitJob(cfg, tool, callId, relayTimeout) {
  let status, body;
  const until = Date.now() + relayTimeout;
  // A self-update deliberately restarts the device. Keep reading the same
  // durable job while its saved result reconnects; never submit it again.
  const waiting = state => ["queued","running"].includes(state) ||
    (tool === "android.maintenance" && state === "unknown");
  let pollFailures=0;
  do {
    let polled;
    const pollStart = Date.now();
    const waitMs = Math.max(1, Math.min(12000, until - pollStart));
    try {
      polled=await api(cfg, `/v1/jobs/${callId}`, {headers:{"x-backstage-wait-ms":String(waitMs)},timeoutMs:Math.min(20000, waitMs + 5000)});
      if([429,502,503,504].includes(polled.status))throw new Error(`Temporary relay error (HTTP ${polled.status})`);
    } catch(error) {
      if(Date.now()>=until||++pollFailures>=3)throw error;
      await new Promise(r=>setTimeout(r,Math.min(300*pollFailures,Math.max(0,until-Date.now()))));
      continue;
    }
    pollFailures=0;
    status=polled.status;body=polled.body;
    if (status >= 400 || !waiting(body.state)) break;
    // Back off only if talking to an older relay that ignored waitMs and returned immediately.
    if (Date.now() - pollStart < 100) await new Promise(r=>setTimeout(r,300));
  } while(Date.now()<until);
  if (["queued","running"].includes(body?.state)) {
    const stopped=await api(cfg, `/v1/jobs/${callId}`, {method:"DELETE",timeoutMs:15000});
    // Never claimed by the phone: it was offline the whole time and nothing ran.
    if (body.state === "queued" && stopped.body?.state === "cancelled") {
      const error = new Error("device is offline: the phone did not reconnect before the deadline (last seen before this call); the command was not sent");
      error.inputDelivered = false;
      throw error;
    }
    throw new Error(`job ${callId}: ${stopped.body.state}; cancellation requested. Query its status before retrying`);
  }
  if (!body?.envelope && !body?.error) throw new Error(`job ${callId}: ${body?.state}`);
  return {status, body};
}

async function openResult(cfg, name, callId, status, body) {
  if (body?.error && !body.envelope) {
    throw new Error(typeof body.error === "string" ? body.error : body.error.message || JSON.stringify(body.error));
  }
  if (status >= 400) throw new Error(body?.error || `HTTP ${status}`);
  if (!body?.envelope) throw new Error("relay returned no envelope");
  return withConfigLock(cfg.filePath, async () => {
    const current = loadFileConfig(cfg.filePath, cfg);
    cfg.links = current.links || {};
    const link = linkFor(cfg, name);
    const opened = openEnvelope({ key: resultKey(unb64url(link.linkKey)), envelope: body.envelope });
    if (body.envelope.from !== link.deviceId) throw new Error(`result from mismatch (got ${body.envelope.from})`);
    let parsed;
    try { parsed = JSON.parse(opened.toString("utf8")); } catch { throw new Error("result plaintext is not JSON"); }
    if (parsed.id !== callId) throw new Error("result id does not match call");
    if (!acceptResultCounter(link, body.envelope.n)) throw new Error("result counter replayed or out of order");
    saveLink(cfg, name, link);
    if (parsed.error) {
      const message = typeof parsed.error === "string" ? parsed.error : parsed.error.message || JSON.stringify(parsed.error);
      const err = new Error(message); err.result = parsed; throw err;
    }
    return parsed.result;
  }, { diskLock: !cfg.inProcessLockOnly });
}

async function encryptedCallLocked(cfg, tool, args, { device, subOwner } = {}) {
  args = wireArguments(tool,args);
  const name = device || requireDeviceName(cfg);
  const link = linkFor(cfg, name);
  const budget = budgetMs(tool);
  const callId = `call-${crypto.randomBytes(8).toString("hex")}`;
  const timeoutMs = budget;
  const plaintext = JSON.stringify({
    id: callId,
    deviceId: link.deviceId,
    name: tool,
    arguments: args || {},
    ...(subOwner ? { subOwner } : {}),
    timeoutMs,
    expiresAt: Date.now() + budget + 5000,
  });
  const n = String(link.callCounter || Date.now());
  const key = callKey(unb64url(link.linkKey));
  const envelope = sealEnvelopeV2({ key, from: link.agentId || ensureAgentId(cfg), n, plaintext });
  link.callCounter = nextCounter(n);
  saveLink(cfg, name, link);

  try {
  const relayTimeout = budget + 5000;
  let status, body;
  const endpoint = cfg.directUrl || link.directUrl;
  if (endpoint) {
    const response = await fetch(`${endpoint.replace(/\/$/, "")}/call`, {
      method: "POST", headers: {"content-type":"application/json"},
      body: JSON.stringify({envelope}), signal: AbortSignal.timeout(relayTimeout + 8000),
    }); status=response.status; body=await response.json();
  } else {
    const submitted = await api(cfg, "/v1/jobs", { method: "POST", body: {
      id: callId, device: name, expectedDeviceId: link.deviceId, envelope, timeoutMs: relayTimeout }, timeoutMs: 15000 });
    status=submitted.status; body=submitted.body;
    // A definitive submission rejection never created a job. Do not invent a
    // recoverable job ID or turn a queue limit into an uncertain phone action.
    if ([400,401,403,404,409,413,422,429].includes(status)) {
      const error=new Error(typeof body?.error==='string'?body.error:`Relay rejected the request (HTTP ${status})`);
      error.inputDelivered=false;
      error.code=status===429?'relay_busy':'submission_rejected';
      throw error;
    }
    if (status === 202) {
      const until = Date.now() + relayTimeout;
      // A self-update deliberately restarts the device. Keep reading the same
      // durable job while its saved result reconnects; never submit it again.
      const waiting = state => ["queued","running"].includes(state) ||
        (tool === "android.maintenance" && state === "unknown");
      let pollFailures=0;
      do {
        let polled;
        const pollStart = Date.now();
        const waitMs = Math.max(1, Math.min(12000, until - pollStart));
        try {
          polled=await api(cfg, `/v1/jobs/${callId}`, {headers:{"x-backstage-wait-ms":String(waitMs)},timeoutMs:Math.min(20000, waitMs + 5000)});
          if([429,502,503,504].includes(polled.status))throw new Error(`Temporary relay error (HTTP ${polled.status})`);
        } catch(error) {
          if(Date.now()>=until||++pollFailures>=3)throw error;
          await new Promise(r=>setTimeout(r,Math.min(300*pollFailures,Math.max(0,until-Date.now()))));
          continue;
        }
        pollFailures=0;
        status=polled.status;body=polled.body;
        if (status >= 400 || !waiting(body.state)) break;
        if (Date.now() - pollStart < 100) await new Promise(r=>setTimeout(r,300));
      } while(Date.now()<until);
      if (["queued","running"].includes(body.state)) {
        const stopped=await api(cfg, `/v1/jobs/${callId}`, {method:"DELETE",timeoutMs:15000});
        throw new Error(`job ${callId}: ${stopped.body.state}; cancellation requested. Query its status before retrying`);
      }
      if (!body.envelope && !body.error) throw new Error(`job ${callId}: ${body.state}`);
    }
  }
  if (body?.error && !body.envelope) {
    throw new Error(typeof body.error === "string" ? body.error : body.error.message || JSON.stringify(body.error));
  }
  if (status >= 400) {
    throw new Error(body?.error || `HTTP ${status}`);
  }
  if (!body?.envelope) throw new Error("relay returned no envelope");
  const opened = openEnvelope({ key: resultKey(unb64url(link.linkKey)), envelope: body.envelope });
  if (body.envelope.from !== link.deviceId) {
    throw new Error(`result from mismatch (got ${body.envelope.from})`);
  }
  if (!acceptResultCounter(link, body.envelope.n)) {
    throw new Error("result counter replayed or out of order");
  }
  let parsed;
  try {
    parsed = JSON.parse(opened.toString("utf8"));
  } catch {
    throw new Error("result plaintext is not JSON");
  }
  if (parsed.id !== callId) throw new Error("result id does not match call");
  saveLink(cfg, name, link);
  if (parsed.error) {
    const message =
      typeof parsed.error === "string" ? parsed.error : parsed.error.message || JSON.stringify(parsed.error);
    const err = new Error(message);
    err.result = parsed;
    throw err;
  }
  return parsed.result;
  } catch (err) {
    if(err.inputDelivered===false)throw err;
    err.jobId=callId;
    err.inputDelivered=null;
    err.message = `job ${callId}: ${err.message}. Query job status before retrying an action`;
    throw err;
  }
}

async function uploadFile(cfg, filePath, fileName) {
  const abs = path.resolve(filePath);
  if (!fs.existsSync(abs) || !fs.statSync(abs).isFile()) {
    usage(`file not found: ${filePath}`);
  }
  const size=fs.statSync(abs).size;
  await requireApiKey(cfg);
  const uploadKey = cfg.links?.[cfg.device]?.apiKey || cfg.apiKey;
  const started=await api(cfg,"/v1/uploads",{method:"POST",apiKey:uploadKey,body:{size,name:fileName||path.basename(abs)}});
  if(started.status!==201)throw new Error(started.body.error||"upload start failed");
  const id=started.body.id;
  let offset=0, failures=0;
  while(offset<size){
    try{
      const end=Math.min(size,offset+8*1024*1024);
      const res=await fetch(`${cfg.transportRelayUrl||cfg.relayUrl}/v1/uploads/${id}`,{method:"PUT",
        headers:{authorization:`Bearer ${uploadKey}`,"x-upload-offset":String(offset),"content-length":String(end-offset)},
        body:fs.createReadStream(abs,{start:offset,end:end-1}),duplex:"half",signal:AbortSignal.timeout(60000)});
      const data=await readJson(res);if(!res.ok)throw new Error(data.error||"chunk failed");
      offset=data.offset;failures=0;
    }catch(e){
      if(++failures>3)throw new Error(`Upload ${id} interrupted: ${e.message}`);
      const progress=await api(cfg,`/v1/uploads/${id}`,{apiKey:uploadKey});
      if(progress.status!==200)throw new Error(progress.body.error||"upload unavailable");
      offset=progress.body.offset;
    }
  }
  const done=await api(cfg,`/v1/uploads/${id}/complete`,{method:"POST",apiKey:uploadKey,body:{}});
  if(done.status!==200)throw new Error(done.body.error||"upload finalize failed");return done.body;
}

async function encryptAndUpload(cfg, filePath) {
  const fileKey = crypto.randomBytes(32);
  const tmp = path.join(os.tmpdir(), `bsf1-${crypto.randomBytes(8).toString("hex")}.bin`);
  try {
    const { sha256 } = encryptFileTo(filePath, tmp, fileKey);
    const uploaded = await uploadFile(cfg, tmp, `${path.basename(filePath)}.bsf1`);
    return { url: uploaded.url, fileKey: b64url(fileKey), sha256, id: uploaded.id };
  } finally {
    try {
      fs.unlinkSync(tmp);
    } catch {
      /* ignore */
    }
  }
}

async function cmdDevices(cfg) {
  await requireApiKey(cfg);
  const { status, body } = await api(cfg, "/v1/devices");
  if (status !== 200) dieTool(body.error || `HTTP ${status}`, body);
  printJson(body);
}

async function cmdPair(cfg, flags) {
  if (!cfg.apiKey || String(cfg.apiKey).startsWith("bsc_")) {
    const requestedName = flags.name || (!Object.keys(cfg.links || {}).length ? (cfg.device || 'phone') : 'phone');
    const connection = await beginConnection({relayUrl:cfg.relayUrl, name:requestedName, clientName:flags.clientName || 'Assistant'});
    console.log(`Open Android Backstage and enter ${connection.code}`);
    console.log(`Open on your phone: ${connection.phoneLink}`);
    console.log('Waiting for approval on your phone…');
    for (;;) {
      const linked = await connection.poll();
      if (!linked) continue;
      const devName = linked.device;
      const mergedLinks = {
        ...(cfg.links || {}),
        [devName]: { ...linked.links[devName], apiKey: linked.apiKey, agentId: linked.agentId },
      };
      persistConfig(cfg, {
        relayUrl: linked.relayUrl,
        apiKey: cfg.apiKey || linked.apiKey,
        agentId: cfg.agentId || linked.agentId,
        device: cfg.device || devName,
        links: mergedLinks,
      });
      printJson({ok:true,name:devName,deviceId:linked.links[devName].deviceId,agentId:linked.agentId});
      return;
    }
  }
  await requireApiKey(cfg);
  const name = flags.name || cfg.device || "phone";
  const agentId = ensureAgentId(cfg);
  let status;
  let body;
  try {
    ({ status, body } = await api(cfg, "/v1/pair/session", {
      method: "POST",
      body: { name },
    }));
  } catch (err) {
    dieTool(err.message);
  }
  if (status !== 200) dieTool(body.error || `HTTP ${status}`, body);
  const sessionId = body.sessionId;
  let deviceName = body.name || name;
  const secret = randomCodeChars(6);
  const code = `${sessionId}-${secret}`;
  if (!CODE_RE.test(code)) dieTool("internal: bad pairing code");

  console.log("");
  console.log("============================================================");
  console.log(`  ${code}`);
  console.log("============================================================");
  console.log(`Relay: ${cfg.relayUrl}`);
  console.log("Open Android Backstage and enter this connection code.");
  console.log("Keep this command open until the phone connects.");
  const setupLink = new URL("androidbackstage://connect");
  setupLink.searchParams.set("relay", cfg.relayUrl);
  setupLink.searchParams.set("code", code);
  if (flags.clientName) setupLink.searchParams.set("name", flags.clientName);
  console.log(`Open on your phone: ${setupLink.href}`);
  console.log("");

  const alice = new Spake2("alice", ALICE_NAME, BOB_NAME);
  const aliceMsg = alice.generateMsg(Buffer.from(code, "utf8"));
  try {
    const put = await api(cfg, `/v1/pair/session/${sessionId}/agent`, {
      method: "PUT",
      body: { agentId, msg: b64url(aliceMsg) },
    });
    if (put.status !== 200) dieTool(put.body.error || `HTTP ${put.status}`, put.body);
  } catch (err) {
    dieTool(err.message);
  }

  const deadline = Date.now() + 10 * 60 * 1000;
  let deviceSide = null;
  while (Date.now() < deadline) {
    let got;
    try {
      got = await api(cfg, `/v1/pair/session/${sessionId}/device`, { timeoutMs: 30_000 });
    } catch (err) {
      dieTool(err.message);
    }
    if (got.status !== 200) dieTool(got.body.error || `HTTP ${got.status}`, got.body);
    if (got.body?.msg && got.body?.confirm) {
      deviceSide = got.body;
      break;
    }
  }
  if (!deviceSide) dieTool("timed out waiting for the phone to join the pairing session");

  const k = alice.processMsg(unb64url(deviceSide.msg));
  const lk = deriveLinkKey(k);
  const expectedB = confirmB(lk);
  let gotB;
  try {
    gotB = unb64url(deviceSide.confirm);
  } catch {
    gotB = Buffer.alloc(0);
  }
  if (!timingSafeEqualBuf(expectedB, gotB)) {
    try {
      await api(cfg, `/v1/pair/session/${sessionId}`, { method: "DELETE" });
    } catch {
      /* ignore */
    }
    throw new Error("wrong code or tampering; try again");
  }

  const putConfirm = await api(cfg, `/v1/pair/session/${sessionId}/confirm`, {
    method: "PUT",
    body: { confirm: b64url(confirmA(lk)) },
  });
  if (putConfirm.status !== 200) dieTool(putConfirm.body.error || `HTTP ${putConfirm.status}`, putConfirm.body);

  const now = String(Date.now());
  deviceName = deviceSide.name || deviceName;
  saveLink(cfg, deviceName, {
    deviceId: deviceSide.deviceId,
    linkKey: b64url(lk),
    callCounter: now,
    lastResultCounter: "0",
  });
  persistConfig(cfg, { device: cfg.device || deviceName });
  printJson({
    ok: true,
    name: deviceName,
    deviceId: deviceSide.deviceId,
    sessionId,
    agentId,
  });
}

async function cmdRename(cfg, oldName, newName) {
  await requireApiKey(cfg);
  if (!oldName || !newName) usage("backstage rename <old> <new>");
  const { status, body } = await api(cfg, `/v1/devices/${encodeURIComponent(oldName)}`, {
    method: "PATCH",
    body: { name: newName },
  });
  if (status !== 200) dieTool(body.error || `HTTP ${status}`, body);
  if (cfg.links?.[oldName]) {
    const links = { ...cfg.links };
    links[newName] = links[oldName];
    delete links[oldName];
    persistConfig(cfg, { links, device: cfg.device === oldName ? newName : cfg.device });
  }
  printJson(body);
}

async function cmdForget(cfg, name) {
  await requireApiKey(cfg);
  if (!name) usage("backstage forget <name>");
  const devKey = cfg.links?.[name]?.apiKey || cfg.apiKey;
  const isConn = String(devKey || "").startsWith("bsc_");
  const { status, body } = isConn
    ? await api(cfg, "/v1/connection", { method: "DELETE", apiKey: devKey })
    : await api(cfg, `/v1/devices/${encodeURIComponent(name)}`, { method: "DELETE" });
  if (status !== 200) dieTool(body.error || `HTTP ${status}`, body);
  if (cfg.links?.[name]) {
    const links = { ...cfg.links };
    delete links[name];
    const remaining = Object.keys(links);
    const nextDev = cfg.device === name ? (remaining[0] || "") : cfg.device;
    const patch = { links, device: nextDev };
    if (isConn && cfg.apiKey === devKey) patch.apiKey = links[nextDev]?.apiKey || "";
    persistConfig(cfg, patch);
  }
  printJson(body);
}

async function cmdCall(cfg, tool, jsonArgs) {
  await requireApiKey(cfg);
  if (!tool) usage("backstage call <tool> [json-args]");
  let args = {};
  if (jsonArgs != null && jsonArgs !== "") {
    try {
      args = JSON.parse(jsonArgs);
    } catch {
      usage(`invalid JSON arguments: ${jsonArgs}`);
    }
  }
  try {
    const payload = await encryptedCall(cfg, tool, args);
    printJson(payload);
    if(["failed","unknown"].includes(payload?.outcome))process.exitCode=1;
  } catch (err) {
    dieTool(err.message);
  }
}

async function cmdShell(cfg, commandParts) {
  await requireApiKey(cfg);
  if (!commandParts.length) usage("backstage shell <command...>");
  try {
    const payload = await encryptedCall(cfg, "android.shell", {requestId:crypto.randomUUID(),command:commandParts.join(" ")});
    printJson(payload);
    if(["failed","unknown"].includes(payload?.outcome))process.exitCode=1;
  } catch (err) {
    dieTool(err.message);
  }
}

function unwrapObject(payload) {
  return payload && typeof payload === "object" && !Array.isArray(payload) ? payload : { result: payload };
}

async function cmdInstall(cfg, apk, launch) {
  await requireApiKey(cfg);
  requireDeviceName(cfg);
  if (!apk) usage("backstage install <apk> [--launch <pkg>]");
  try {
    const uploaded = await encryptAndUpload(cfg, apk);
    const installed = await encryptedCall(cfg, "android.install", {requestId:crypto.randomUUID(),source:{url:uploaded.url,fileKey:uploaded.fileKey,sha256:uploaded.sha256}});
    let launched = null;
    if (launch) {
      if(installed.outcome==='succeeded'){
        const task=await encryptedCall(cfg,'android.task_begin',{requestId:crypto.randomUUID()});
        if(task.outcome==='succeeded')launched=await encryptedCall(cfg,'android.open',{requestId:crypto.randomUUID(),sessionId:task.sessionId,package:launch,verify:{kind:'window',package:launch}});
      }
    }
    printJson(
      launch
        ? { install: installed, launch: launched, url: uploaded.url, sha256: uploaded.sha256 }
        : { ...unwrapObject(installed), url: uploaded.url, sha256: uploaded.sha256 },
    );
  } catch (err) {
    dieTool(err.message);
  }
}

async function cmdPush(cfg, local, remote) {
  await requireApiKey(cfg);
  requireDeviceName(cfg);
  if (!local || !remote) usage("backstage push <local> <remote>");
  try {
    const uploaded = await encryptAndUpload(cfg, local);
    const fetched = await encryptedCall(cfg, "android.fetch", {requestId:crypto.randomUUID(),
      url: uploaded.url,
      remote,
      fileKey: uploaded.fileKey,
      sha256: uploaded.sha256,
    });
    printJson({ ...unwrapObject(fetched), url: uploaded.url, remote, sha256: uploaded.sha256 });
  } catch (err) {
    dieTool(err.message);
  }
}

async function cmdSelfUpdate(cfg, apk) {
  await requireApiKey(cfg);
  const deviceName = requireDeviceName(cfg);
  if (!apk) usage("backstage self-update <apk>");
  try {
    const before=await api(cfg,"/v1/devices");
    if(before.status!==200)throw new Error(before.body.error||"Cannot read device identity");
    const previous=before.body.devices?.find(d=>d.name===deviceName||(d.aliases||[]).includes(deviceName));
    if(!previous?.bootId)throw new Error("Device restart identity is unavailable; update the relay and connect the device first");
    if(previous.id!==linkFor(cfg,deviceName).deviceId)throw new Error("Selected device no longer matches its pairing; refusing update");
    const uploaded = await encryptAndUpload(cfg, apk);
    let updated;
    try {
      updated = await encryptedCall(cfg, "android.maintenance", {requestId:crypto.randomUUID(),source:{url:uploaded.url,fileKey:uploaded.fileKey,sha256:uploaded.sha256}});
    } catch (err) {
      // The new daemon can come up before the old one delivers its reply. A
      // confirmed restart below is the real result, so only an error that
      // proves nothing ran stops here.
      if (err.inputDelivered === false) throw err;
      updated = {outcome:"unknown",jobId:err.jobId,detail:"The reply was lost when the phone restarted; checking the restart instead."};
    }
    const deadline = Date.now() + 4 * 60 * 1000;
    while (Date.now() < deadline) {
      const { body } = await api(cfg, "/v1/devices", { timeoutMs: 8000 });
      const devices = body.devices || [];
      const device = devices.find((d) => d.id === previous.id);
      if (device?.online && device.bootId && device.bootId !== previous.bootId) {
        printJson({ ok: true, selfUpdate: updated, device, url: uploaded.url });
        return;
      }
      await new Promise((r) => setTimeout(r, 3000));
    }
    printJson({
      ok: false,
      error: `device "${deviceName}" restart was not confirmed within 4 minutes. Open Android Backstage on that phone and tap Start.`,
      selfUpdate: updated,
      url: uploaded.url,
    });
    process.exit(1);
  } catch (err) {
    dieTool(err.message);
  }
}

async function cmdMcpConfig(cfg) {
  await requireApiKey(cfg);
  const { status, body } = await api(cfg, "/v1/devices");
  if (status !== 200) dieTool(body.error || `HTTP ${status}`, body);
  const mcpServers = {};
  const devices = body.devices?.length ? body.devices : [{ name: cfg.device || "phone" }];
  for (const d of devices) {
    mcpServers[`android-${d.name}`] = {
      command: "node",
      args: ["cli/backstage.mjs", "mcp", "--device", d.name],
    };
  }
  printJson({ mcpServers });
}

async function cmdHealth(cfg) {
  try {
    const { status, body } = await api(cfg, "/health", { timeoutMs: 8000 });
    if (status !== 200) dieTool(body.error || `HTTP ${status}`, body);
    printJson(body);
  } catch (err) {
    dieTool(err.message);
  }
}

async function cmdMcp(cfg) {
  const deviceName = requireDeviceName(cfg);
  await requireApiKey(cfg);
  await runMcpStdio({
    deviceName,
    callTool: async (name, args) => {
      args = normalizeArguments(name, args);
      if ((name === "android.file" && args?.operation === "push" || name === "android.push") && args?.local && args?.remote) {
        const uploaded = await encryptAndUpload({ ...cfg, device: deviceName }, args.local);
        return encryptedCall(cfg, "android.file", {
          operation: "fetch",
          url: uploaded.url,
          fileKey: uploaded.fileKey,
          sha256: uploaded.sha256,
          remote: args.remote,
          requestId: args.requestId || crypto.randomUUID(),
        }, { device: deviceName });
      }
      if ((name === "android.file" && args?.operation === "pull" || name === "android.pull") && args?.offset == null) {
        if (args.saveTo) {
          const out = await downloadFile(
            (tool, a) => encryptedCall(cfg, tool, a, { device: deviceName }),
            { remote: args.path, local: args.saveTo, device: deviceName },
          );
          return { ...out, remote: args.path, localPath: out.path };
        }
        const first = await encryptedCall(cfg, "android.file", { operation: "pull", path: args.path, offset: 0, length: args.length || 1048576 }, { device: deviceName });
        if (first?.outcome !== "succeeded" || first.eof) return first;
        const dir = fs.mkdtempSync(path.join(os.tmpdir(), "backstage-pull-"));
        fs.chmodSync(dir, 0o700);
        const dest = path.join(dir, path.basename(args.path).replace(/[^A-Za-z0-9._-]/g, "_"));
        const out = await downloadFile(
          (tool, a) => encryptedCall(cfg, tool, a, { device: deviceName }),
          { remote: args.path, local: dest, device: deviceName },
        );
        return { ...out, remote: args.path, localPath: out.path };
      }
      return encryptedCall(cfg, name, args, { device: deviceName });
    },
  });
}

async function cmdMcpBridge(cfg, flags) {
  const linked = Object.keys(cfg.links || {});
  if (!cfg.device && linked.length === 1) cfg.device = linked[0];
  if (flags.printConfig) {
    const dev = cfg.device || "<device-name>";
    const entry = IN_REPO
      ? { command: "node", args: [path.resolve(fileURLToPath(import.meta.url)), "mcp-bridge", "--device", dev] }
      : { command: "npx", args: ["-y", "android-backstage", "mcp-bridge", "--device", dev] };
    printJson({
      mode: "e2ee-local-bridge",
      note: "All commands and replies are sealed end-to-end (AES-256-GCM) between this machine and your phone; the relay never sees plaintext.",
      mcpServers: { "android-backstage-e2ee": entry },
    });
    return;
  }
  if (!cfg.device || !cfg.links?.[cfg.device]) {
    const pairCmd = IN_REPO ? "node cli/backstage.mjs pair --name phone" : "npx -y android-backstage pair --name phone";
    const runCmd = IN_REPO ? "node cli/backstage.mjs mcp-bridge --device phone" : "npx -y android-backstage mcp-bridge --device phone";
    process.stderr.write(
      "Android Backstage E2EE bridge is not paired for a device yet.\n"
      + `Run once: ${pairCmd}\n`
      + `Then re-run: ${runCmd}\n`
    );
    process.exitCode = 2;
    return;
  }
  await cmdMcp(cfg);
}

export async function main(argv = process.argv.slice(2)) {
  const { flags, positionals } = parseArgv(argv);
  if (flags.help || positionals[0] === "help") usage();
  const cfg = resolveConfig(flags);
  const cmd = positionals[0];
  if (!cmd) usage();
  switch (cmd) {
    case "devices":
      await cmdDevices(cfg);
      break;
    case "pair":
      await withConfigLock(cfg.filePath, () => cmdPair(resolveConfig(flags), flags));
      break;
    case "rename":
      await withConfigLock(cfg.filePath, () => cmdRename(resolveConfig(flags), positionals[1], positionals[2]));
      break;
    case "forget":
      await withConfigLock(cfg.filePath, () => cmdForget(resolveConfig(flags), positionals[1]));
      break;
    case "job":
    case "cancel": {
      const id=positionals[1]; if(!/^[A-Za-z0-9_-]+$/.test(id||"")) usage("job id required");
      const r=await api(cfg,`/v1/jobs/${id}`,{method:cmd==="cancel"?"DELETE":"GET"});
      if(r.body.envelope) {
        const link=Object.values(cfg.links).find(x=>x.deviceId===r.body.envelope.from);
        if(link) {
          const decoded=JSON.parse(openEnvelope({key:resultKey(unb64url(link.linkKey)),envelope:r.body.envelope}));
          if(decoded.id!==id)throw new Error("Historical result id mismatch");
          delete r.body.envelope; r.body.recordedResponse=decoded;
        }
      }
      printJson(r.body); if(r.status>=400)process.exitCode=1; break;
    }
    case "compile": printJson(compileReceipt(JSON.parse(fs.readFileSync(positionals[1],"utf8")))); break;
    case "workflow":
    case "qa": {
      if(cmd==="qa" && !positionals[1]) usage("qa requires a package");
      const spec=cmd==="qa" ? {version:1,name:"App readiness",steps:[
        {tool:"android.task_begin",arguments:{requestId:crypto.randomUUID()},saveAs:"task"},
        {tool:"android.open",arguments:{sessionId:"${task.sessionId}",requestId:crypto.randomUUID(),package:positionals[1],verify:{kind:"window",package:positionals[1]}},assert:[{path:"outcome",equals:"succeeded"}]},
        {tool:"android.logs",arguments:{kind:"crash",package:positionals[1]},saveAs:"crashes"},
        {tool:"android.task_finish",arguments:{sessionId:"${task.sessionId}",requestId:crypto.randomUUID()}}
      ]} : JSON.parse(fs.readFileSync(positionals[1],"utf8"));
      const vars=positionals[2]?JSON.parse(positionals[2]):{};
      const result=await runWorkflow(spec,vars,(name,args)=>encryptedCall(cfg,name,args),
        {device:cfg.device,outputDir:path.join(path.dirname(cfg.filePath),"receipts")});
      printJson(result);if(result.status!=="passed")process.exitCode=1;break;
    }
    case "call":
      await cmdCall(cfg, positionals[1], positionals[2]);
      break;
    case "shell":
      await cmdShell(cfg, positionals.slice(1));
      break;
    case "install":
      await cmdInstall(cfg, positionals[1], flags.launch);
      break;
    case "push":
      await cmdPush(cfg, positionals[1], positionals[2]);
      break;
    case "pull": {
      await requireApiKey(cfg);const deviceName=requireDeviceName(cfg);
      if(!positionals[1]||!positionals[2])usage('backstage pull <remote> <local>');
      const device=cfg.links?.[deviceName]?.deviceId||deviceName;
      printJson(await downloadFile((name,args)=>encryptedCall(cfg,name,args),{remote:positionals[1],local:positionals[2],device}));
      break;
    }
    case "self-update":
      await cmdSelfUpdate(cfg, positionals[1]);
      break;
    case "mcp":
      await cmdMcp(cfg);
      break;
    case "mcp-bridge":
      await cmdMcpBridge(cfg, flags);
      break;
    case "mcp-config":
      await cmdMcpConfig(cfg);
      break;
    case "health":
      await cmdHealth(cfg);
      break;
    default:
      usage(`unknown command: ${cmd}`);
  }
}

void INSTRUCTIONS;

function isMain() {
  const entry = process.argv[1];
  if (!entry) return false;
  try {
    return pathToFileURL(fs.realpathSync(entry)).href === import.meta.url;
  } catch {
    return false;
  }
}

if (isMain()) {
  main().catch((err) => {
    if (err.exitCode !== 0) console.error(err?.message || err);
    process.exitCode = err.exitCode ?? 1;
  });
}

export { encryptedCall, resolveConfig, encryptAndUpload };
