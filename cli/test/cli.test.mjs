import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { withRelay, httpJson, deviceHeaders, delay } from "../../relay/test/helpers.mjs";
import { Spake2, ALICE_NAME, BOB_NAME } from "../spake2.mjs";
import {
  linkKey,
  callKey,
  resultKey,
  confirmA,
  confirmB,
  sealEnvelope,
  openEnvelope,
  nextCounter,
  b64url,
  unb64url,
  timingSafeEqualBuf,
  decryptBytes,
} from "../crypto.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CLI = path.resolve(__dirname, "../backstage.mjs");
const CODE_RE = /([ABCDEFGHJKMNPQRSTVWXYZ23456789]{4}-[ABCDEFGHJKMNPQRSTVWXYZ23456789]{6})/;

function spawnCli(args, env, timeoutMs = 25_000) {
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

async function runCli(args, env, timeoutMs = 20_000) {
  const spawned = spawnCli(args, env, timeoutMs);
  return spawned.closed;
}

async function waitForCode(getStdout, timeoutMs = 8000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const m = CODE_RE.exec(getStdout());
    if (m) return m[1];
    await delay(20);
  }
  throw new Error(`no pairing code in stdout:\n${getStdout()}`);
}

async function fakePairAsDevice(url, code) {
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

async function fakeDeviceLoop(url, device, jobs, ac) {
  await fetch(`${url}/v1/device/hello`, {
    method: "POST",
    headers: { authorization: `Bearer ${device.deviceToken}`, "content-type": "application/json" },
    body: JSON.stringify({ model: "Pixel", sdk: 34, agentVersion: "1.0.0" }),
  });
  const ck = callKey(device.linkKey);
  const rk = resultKey(device.linkKey);
  while (!ac.signal.aborted) {
    try {
      const res = await fetch(`${url}/v1/device/next`, {
        method: "POST",
        headers: { authorization: `Bearer ${device.deviceToken}`, "content-type": "application/json" },
        body: "{}",
        signal: AbortSignal.timeout(1500),
      });
      const job = await res.json();
      if (!job?.id || job.idle) continue;
      const opened = JSON.parse(openEnvelope({ key: ck, envelope: job.envelope }).toString("utf8"));
      jobs.push({ id: job.id, ...opened });
      let result = {
        ok: true,
        stdout: "uid=2000(shell)",
        name: opened.name,
        arguments: opened.arguments,
      };
      if (opened.name === "android.install") {
        const fileRes = await fetch(opened.arguments.source.url);
        const blob = Buffer.from(await fileRes.arrayBuffer());
        const dec = decryptBytes(blob, unb64url(opened.arguments.source.fileKey));
        result = {
          ok: true,
          installed: true,
          sha256: dec.sha256,
          expectedSha: opened.arguments.source.sha256,
          plaintext: dec.plaintext.toString("utf8"),
          match: dec.sha256 === opened.arguments.source.sha256,
        };
      }
      const n = device.resultCounter;
      device.resultCounter = nextCounter(n);
      const envelope = sealEnvelope({
        key: rk,
        from: device.deviceId,
        n,
        plaintext: JSON.stringify({ id: opened.id, result }),
      });
      await fetch(`${url}/v1/device/result`, {
        method: "POST",
        headers: { authorization: `Bearer ${device.deviceToken}`, "content-type": "application/json" },
        body: JSON.stringify({ id: job.id, envelope }),
      });
    } catch {
      if (ac.signal.aborted) break;
    }
  }
}

function readNdjson(stream) {
  let buf = "";
  const lines = [];
  const waiters = [];
  stream.on("data", (chunk) => {
    buf += chunk;
    let idx;
    while ((idx = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, idx).trim();
      buf = buf.slice(idx + 1);
      if (!line) continue;
      let parsed;
      try {
        parsed = JSON.parse(line);
      } catch {
        continue;
      }
      if (waiters.length) waiters.shift()(parsed);
      else lines.push(parsed);
    }
  });
  return function nextLine(timeoutMs = 8000) {
    if (lines.length) return Promise.resolve(lines.shift());
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("mcp stdout timeout")), timeoutMs);
      waiters.push((msg) => {
        clearTimeout(timer);
        resolve(msg);
      });
    });
  };
}

