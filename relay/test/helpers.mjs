import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createRelay } from "../server.mjs";

export function tmpDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "backstage-relay-"));
}

export async function startRelay(opts = {}) {
  const dataDir = opts.dataDir || tmpDir();
  const relay = await createRelay({
    port: 0,
    host: "127.0.0.1",
    dataDir,
    pollMs: opts.pollMs ?? 400,
    offlineMs: opts.offlineMs ?? 45_000,
    pairPollMs: opts.pairPollMs ?? 400,
    log: opts.log ?? { log() {}, error() {} },
    ...opts,
  });
  const apiKey =
    relay.firstApiKey || fs.readFileSync(path.join(dataDir, "first-api-key.txt"), "utf8").trim();
  return { relay, dataDir, apiKey, url: relay.url };
}

export async function withRelay(opts, fn) {
  if (typeof opts === "function") {
    fn = opts;
    opts = {};
  }
  const ctx = await startRelay(opts);
  try {
    return await fn(ctx);
  } finally {
    await ctx.relay.close();
    fs.rmSync(ctx.dataDir, { recursive: true, force: true });
  }
}

export function apiHeaders(apiKey, extra = {}) {
  return { authorization: `Bearer ${apiKey}`, "content-type": "application/json", ...extra };
}

export function deviceHeaders(deviceToken, extra = {}) {
  return { authorization: `Bearer ${deviceToken}`, "content-type": "application/json", ...extra };
}

export async function httpJson(url, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  let body = opts.body;
  if (body != null && typeof body !== "string" && !Buffer.isBuffer(body)) {
    if (!headers["content-type"] && !headers["Content-Type"]) {
      headers["content-type"] = "application/json";
    }
    body = JSON.stringify(body);
  }
  const res = await fetch(url, {
    method: opts.method || "GET",
    headers,
    body,
    signal: AbortSignal.timeout(opts.timeoutMs || 10_000),
  });
  const text = await res.text();
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    parsed = undefined;
  }
  return { status: res.status, headers: res.headers, body: parsed, text };
}

export function fakeEnvelope(from = "agt_test", n = "1", ct = "dGVzdA") {
  return { v: "1", from, n: String(n), ct };
}

export async function pairSession(url, apiKey, { name = "phone", agentId = "agt_0123456789abcdef" } = {}) {
  const created = await httpJson(`${url}/v1/pair/session`, {
    method: "POST",
    headers: apiHeaders(apiKey),
    body: { name },
  });
  const sessionId = created.body.sessionId;
  await httpJson(`${url}/v1/pair/session/${sessionId}/agent`, {
    method: "PUT",
    headers: apiHeaders(apiKey),
    body: { agentId, msg: "YWxpY2Vtc2c" },
  });
  const devicePost = await httpJson(`${url}/v1/pair/session/${sessionId}/device`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: {
      msg: "Ym9ibXNn",
      model: "Pixel",
      sdk: 34,
      agentVersion: "1.0.0",
      localToken: "local-test-token",
    },
  });
  await httpJson(`${url}/v1/pair/session/${sessionId}/device-confirm`, {
    method: "PUT",
    headers: deviceHeaders(devicePost.body.deviceToken),
    body: { confirm: "Y29uZmlybUI" },
  });
  await httpJson(`${url}/v1/pair/session/${sessionId}/confirm`, {
    method: "PUT",
    headers: apiHeaders(apiKey),
    body: { confirm: "Y29uZmlybUE" },
  });
  return {
    session: created.body,
    claim: devicePost.body,
    localToken: "local-test-token",
    agentId,
  };
}

export async function hello(url, deviceToken, body = { model: "Pixel", sdk: 34, agentVersion: "1.0.0" }) {
  return httpJson(`${url}/v1/device/hello`, {
    method: "POST",
    headers: deviceHeaders(deviceToken),
    body,
  });
}

export function delay(ms) {
  return new Promise((r) => setTimeout(r, ms));
}
