import { test } from "node:test";
import assert from "node:assert/strict";
import { withRelay, pairSession, hello, httpJson, apiHeaders, fakeEnvelope, delay } from "./helpers.mjs";

test("a phone that polled moments ago gets its jobs queued, not refused as offline", async () => {
  await withRelay({ offlineMs: 60, reconnectGraceMs: 2_000, pollMs: 200 }, async ({ url, apiKey }) => {
    const { claim } = await pairSession(url, apiKey, { name: "phone" });
    const submit = (id) => httpJson(`${url}/v1/jobs`, { method: "POST", headers: apiHeaders(apiKey),
      body: { id, device: "phone", envelope: fakeEnvelope(), timeoutMs: 5_000 } });
    assert.equal((await submit("never-seen-phone-0001")).status, 409, "a phone never seen is offline");
    await hello(url, claim.deviceToken, { model: "Pixel", sdk: 34, agentVersion: "2.0.0-dev.26", lanAddresses: ["10.0.0.5", "100.64.0.10", 7] });
    await delay(150);
    const queued = await submit("reconnecting-phone-0001");
    assert.equal(queued.status, 202, "inside the reconnect grace");
    assert.equal(queued.body.state, "queued");
    await delay(2_100);
    assert.equal((await submit("long-gone-phone-00001")).status, 409, "past the grace it is offline");
    const listed = await httpJson(`${url}/v1/devices`, { headers: apiHeaders(apiKey) });
    assert.deepEqual(listed.body.devices[0].lanAddresses, ["10.0.0.5", "100.64.0.10"], "addresses for the direct route, strings only");
  });
});

test("reusable files resume with byte ranges; single-use files survive a HEAD", async () => {
  await withRelay(async ({ url, apiKey }) => {
    const keep = await (await fetch(`${url}/v1/files`, { method: "POST",
      headers: { authorization: `Bearer ${apiKey}`, "x-file-name": "keep.bin", "x-file-single-use": "false" }, body: "0123456789" })).json();
    const head = await fetch(keep.url, { method: "HEAD" });
    assert.equal(head.status, 200);
    assert.equal(head.headers.get("accept-ranges"), "bytes");
    assert.equal(head.headers.get("content-length"), "10");
    const tail = await fetch(keep.url, { headers: { range: "bytes=4-" } });
    assert.equal(tail.status, 206);
    assert.equal(tail.headers.get("content-range"), "bytes 4-9/10");
    assert.equal(await tail.text(), "456789");
    const middle = await fetch(keep.url, { headers: { range: "bytes=2-3" } });
    assert.equal(await middle.text(), "23");
    assert.equal((await fetch(keep.url, { headers: { range: "bytes=10-" } })).status, 416);
    assert.equal(await (await fetch(keep.url)).text(), "0123456789", "whole file without a range");

    const once = await (await fetch(`${url}/v1/files`, { method: "POST",
      headers: { authorization: `Bearer ${apiKey}`, "x-file-name": "once.bin" }, body: "abc" })).json();
    assert.equal((await fetch(once.url, { method: "HEAD" })).status, 200);
    const got = await fetch(once.url, { headers: { range: "bytes=1-" } });
    assert.equal(got.status, 200, "single-use files ignore ranges");
    assert.equal(await got.text(), "abc");
    assert.equal((await fetch(once.url)).status, 404);
  });
});
