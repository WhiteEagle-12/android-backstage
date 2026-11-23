// Shared by CLI tests that need a paired fake phone.
import assert from "node:assert/strict";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { httpJson, deviceHeaders, delay } from "../../relay/test/helpers.mjs";
import { Spake2, ALICE_NAME, BOB_NAME } from "../spake2.mjs";
import { linkKey, confirmA, confirmB, b64url, unb64url, timingSafeEqualBuf } from "../crypto.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CLI = path.resolve(__dirname, "../backstage.mjs");
const CODE_RE = /([ABCDEFGHJKMNPQRSTVWXYZ23456789]{4}-[ABCDEFGHJKMNPQRSTVWXYZ23456789]{6})/;

export function spawnCli(args, env, timeoutMs = 25_000) {
  let stdout = "";
  let stderr = "";
  const child = spawn(process.execPath, [CLI, ...args], {
    env: { ...process.env, BACKSTAGE_DEVICE: "", ...env },
  });
  child.stdout.on("data", (d) => {
    stdout += d;
  });
  child.stderr.on("data", (d) => {
    stderr += d;
  });
  const closed = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      child.kill("SIGKILL");
      reject(new Error(`cli timed out: ${args.join(" ")}\n${stdout}\n${stderr}`));
    }, timeoutMs);
    child.on("error", (err) => {
      clearTimeout(timer);
      reject(err);
    });
    child.on("close", (status) => {
      clearTimeout(timer);
      resolve({ status, stdout, stderr });
    });
  });
  return {
    child,
    getStdout: () => stdout,
    getStderr: () => stderr,
    closed,
  };
}

export async function runCli(args, env, timeoutMs = 20_000) {
  const spawned = spawnCli(args, env, timeoutMs);
  return spawned.closed;
}

export async function waitForCode(getStdout, timeoutMs = 8000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const m = CODE_RE.exec(getStdout());
    if (m) return m[1];
    await delay(20);
  }
  throw new Error(`no pairing code in stdout:\n${getStdout()}`);
}

export async function fakePairAsDevice(url, code) {
  const sessionId = code.slice(0, 4);
  const bob = new Spake2("bob", BOB_NAME, ALICE_NAME);
  const bobMsg = bob.generateMsg(Buffer.from(code, "utf8"));
  let claimed = null;
  for (let i = 0; i < 80; i += 1) {
    claimed = await httpJson(`${url}/v1/pair/session/${sessionId}/device`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: {
        msg: b64url(bobMsg),
        model: "Pixel",
        sdk: 34,
        agentVersion: "1.0.0",
        localToken: "local",
      },
    });
    if (claimed.status === 200) break;
    await delay(50);
  }
  if (claimed.status !== 200) {
    throw new Error(`device pair failed: ${claimed.status} ${JSON.stringify(claimed.body)}`);
  }
  const k = bob.processMsg(unb64url(claimed.body.agentMsg));
  const lk = linkKey(k);
  await httpJson(`${url}/v1/pair/session/${sessionId}/device-confirm`, {
    method: "PUT",
    headers: deviceHeaders(claimed.body.deviceToken),
    body: { confirm: b64url(confirmB(lk)) },
  });
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const got = await httpJson(`${url}/v1/pair/session/${sessionId}/confirm`, {
      headers: deviceHeaders(claimed.body.deviceToken),
    });
    if (got.body?.confirm) {
      assert.ok(timingSafeEqualBuf(unb64url(got.body.confirm), confirmA(lk)));
      break;
    }
    await delay(40);
  }
  return {
    deviceId: claimed.body.deviceId,
    deviceToken: claimed.body.deviceToken,
    agentId: claimed.body.agentId,
    linkKey: lk,
    resultCounter: String(Date.now()),
  };
}