test("cli health, pair, devices, call, install, mcp", async () => {
  await withRelay({ pollMs: 400, pairPollMs: 800 }, async ({ url, apiKey }) => {
    const cfgPath = path.join(os.tmpdir(), `backstage-cli-${process.pid}.json`);
    const env = {
      BACKSTAGE_RELAY_URL: url,
      BACKSTAGE_API_KEY: apiKey,
      BACKSTAGE_DEVICE: "phone",
      BACKSTAGE_CONFIG: cfgPath,
    };

    const health = await runCli(["health"], env);
    assert.equal(health.status, 0, health.stderr);
    const healthBody = JSON.parse(health.stdout);
    assert.equal(healthBody.ok, true);
    assert.equal(healthBody.service, "backstage-relay");

    const pairProc = spawnCli(["pair", "--name", "phone"], env, 20_000);
    const code = await waitForCode(pairProc.getStdout);
    const device = await fakePairAsDevice(url, code);
    const pair = await pairProc.closed;
    assert.equal(pair.status, 0, pair.stderr + pair.stdout);
    assert.match(pair.stdout, CODE_RE);
    const saved = JSON.parse(fs.readFileSync(cfgPath, "utf8"));
    assert.ok(saved.agentId.startsWith("agt_"));
    assert.ok(saved.links.phone.linkKey);
    assert.equal(saved.links.phone.deviceId, device.deviceId);

    const devices = await runCli(["devices"], env);
    assert.equal(devices.status, 0, devices.stderr);
    const listed = JSON.parse(devices.stdout);
    assert.equal(listed.devices.length, 1);
    assert.equal(listed.devices[0].name, "phone");
    assert.deepEqual(listed.devices[0].agents, [saved.agentId]);

    const jobs = [];
    const ac = new AbortController();
    const loop = fakeDeviceLoop(url, device, jobs, ac);
    try {
      const call = await runCli(
        ["--device", "phone", "call", "android.shell", JSON.stringify({ command: "id" })],
        env,
      );
      assert.equal(call.status, 0, call.stderr + call.stdout);
      const callBody = JSON.parse(call.stdout);
      assert.equal(callBody.stdout, "uid=2000(shell)");
      assert.equal(jobs.at(-1).name, "android.shell");

      const apk = path.join(os.tmpdir(), `backstage-cli-${process.pid}.apk`);
      fs.writeFileSync(apk, "dummy-apk");
      try {
        const install = await runCli(["--device", "phone", "install", apk], env);
        assert.equal(install.status, 0, install.stderr + install.stdout);
        const installBody = JSON.parse(install.stdout);
        assert.ok(String(installBody.url).includes("/v1/files/"), install.stdout);
        const installJob = jobs.find((j) => j.name === "android.install");
        assert.ok(installJob, "device should receive android.install");
        assert.equal(installJob.arguments.source.url, installBody.url);
        assert.ok(installJob.arguments.source.fileKey);
        assert.ok(installJob.arguments.source.sha256);
        assert.equal(installBody.sha256, installJob.arguments.source.sha256);
        assert.equal(installBody.ok, true);
        assert.equal(installBody.match, true);
        assert.equal(installBody.plaintext, "dummy-apk");
      } finally {
        fs.unlinkSync(apk);
      }

      const mcp = spawn(process.execPath, [CLI, "mcp", "--device", "phone"], {
        env: { ...process.env, BACKSTAGE_DEVICE: "", ...env },
      });
      const nextMcp = readNdjson(mcp.stdout);
      mcp.stdin.write(
        `${JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2024-11-05" } })}\n`,
      );
      const init = await nextMcp();
      assert.equal(init.result.serverInfo.name, "backstage-phone");
      assert.match(init.result.instructions, /locked to device "phone"/);
      mcp.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id: 2, method: "tools/list" })}\n`);
      const listedTools = await nextMcp();
      const names = listedTools.result.tools.map((t) => t.name);
      assert.ok(names.includes("android.shell"));
      assert.ok(!names.includes("android.adb_wifi"));
      mcp.stdin.write(
        `${JSON.stringify({
          jsonrpc: "2.0",
          id: 3,
          method: "tools/call",
          params: { name: "android.shell", arguments: { command: "id" } },
        })}\n`,
      );
      const called = await nextMcp(15_000);
      assert.equal(called.result.isError, false);
      assert.equal(called.result.structuredContent.stdout, "uid=2000(shell)");
      mcp.kill("SIGTERM");
    } finally {
      ac.abort();
      await Promise.race([loop, new Promise((r) => setTimeout(r, 500))]);
      try {
        fs.unlinkSync(cfgPath);
      } catch {
        /* ignore */
      }
    }
  });
});
