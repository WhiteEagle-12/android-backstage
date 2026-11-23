import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { withRelay, httpJson } from "./helpers.mjs";
import { isPublicIp, parsePublicWebhookUrl, resolvePublicWebhook } from "../webhook.mjs";
import { createR2Store } from "../r2.mjs";
import { createFiles } from "../files.mjs";
import { createQueue } from "../queue.mjs";
import { createRelay } from "../server.mjs";

test("webhook SSRF guard blocks loopback, RFC1918, Tailnet, metadata, IPv4-mapped IPv6, and DNS rebinding", async () => {
  for (const badIp of [
    "127.0.0.1", "10.0.0.1", "172.16.0.1", "172.31.255.255", "192.168.1.1",
    "169.254.169.254", "100.64.0.1", "100.127.255.254", "0.0.0.0", "224.0.0.1",
    "::1", "::", "fe80::1", "fc00::1", "fd7a:115c:a1e0::1",
    "::ffff:127.0.0.1", "::ffff:169.254.169.254", "::ffff:10.0.0.5", "64:ff9b::10.0.0.1",
  ]) {
    assert.equal(isPublicIp(badIp), false, `expected ${badIp} to be blocked`);
  }
  for (const goodIp of ["1.1.1.1", "8.8.8.8", "93.184.216.34", "2606:4700:4700::1111"]) {
    assert.equal(isPublicIp(goodIp), true, `expected ${goodIp} to be allowed`);
  }

  for (const badUrl of [
    "http://example.com/hook",
    "https://user:pass@example.com/hook",
    "https://metadata.google.internal/computeMetadata/v1/",
    "https://foo.local/hook",
    "https://100.64.0.10/hook",
    "https://[::ffff:127.0.0.1]/hook",
  ]) {
    assert.throws(() => parsePublicWebhookUrl(badUrl), /public https address/);
  }

  // DNS resolution returning a private IP (e.g. nip.io or rebinding) must fail closed.
  await assert.rejects(
    resolvePublicWebhook("https://evil.example/hook", {
      lookup: async () => [{ address: "169.254.169.254", family: 4 }],
    }),
    /resolve to a public address/,
  );

  // Mixed public + private DNS answers must also fail closed.
  await assert.rejects(
    resolvePublicWebhook("https://mixed.example/hook", {
      lookup: async () => [
        { address: "93.184.216.34", family: 4 },
        { address: "100.64.0.10", family: 4 },
      ],
    }),
    /resolve to a public address/,
  );

  const ok = await resolvePublicWebhook("https://hooks.example/backstage", {
    lookup: async () => [{ address: "93.184.216.34", family: 4 }],
  });
  assert.equal(ok.address.address, "93.184.216.34");
});

test("relay rejects private-DNS webhooks and enforces per-IP cap on /v1/pair/connect", async () => {
  await withRelay(
    {
      webhookLookup: async (host) =>
        host === "evil-rebind.example"
          ? [{ address: "169.254.169.254", family: 4 }]
          : [{ address: "93.184.216.34", family: 4 }],
    },
    async ({ url, apiKey }) => {
      const bad = await httpJson(`${url}/v1/events/webhooks`, {
        method: "POST",
        headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json" },
        body: { url: "https://evil-rebind.example/hook" },
      });
      assert.equal(bad.status, 400);

      const good = await httpJson(`${url}/v1/events/webhooks`, {
        method: "POST",
        headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json" },
        body: { url: "https://good.example/hook", secret: "s3cr3t" },
      });
      assert.equal(good.status, 200);
      assert.equal(good.body.webhook.hasSecret, true);

      // Per-IP cap on unauthenticated /v1/pair/connect (8 pending per IP)
      for (let i = 0; i < 8; i++) {
        const res = await httpJson(`${url}/v1/pair/connect`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: { name: `phone-${i}` },
        });
        assert.equal(res.status, 200, `attempt ${i} should succeed`);
      }
      const ninth = await httpJson(`${url}/v1/pair/connect`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: { name: "phone-overflow" },
      });
      assert.equal(ninth.status, 429);
    },
  );
});

