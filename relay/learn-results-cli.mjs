#!/usr/bin/env node
/** Small tutor-side helper for creating and reading TeachMe activity results. */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const stateDir = process.env.TEACHME_STATE_DIR || path.join(os.homedir(), ".local", "share", "teachme");
const registryPath = path.join(stateDir, "pending-results.json");

function readRegistry() {
  try {
    const data = JSON.parse(fs.readFileSync(registryPath, "utf8"));
    return Array.isArray(data) ? data : [];
  } catch { return []; }
}
function saveRegistry(entries) {
  fs.mkdirSync(stateDir, { recursive: true, mode: 0o700 });
  const tmp = `${registryPath}.${process.pid}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(entries, null, 2) + "\n", { mode: 0o600 });
  fs.renameSync(tmp, registryPath);
}
function args(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i].startsWith("--")) out[argv[i].slice(2)] = argv[++i];
    else out._.push(argv[i]);
  }
  return out;
}
async function request(url, options = {}) {
  const response = await fetch(url, { ...options, signal: AbortSignal.timeout(15_000) });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(`${response.status}: ${body.error || "request failed"}`);
  return body;
}
function required(value, name) { if (!value) throw new Error(`missing ${name}`); return value; }

const parsed = args(process.argv.slice(2));
const command = parsed._[0] || "pending";
if (command === "create") {
  const relay = required(parsed.relay || process.env.RELAY_URL, "--relay or RELAY_URL").replace(/\/$/, "");
  const admin = required(parsed["admin-token"] || process.env.RELAY_ADMIN_TOKEN, "--admin-token or RELAY_ADMIN_TOKEN");
  const created = await request(`${relay}/v1/learn/activities`, {
    method: "POST", headers: { authorization: `Bearer ${admin}`, "content-type": "application/json" },
    body: JSON.stringify({ ttlMs: parsed["ttl-ms"] ? Number(parsed["ttl-ms"]) : undefined, maxAttempts: parsed["max-attempts"] ? Number(parsed["max-attempts"]) : undefined }),
  });
  const entries = readRegistry().filter((entry) => entry.activityId !== created.activityId);
  entries.push({ activityId: created.activityId, relay, readToken: created.readToken, expiresAt: created.expiresAt, next: 0 });
  saveRegistry(entries);
  process.stdout.write(JSON.stringify({ ...created, resultUrl: `${relay}/v1/learn/activities/${created.activityId}/results` }) + "\n");
} else if (command === "read") {
  const entries = readRegistry();
  const selected = parsed._[1] ? entries.filter((candidate) => candidate.activityId === parsed._[1]) : entries;
  if (!selected.length) throw new Error(parsed._[1] ? "unknown learning activity" : "no pending learning activities");
  const reports = [];
  for (const entry of selected) {
    const body = await request(`${entry.relay}/v1/learn/activities/${entry.activityId}/results?after=${entry.next || 0}`, { headers: { authorization: `Bearer ${entry.readToken}` } });
    entry.next = body.next;
    reports.push(body);
  }
  saveRegistry(entries);
  process.stdout.write(JSON.stringify(parsed._[1] ? reports[0] : { activities: reports }) + "\n");
} else if (command === "pending") {
  const now = Date.now();
  const entries = readRegistry().filter((entry) => Date.parse(entry.expiresAt) > now);
  saveRegistry(entries);
  process.stdout.write(JSON.stringify(entries.map(({ activityId, relay, expiresAt, next }) => ({ activityId, relay, expiresAt, next }))) + "\n");
} else {
  throw new Error("usage: create | read [activityId] | pending");
}
