import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { withRelay, delay } from "../../relay/test/helpers.mjs";
import { callKey, resultKey, sealEnvelope, openEnvelope, nextCounter } from "../crypto.mjs";
import { encryptedCall } from "../backstage.mjs";
import { spawnCli, waitForCode, fakePairAsDevice } from "./phone-fixture.mjs";

/**
 * One fake phone reachable two ways, with a request journal like the real one:
 * a call ID that already ran returns its saved result instead of running again.
 */
function fakePhone(device) {
  const ck = callKey(device.linkKey), rk = resultKey(device.linkKey);
  const runs = [], journal = new Map();
  const seal = (id, result) => {
    const n = device.resultCounter;
    device.resultCounter = nextCounter(n);
    return sealEnvelope({ key: rk, from: device.deviceId, n, plaintext: JSON.stringify({ id, result }) });
  };
  const handle = (envelope, route) => {
    const call = JSON.parse(openEnvelope({ key: ck, envelope }).toString("utf8"));
    if (!journal.has(call.id)) {
      runs.push({ id: call.id, route, name: call.name });
      journal.set(call.id, { ok: true, stdout: `ran ${call.arguments.command}` });
    }
    return seal(call.id, journal.get(call.id));
  };
  return { runs, handle };
}

async function relayLoop(url, device, phone, ac) {
  await fetch(`${url}/v1/device/hello`, { method: "POST", headers: { authorization: `Bearer ${device.deviceToken}`, "content-type": "application/json" },
    body: JSON.stringify({ model: "Pixel", sdk: 34, agentVersion: "2.0.0-dev.26", lanAddresses: ["100.64.0.9"] }) });
  while (!ac.signal.aborted) {
    try {
      const res = await fetch(`${url}/v1/device/next`, { method: "POST", headers: { authorization: `Bearer ${device.deviceToken}`, "content-type": "application/json" },
        body: "{}", signal: AbortSignal.timeout(1500) });
      const job = await res.json();
      if (!job?.id || job.idle) continue;
      const envelope = phone.handle(job.envelope, "relay");
      await fetch(`${url}/v1/device/result`, { method: "POST", headers: { authorization: `Bearer ${device.deviceToken}`, "content-type": "application/json" },
        body: JSON.stringify({ id: job.id, envelope }) });
    } catch {
      if (ac.signal.aborted) break;
    }
  }
}

/** mode: ok | lose-reply (runs, then drops the connection) | refuse (another phone at this address) */
function directServer(phone, mode) {
  const server = http.createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      if (mode.value === "refuse") return res.end(JSON.stringify({ error: { message: "unauthorized: unknown agent" } }));
      const envelope = phone.handle(JSON.parse(body).envelope, "direct");
      if (mode.value === "lose-reply") return req.socket.destroy();
      res.setHeader("content-type", "application/json");
      res.end(JSON.stringify({ envelope }));
    });
  });
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve(server)));
}

test("direct route runs calls once, falls back to the relay, and never repeats a delivered call", async () => {
  await withRelay({ pollMs: 400, pairPollMs: 800 }, async ({ url, apiKey }) => {
    const cfgPath = path.join(os.tmpdir(), `backstage-direct-${process.pid}.json`);
    const env = { BACKSTAGE_RELAY_URL: url, BACKSTAGE_API_KEY: apiKey, BACKSTAGE_DEVICE: "phone", BACKSTAGE_CONFIG: cfgPath };
    const pairing = spawnCli(["pair", "--name", "phone"], env, 20_000);
    const device = await fakePairAsDevice(url, await waitForCode(pairing.getStdout));
    assert.equal((await pairing.closed).status, 0);
    const phone = fakePhone(device);
    const ac = new AbortController();
    const loop = relayLoop(url, device, phone, ac);
    const mode = { value: "ok" };
    const server = await directServer(phone, mode);
    const directUrl = `http://127.0.0.1:${server.address().port}`;
    const saved = () => { const file = JSON.parse(fs.readFileSync(cfgPath, "utf8")); return { ...file, file, filePath: cfgPath, directUrl: null }; };
    const routes = [];
    const call = (command, options = {}) => encryptedCall(saved(), "android.shell", { command }, { device: "phone", onRoute: (r, why) => routes.push([r, why]), ...options });
    try {
      await delay(200);
      const direct = await call("a", { directUrl });
      assert.equal(direct.stdout, "ran a");
      assert.deepEqual(phone.runs.map((r) => r.route), ["direct"]);
      assert.equal(routes.at(-1)[0], "direct");

      // Nothing listens: the phone was never reached, so the relay runs it.
      const closed = await call("b", { directUrl: "http://127.0.0.1:9" });
      assert.equal(closed.stdout, "ran b");
      assert.deepEqual(phone.runs.map((r) => r.route), ["direct", "relay"]);
      assert.match(routes.at(-1)[1], /direct failed/);

      // The phone ran it but the reply was lost: the relay resend returns the saved result.
      mode.value = "lose-reply";
      const lost = await call("c", { directUrl });
      assert.equal(lost.stdout, "ran c");
      assert.equal(phone.runs.filter((r) => r.id && r.name === "android.shell").length, 3, "c ran exactly once");
      assert.equal(phone.runs.at(-1).route, "direct");

      // Another phone answered at that address and refused: nothing ran there; use the relay.
      mode.value = "refuse";
      const refused = await call("d", { directUrl });
      assert.equal(refused.stdout, "ran d");
      assert.equal(phone.runs.at(-1).route, "relay");
      assert.match(routes.at(-1)[1], /direct refused: unauthorized/);
      assert.equal(phone.runs.length, 4);
    } finally {
      ac.abort();
      server.close();
      await loop;
      fs.rmSync(cfgPath, { force: true });
    }
  });
});
