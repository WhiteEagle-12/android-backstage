import assert from "node:assert/strict";
import fs from "node:fs";
import { describe, it } from "node:test";
import {
  withRelay,
  startRelay,
  httpJson,
  apiHeaders,
  deviceHeaders,
  pairSession,
  hello,
  fakeEnvelope,
  delay,
} from "./helpers.mjs";

describe("bootstrap", () => {
  it("mints a first API key, writes it mode 0600, and serves health", async () => {
    await withRelay(async ({ relay, dataDir, apiKey, url }) => {
      assert.ok(apiKey.startsWith("bsk_"));
      assert.equal(relay.firstApiKey, apiKey);
      const keyPath = `${dataDir}/first-api-key.txt`;
      const saved = fs.readFileSync(keyPath, "utf8").trim();
      assert.equal(saved, apiKey);
      const mode = fs.statSync(keyPath).mode & 0o777;
      assert.equal(mode, 0o600);
      const health = await httpJson(`${url}/health`);
      assert.equal(health.status, 200);
      assert.equal(health.body.ok, true);
      assert.equal(health.body.service, "backstage-relay");
      assert.equal(health.body.version, "1.1.0-alpha1");
      assert.equal(health.body.devicesOnline, 0);
      const me = await httpJson(`${url}/v1/me`, { headers: apiHeaders(apiKey) });
      assert.equal(me.body.accountId, "default");
      const unauth = await httpJson(`${url}/v1/me`);
      assert.equal(unauth.status, 401);
      const mcpGone = await httpJson(`${url}/mcp`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: { jsonrpc: "2.0", id: 1, method: "ping" },
      });
      assert.equal(mcpGone.status, 404);
      const budgetGone = await httpJson(`${url}/tool-budget?name=android.shell`);
      assert.equal(budgetGone.status, 404);
      const oldPair = await httpJson(`${url}/v1/pair`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: {},
      });
      assert.equal(oldPair.status, 404);
    });
  });

  it("does not mint a second key on restart", async () => {
    const ctx = await startRelay();
    const first = ctx.apiKey;
    await ctx.relay.close();
    const again = await startRelay({ dataDir: ctx.dataDir });
    try {
      assert.equal(again.relay.firstApiKey, null);
      const me = await httpJson(`${again.url}/v1/me`, { headers: apiHeaders(first) });
      assert.equal(me.status, 200);
    } finally {
      await again.relay.close();
      fs.rmSync(ctx.dataDir, { recursive: true, force: true });
    }
  });
});

describe("pairing session", () => {
  it("runs the full agent/device SPAKE2 byte exchange and lists agents", async () => {
    await withRelay(async ({ url, apiKey }) => {
      const created = await httpJson(`${url}/v1/pair/session`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: { name: "phone" },
      });
      assert.equal(created.status, 200);
      assert.match(created.body.sessionId, /^[ABCDEFGHJKMNPQRSTVWXYZ23456789]{4}$/);
      assert.equal(created.body.name, "phone");
      const id = created.body.sessionId;

      const agent = await httpJson(`${url}/v1/pair/session/${id}/agent`, {
        method: "PUT",
        headers: apiHeaders(apiKey),
        body: { agentId: "agt_deadbeefcafebabe", msg: "YWxpY2U" },
      });
      assert.equal(agent.status, 200);

      const devicePost = await httpJson(`${url}/v1/pair/session/${id}/device`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: { msg: "Ym9i", model: "Pixel", sdk: 34, agentVersion: "1.0.0" },
      });
      assert.equal(devicePost.status, 200);
      assert.ok(devicePost.body.deviceToken.startsWith("bsd_"));
      assert.equal(devicePost.body.agentId, "agt_deadbeefcafebabe");
      assert.equal(devicePost.body.agentMsg, "YWxpY2U");
      assert.equal(devicePost.body.name, "phone");

      const twice = await httpJson(`${url}/v1/pair/session/${id}/device`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: { msg: "Ym9i2", model: "Pixel", sdk: 34, agentVersion: "1.0.0" },
      });
      assert.equal(twice.status, 400);

      await httpJson(`${url}/v1/pair/session/${id}/device-confirm`, {
        method: "PUT",
        headers: deviceHeaders(devicePost.body.deviceToken),
        body: { confirm: "Y29uZkI" },
      });

      const got = await httpJson(`${url}/v1/pair/session/${id}/device`, {
        headers: apiHeaders(apiKey),
      });
      assert.equal(got.body.deviceId, devicePost.body.deviceId);
      assert.equal(got.body.msg, "Ym9i");
      assert.equal(got.body.confirm, "Y29uZkI");

      await httpJson(`${url}/v1/pair/session/${id}/confirm`, {
        method: "PUT",
        headers: apiHeaders(apiKey),
        body: { confirm: "Y29uZkE" },
      });
      const confirm = await httpJson(`${url}/v1/pair/session/${id}/confirm`, {
        headers: deviceHeaders(devicePost.body.deviceToken),
      });
      assert.equal(confirm.body.confirm, "Y29uZkE");

      const listed = await httpJson(`${url}/v1/devices`, { headers: apiHeaders(apiKey) });
      assert.equal(listed.body.devices.length, 1);
      assert.deepEqual(listed.body.devices[0].agents, ["agt_deadbeefcafebabe"]);
    });
  });

  it("DELETE session revokes the device it created", async () => {
    await withRelay(async ({ url, apiKey }) => {
      const { session, claim } = await pairSession(url, apiKey, { name: "phone" });
      const del = await httpJson(`${url}/v1/pair/session/${session.sessionId}`, {
        method: "DELETE",
        headers: apiHeaders(apiKey),
      });
      assert.equal(del.status, 200);
      const next = await httpJson(`${url}/v1/device/next`, {
        method: "POST",
        headers: deviceHeaders(claim.deviceToken),
        body: {},
      });
      assert.equal(next.status, 401);
    });
  });
});