test("per-account quotas in files and queue isolate noisy neighbors", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bs-quota-"));
  try {
    const sample = path.join(dir, "sample.bin");
    fs.writeFileSync(sample, Buffer.alloc(1024, 0x41));
    const files = createFiles({ dataDir: dir, maxBytes: 4096, quotaBytes: 100_000, maxAccountFiles: 2, maxAccountBytes: 3000 });
    files.publishLocal(sample, { owner: "acct_a" });
    files.publishLocal(sample, { owner: "acct_a" });
    assert.throws(() => files.publishLocal(sample, { owner: "acct_a" }), /account file storage quota exceeded/);
    // Account B is unaffected
    const b = files.publishLocal(sample, { owner: "acct_b" });
    assert.ok(b.id);

    const queue = createQueue({ dataDir: dir, maxPending: 32, maxAccountJobs: 2, maxAccountBytes: 100 * 1024 * 1024 });
    queue.enqueue("dev_1", { id: "job-acct-a-000001", accountId: "acct_a", envelope: { v: 2, from: "a", n: "1", r: "aaaaaaaaaaaaaaaa", ct: "x" } });
    queue.enqueue("dev_1", { id: "job-acct-a-000002", accountId: "acct_a", envelope: { v: 2, from: "a", n: "2", r: "bbbbbbbbbbbbbbbb", ct: "x" } });
    assert.throws(
      () => queue.enqueue("dev_2", { id: "job-acct-a-000003", accountId: "acct_a", envelope: { v: 2, from: "a", n: "3", r: "cccccccccccccccc", ct: "x" } }),
      /account queue capacity reached/,
    );
    // Account B can still enqueue
    const jobB = queue.enqueue("dev_2", { id: "job-acct-b-000001", accountId: "acct_b", envelope: { v: 2, from: "b", n: "1", r: "dddddddddddddddd", ct: "x" } });
    assert.equal(jobB.state, "queued");
    queue.close();
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("R2 zero-egress store signs SigV4 PUT and returns presigned GET URL", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bs-r2-"));
  try {
    const file = path.join(dir, "blob.bsf1");
    fs.writeFileSync(file, Buffer.from("encrypted-ciphertext"));
    const calls = [];
    const r2 = createR2Store({
      endpoint: "https://acct123.r2.cloudflarestorage.com",
      bucket: "backstage-files",
      accessKeyId: "AKIA_TEST",
      secretAccessKey: "SECRET_TEST",
      now: () => new Date("2026-11-23T00:00:00.000Z"),
      fetchImpl: async (url, init) => {
        calls.push({ url, method: init.method, headers: init.headers, len: init.body.length });
        return { ok: true, status: 200, text: async () => "" };
      },
    });
    const out = await r2.uploadFile(file, { ttlSec: 900 });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, "PUT");
    assert.match(calls[0].headers.authorization, /^AWS4-HMAC-SHA256 Credential=AKIA_TEST\/20261123\/auto\/s3\/aws4_request/);
    assert.match(out.url, /^https:\/\/acct123\.r2\.cloudflarestorage\.com\/backstage-files\/relay-files\/[a-f0-9]{64}\?/);
    assert.match(out.url, /X-Amz-Algorithm=AWS4-HMAC-SHA256/);
    assert.match(out.url, /X-Amz-Expires=900/);
    assert.match(out.url, /X-Amz-Signature=[a-f0-9]{64}$/);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("relay zeroRetention mode keeps jobs and dashboard strictly in memory and purges stale disk artifacts", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bs-relay-zdr-"));
  try {
    fs.mkdirSync(path.join(dir, "jobs"), { recursive: true });
    fs.writeFileSync(path.join(dir, "jobs", "stale.json"), '{"id":"stale"}');
    fs.writeFileSync(path.join(dir, "dashboard-activity.json"), '[{"route":"/v1/call"}]');

    const relay = await createRelay({ port: 0, host: "127.0.0.1", dataDir: dir, zeroRetention: true, log: { log() {}, error() {} } });
    try {
      assert.equal(fs.existsSync(path.join(dir, "jobs")), false, "stale jobs dir removed on startup");
      assert.equal(fs.existsSync(path.join(dir, "dashboard-activity.json")), false, "stale dashboard history removed on startup");

      const h = await fetch(`${relay.url}/v1/me`, { headers: { authorization: `Bearer ${relay.firstApiKey}` } });
      assert.equal(h.status, 200);
      assert.equal(fs.existsSync(path.join(dir, "jobs")), false, "no jobs dir created in zeroRetention mode");
      assert.equal(fs.existsSync(path.join(dir, "dashboard-activity.json")), false, "no dashboard file created in zeroRetention mode");
    } finally {
      await relay.close();
    }
    assert.equal(fs.existsSync(path.join(dir, "dashboard-activity.json")), false, "close() does not flush dashboard to disk in zeroRetention mode");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