describe("call round trip", () => {
  it("forwards opaque envelopes call → next → result", async () => {
    await withRelay(async ({ url, apiKey }) => {
      const { claim } = await pairSession(url, apiKey, { name: "phone" });
      await hello(url, claim.deviceToken);
      const callEnv = fakeEnvelope("agt_0123456789abcdef", "1700000000000", "Y2FsbA");
      const callP = httpJson(`${url}/v1/call`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: { device: "phone", envelope: callEnv, timeoutMs: 4000 },
      });
      const next = await httpJson(`${url}/v1/device/next`, {
        method: "POST",
        headers: deviceHeaders(claim.deviceToken),
        body: {},
      });
      assert.equal(next.status, 200);
      assert.ok(next.body.id);
      assert.deepEqual(next.body.envelope, callEnv);
      const resultEnv = fakeEnvelope(claim.deviceId, "1700000000001", "cmVzdWx0");
      await httpJson(`${url}/v1/device/result`, {
        method: "POST",
        headers: deviceHeaders(claim.deviceToken),
        body: { id: next.body.id, envelope: resultEnv },
      });
      const call = await callP;
      assert.equal(call.status, 200);
      assert.deepEqual(call.body.envelope, resultEnv);
    });
  });

  it("surfaces unencrypted device refusals as 403 { error }", async () => {
    await withRelay(async ({ url, apiKey }) => {
      const { claim } = await pairSession(url, apiKey);
      await hello(url, claim.deviceToken);
      const callP = httpJson(`${url}/v1/call`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: { device: "phone", envelope: fakeEnvelope(), timeoutMs: 4000 },
      });
      const next = await httpJson(`${url}/v1/device/next`, {
        method: "POST",
        headers: deviceHeaders(claim.deviceToken),
        body: {},
      });
      await httpJson(`${url}/v1/device/result`, {
        method: "POST",
        headers: deviceHeaders(claim.deviceToken),
        body: { id: next.body.id, error: { message: "unauthorized" } },
      });
      const call = await callP;
      assert.equal(call.status, 403);
      assert.equal(call.body.error, "unauthorized");
    });
  });

  it("returns 409 when the device is offline", async () => {
    await withRelay({ offlineMs: 50, pollMs: 200 }, async ({ url, apiKey }) => {
      const { claim } = await pairSession(url, apiKey, { name: "pixel" });
      const call = await httpJson(`${url}/v1/call`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: { device: "pixel", envelope: fakeEnvelope() },
      });
      assert.equal(call.status, 409);
      assert.equal(
        call.body.error,
        'device "pixel" is offline (last seen never). Open Android Backstage on that phone and tap Start. Not using another device.',
      );
      await hello(url, claim.deviceToken);
      await delay(80);
      const again = await httpJson(`${url}/v1/call`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: { device: "pixel", envelope: fakeEnvelope() },
      });
      assert.equal(again.status, 409);
      assert.match(
        again.body.error,
        /device "pixel" is offline \(last seen .+\)\. Open Android Backstage on that phone and tap Start\. Not using another device\./,
      );
    });
  });

  it("returns 504 when the result never arrives", async () => {
    await withRelay({ pollMs: 200 }, async ({ url, apiKey }) => {
      const { claim } = await pairSession(url, apiKey, { name: "phone" });
      await hello(url, claim.deviceToken);
      const call = await httpJson(`${url}/v1/call`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: { device: "phone", envelope: fakeEnvelope(), timeoutMs: 1000 },
        timeoutMs: 5000,
      });
      assert.equal(call.status, 504);
      assert.ok(call.body.error);
    });
  });

  it("rejects invalid envelopes with 400", async () => {
    await withRelay(async ({ url, apiKey }) => {
      const { claim } = await pairSession(url, apiKey, { name: "phone" });
      await hello(url, claim.deviceToken);
      const missing = await httpJson(`${url}/v1/call`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: { device: "phone", envelope: { v: "1", from: "a" } },
      });
      assert.equal(missing.status, 400);
      const badV = await httpJson(`${url}/v1/call`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: { device: "phone", envelope: { v: "2", from: "a", n: "1", ct: "x" } },
      });
      assert.equal(badV.status, 400);
      // The daemon emits v as the JSON number 1; the relay forwards it rather than 400.
      // The device never answers, so this resolves as a timeout, not a validation error.
      const numV = await httpJson(`${url}/v1/call`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: { device: "phone", envelope: { v: 1, from: "a", n: "1", ct: "x" }, timeoutMs: 1000 },
      });
      assert.notEqual(numV.status, 400);
      const unknown = await httpJson(`${url}/v1/call`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: { device: "nope", envelope: fakeEnvelope() },
      });
      assert.equal(unknown.status, 400);
      assert.match(unknown.body.error, /unknown device "nope"/);
      assert.match(unknown.body.error, /Not using another device/);
    });
  });
});

describe("concurrent poll", () => {
  it("returns duplicate idle on a second outstanding poll", async () => {
    await withRelay({ pollMs: 800 }, async ({ url, apiKey }) => {
      const { claim } = await pairSession(url, apiKey);
      await hello(url, claim.deviceToken);
      const firstP = httpJson(`${url}/v1/device/next`, {
        method: "POST",
        headers: deviceHeaders(claim.deviceToken),
        body: {},
        timeoutMs: 2000,
      });
      await delay(40);
      const second = await httpJson(`${url}/v1/device/next`, {
        method: "POST",
        headers: deviceHeaders(claim.deviceToken),
        body: {},
        timeoutMs: 1000,
      });
      assert.equal(second.status, 200);
      assert.deepEqual(second.body, { idle: true, duplicate: true });
      const first = await firstP;
      assert.equal(first.body.idle, true);
      assert.equal(first.body.duplicate, undefined);
    });
  });
});

describe("dropped poll", () => {
  it("frees the lane at once when the phone's connection closes", async () => {
    await withRelay({ pollMs: 5000 }, async ({ url, apiKey }) => {
      const { claim } = await pairSession(url, apiKey);
      await hello(url, claim.deviceToken);
      await httpJson(`${url}/v1/device/next`, {
        method: "POST", headers: deviceHeaders(claim.deviceToken), body: {}, timeoutMs: 150,
      }).catch(() => {});
      await delay(50);
      const again = await httpJson(`${url}/v1/device/next`, {
        method: "POST", headers: deviceHeaders(claim.deviceToken), body: {}, timeoutMs: 300,
      }).catch(error => ({ error }));
      assert.ok(again.error, `a fresh poll waits for work instead of being refused: ${JSON.stringify(again.body)}`);
    });
  });
});

describe("PATCH + DELETE", () => {
  it("renames and resolves aliases on /v1/call", async () => {
    await withRelay(async ({ url, apiKey }) => {
      const { claim } = await pairSession(url, apiKey, { name: "phone" });
      await hello(url, claim.deviceToken);
      const patched = await httpJson(`${url}/v1/devices/phone`, {
        method: "PATCH",
        headers: apiHeaders(apiKey),
        body: { name: "pixel", aliases: ["phone", "nubia"] },
      });
      assert.equal(patched.status, 200);
      assert.equal(patched.body.name, "pixel");
      assert.deepEqual(patched.body.aliases, ["phone", "nubia"]);
      const callP = httpJson(`${url}/v1/call`, {
        method: "POST",
        headers: apiHeaders(apiKey),
        body: { device: "nubia", envelope: fakeEnvelope(), timeoutMs: 4000 },
      });
      const next = await httpJson(`${url}/v1/device/next`, {
        method: "POST",
        headers: deviceHeaders(claim.deviceToken),
        body: {},
      });
      await httpJson(`${url}/v1/device/result`, {
        method: "POST",
        headers: deviceHeaders(claim.deviceToken),
        body: { id: next.body.id, envelope: fakeEnvelope(claim.deviceId, "2", "eA") },
      });
      const call = await callP;
      assert.equal(call.status, 200);
    });
  });

  it("DELETE revokes the device token (401 on next poll)", async () => {
    await withRelay({ pollMs: 200 }, async ({ url, apiKey }) => {
      const { claim } = await pairSession(url, apiKey, { name: "phone" });
      await hello(url, claim.deviceToken);
      const del = await httpJson(`${url}/v1/devices/phone`, {
        method: "DELETE",
        headers: apiHeaders(apiKey),
      });
      assert.equal(del.status, 200);
      assert.equal(del.body.ok, true);
      const next = await httpJson(`${url}/v1/device/next`, {
        method: "POST",
        headers: deviceHeaders(claim.deviceToken),
        body: {},
      });
      assert.equal(next.status, 401);
    });
  });
});

describe("files", () => {
  it("uploads, downloads once, then 404s; TTL expires", async () => {
    await withRelay(async ({ url, apiKey }) => {
      const up = await fetch(`${url}/v1/files`, {
        method: "POST",
        headers: {
          authorization: `Bearer ${apiKey}`,
          "x-file-name": "hello.bin",
        },
        body: "hello-bytes",
      });
      assert.equal(up.status, 200);
      const meta = await up.json();
      assert.ok(meta.id);
      assert.ok(String(meta.url).includes(`/v1/files/${meta.id}`));
      const first = await fetch(meta.url);
      assert.equal(first.status, 200);
      assert.equal(await first.text(), "hello-bytes");
      const second = await fetch(meta.url);
      assert.equal(second.status, 404);

      const keep = await fetch(`${url}/v1/files`, {
        method: "POST",
        headers: {
          authorization: `Bearer ${apiKey}`,
          "x-file-name": "keep.bin",
          "x-file-single-use": "false",
        },
        body: "keep-me",
      });
      const keepMeta = await keep.json();
      assert.equal((await fetch(keepMeta.url)).status, 200);
      assert.equal(await (await fetch(keepMeta.url)).text(), "keep-me");

      const brief = await fetch(`${url}/v1/files`, {
        method: "POST",
        headers: {
          authorization: `Bearer ${apiKey}`,
          "x-file-name": "brief.bin",
          "x-file-ttl-ms": "40",
        },
        body: "soon-gone",
      });
      const briefMeta = await brief.json();
      await delay(80);
      const expired = await fetch(briefMeta.url);
      assert.equal(expired.status, 404);
    });
  });
});

describe("parallel phones", () => {
  it("grants lanes at hello and ignores polls from a previous daemon boot", async () => {
    await withRelay({ pollMs: 300 }, async ({ url, apiKey }) => {
      const { claim } = await pairSession(url, apiKey);
      const first = await hello(url, claim.deviceToken, { model: "Pixel", sdk: 34, agentVersion: "2", bootId: "boot-old", parallelJobs: 4 });
      assert.equal(first.body.parallelJobs, 4);
      const legacy = await hello(url, claim.deviceToken, { model: "Pixel", sdk: 34, agentVersion: "1", bootId: "boot-new" });
      assert.equal(legacy.body.parallelJobs, 1, "a phone that does not report lanes runs one job");
      const stale = await httpJson(`${url}/v1/device/next`, { method: "POST", headers: deviceHeaders(claim.deviceToken), body: { bootId: "boot-old" }, timeoutMs: 2000 });
      assert.deepEqual(stale.body, { idle: true, stale: true });
      const current = await httpJson(`${url}/v1/device/next`, { method: "POST", headers: deviceHeaders(claim.deviceToken), body: { bootId: "boot-new" }, timeoutMs: 2000 });
      assert.equal(current.body.idle, true);
      assert.equal(current.body.stale, undefined);
    });
  });
});
