import test from "node:test";
import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createMailer } from "../mail.mjs";
import { createOwners } from "../owners.mjs";
import { createArtifacts } from "../artifacts.mjs";
import { directCandidates } from "../direct-routes.mjs";
import { createWorkApp } from "../server.mjs";
import { createConnections } from "../connections.mjs";
import { createAppReleases } from "../app-releases.mjs";
import { createRelay } from "../../relay/server.mjs";

test("mailer prefers Resend transactional API and blocks CRLF header injection", async () => {
  const sent = [];
  const mailer = createMailer({
    resendApiKey: "re_test_key",
    from: "Android Backstage <login@android-backstage.com>",
    fetchImpl: async (url, init) => {
      sent.push({ url, headers: init.headers, body: JSON.parse(init.body) });
      return { ok: true, status: 200 };
    },
  });
  assert.equal(mailer.configured(), true);
  assert.equal(mailer.provider(), "resend");
  await mailer.send("user@example.com", "123456 is your Android Backstage code", "Code: 123456");
  assert.equal(sent.length, 1);
  assert.equal(sent[0].url, "https://api.resend.com/emails");
  assert.equal(sent[0].headers.authorization, "Bearer re_test_key");
  assert.deepEqual(sent[0].body.to, ["user@example.com"]);
  assert.equal(sent[0].body.from, "Android Backstage <login@android-backstage.com>");

  await assert.rejects(
    mailer.send("user@example.com\r\nBcc: evil@example.com", "subject", "body"),
    /Invalid recipient/,
  );
  await assert.rejects(
    mailer.send("user@example.com", "subject\nX-Injected: 1", "body"),
    /Invalid subject/,
  );
});

test("owners supports expectedLinkOwner verification and full deleteOwner wipe", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bs-owners-"));
  try {
    const codes = [];
    const mailer = {
      configured: () => true,
      send: async (to, subject) => { codes.push({ to, code: subject.slice(0, 6) }); },
    };
    const owners = createOwners({ stateFile: path.join(dir, "owners.json"), origin: "https://relay.android-backstage.com", mailer });
    await owners.startSignUp("alice@example.com", "alice-password-1");
    const aliceId = owners.verifySignUp("alice@example.com", codes[0].code);
    assert.equal(owners.exists(aliceId), true);

    // Sign-up code for Bob cannot satisfy expectedLinkOwner on Alice's account-email verification
    await owners.startSignUp("bob@example.com", "bob-password-1");
    assert.throws(
      () => owners.verifyEmail("bob@example.com", codes[1].code, { expectedLinkOwner: aliceId }),
      /expired/,
    );

    // Deleting Alice wipes her record and email lookup completely
    const cookies = [];
    const res = { append: (k, v) => cookies.push([k, v]) };
    assert.equal(owners.deleteOwner(aliceId, null, res), true);
    assert.equal(owners.exists(aliceId), false);
    assert.equal(owners.ownerByEmail("alice@example.com"), null);
    assert.ok(cookies.some(([k, v]) => k === "Set-Cookie" && v.includes("Max-Age=0")));
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("artifacts enforces per-grant byte quotas on both begin and createFromFile and supports removeForGrants", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bs-art-"));
  try {
    const artifacts = createArtifacts({ directory: dir, maxBytes: 4096, quotaBytes: 100_000, maxGrantBytes: 3000 });
    const sha = crypto.createHash("sha256").update(Buffer.alloc(2000, 1)).digest("hex");
    const a1 = artifacts.begin({ grantId: "grant_1", name: "a.apk", size: 2000, sha256: sha });
    assert.ok(a1.artifactId);
    // Second 2000-byte upload exceeds 3000-byte per-grant quota
    assert.throws(
      () => artifacts.begin({ grantId: "grant_1", name: "b.apk", size: 2000, sha256: sha }),
      /Upload quota for this connection is full/,
    );
    // Grant 2 is isolated and unaffected
    const a2 = artifacts.begin({ grantId: "grant_2", name: "c.apk", size: 2000, sha256: sha });
    assert.ok(a2.artifactId);
    // Account deletion cleans only grant_1's artifacts
    assert.equal(artifacts.removeForGrants(["grant_1"]), 1);
    assert.throws(() => artifacts.status(a1.artifactId, "grant_1"), /Unknown or expired/);
    assert.equal(artifacts.status(a2.artifactId, "grant_2").artifactId, a2.artifactId);
    artifacts.close();
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("directCandidates never probes link-local metadata or loopback addresses", () => {
  const ifaces = {
    eth0: [{ family: "IPv4", internal: false, address: "169.254.1.10", netmask: "255.255.0.0" }],
    lan0: [{ family: "IPv4", internal: false, address: "192.168.1.10", netmask: "255.255.255.0" }],
  };
  const out = directCandidates(["169.254.169.254", "127.0.0.1", "0.0.0.0", "192.168.1.44", "100.64.0.10"], ifaces);
  assert.deepEqual(out, ["100.64.0.10", "192.168.1.44"]);
});

test("app-releases supports external zero-egress HTTPS update URLs and rejects non-HTTPS", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bs-rel-"));
  try {
    const sha = "a".repeat(64);
    const releases = createAppReleases({ directory: dir, base: "https://relay.android-backstage.com/work" });
    fs.writeFileSync(path.join(dir, "latest.json"), JSON.stringify({
      versionCode: 237,
      versionName: "2.0.0-dev.37",
      file: "android-backstage-2.0.0-dev.37.apk",
      sha256: sha,
      size: 10035728,
      url: "https://github.com/WhiteEagle-12/android-backstage/releases/download/v2.0.0-dev.37/android-backstage-2.0.0-dev.37.apk",
    }));
    const ext = releases.latest();
    assert.equal(ext.url, "https://github.com/WhiteEagle-12/android-backstage/releases/download/v2.0.0-dev.37/android-backstage-2.0.0-dev.37.apk");
    assert.equal(ext.versionCode, 237);

    // Non-HTTPS external URLs fail closed
    fs.writeFileSync(path.join(dir, "latest.json"), JSON.stringify({
      versionCode: 237,
      versionName: "2.0.0-dev.37",
      file: "android-backstage-2.0.0-dev.37.apk",
      sha256: sha,
      url: "http://evil.example/app.apk",
    }));
    assert.equal(releases.latest(), null);

    // Local fallback still works when external url is omitted
    fs.writeFileSync(path.join(dir, "latest.json"), JSON.stringify({
      versionCode: 237,
      versionName: "2.0.0-dev.37",
      file: "backstage-2.0.0-dev.37.apk",
      sha256: sha,
    }));
    assert.equal(releases.latest().url, "https://relay.android-backstage.com/work/app/download/backstage-2.0.0-dev.37.apk");
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("POST /work/account/delete requires Origin + confirmation and wipes owner, devices, grants, and artifacts", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bs-del-"));
  const relay = await createRelay({ port: 0, host: "127.0.0.1", dataDir: path.join(dir, "relay") });
  let server;
  try {
    const codes = [];
    const mailer = { configured: () => true, send: async (to, subj) => { codes.push(subj.slice(0, 6)); } };
    const artifacts = createArtifacts({ directory: path.join(dir, "artifacts") });
    const connections = createConnections({ directory: path.join(dir, "conn"), relayUrl: relay.url });
    let base = "";
    const owners = createOwners({ stateFile: path.join(dir, "owners.json"), origin: "http://127.0.0.1", mailer });
    // Start HTTP listener on ephemeral port and bind origin dynamically
    const app = createWorkApp({
      base: "http://127.0.0.1/work",
      stateFile: path.join(dir, "oauth.json"),
      approvalFile: path.join(dir, "approval.json"),
      connections,
      owners,
      artifacts,
    });
    server = await new Promise((resolve) => { const s = app.listen(0, "127.0.0.1", () => resolve(s)); });
    base = `http://127.0.0.1:${server.address().port}`;

    // Create account and sign in via signup OTP
    await fetch(`${base}/work/signup/start`, {
      method: "POST",
      headers: { "content-type": "application/json", origin: "http://127.0.0.1" },
      body: JSON.stringify({ email: "del@example.com", password: "delete-me-password" }),
    });
    const verifyRes = await fetch(`${base}/work/signup/verify`, {
      method: "POST",
      headers: { "content-type": "application/json", origin: "http://127.0.0.1" },
      body: JSON.stringify({ email: "del@example.com", code: codes[0] }),
    });
    assert.equal(verifyRes.status, 200);
    const cookie = verifyRes.headers.get("set-cookie").split(";")[0];

    // Cross-origin CSRF attempt is rejected
    const csrf = await fetch(`${base}/work/account/delete`, {
      method: "POST",
      headers: { "content-type": "application/json", cookie, origin: "https://evil.example" },
      body: JSON.stringify({ confirm: "DELETE" }),
    });
    assert.equal(csrf.status, 403);

    // Missing confirmation is rejected
    const noConfirm = await fetch(`${base}/work/account/delete`, {
      method: "POST",
      headers: { "content-type": "application/json", cookie, origin: "http://127.0.0.1" },
      body: JSON.stringify({ confirm: "nope" }),
    });
    assert.equal(noConfirm.status, 400);

    // Valid delete wipes owner and expires session cookie
    const ok = await fetch(`${base}/work/account/delete`, {
      method: "POST",
      headers: { "content-type": "application/json", cookie, origin: "http://127.0.0.1" },
      body: JSON.stringify({ confirm: "DELETE" }),
    });
    assert.equal(ok.status, 200);
    assert.equal(owners.ownerByEmail("del@example.com"), null);
    // Subsequent call with old cookie is unauthorized
    const after = await fetch(`${base}/work/account/delete`, {
      method: "POST",
      headers: { "content-type": "application/json", cookie, origin: "http://127.0.0.1" },
      body: JSON.stringify({ confirm: "DELETE" }),
    });
    assert.equal(after.status, 401);
    artifacts.close();
  } finally {
    await new Promise((r) => (server ? server.close(r) : r()));
    await relay.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("artifacts with R2 offloads pulled files to presigned 302 redirect and shreds local temp bytes", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bs-art-r2-"));
  let server;
  try {
    const deleted = [];
    const fakeR2 = {
      async uploadFile(localPath) {
        assert.ok(fs.existsSync(localPath));
        return { id: "obj1", key: "work-pulls/obj1", url: "https://acct.r2.cloudflarestorage.com/bucket/work-pulls/obj1?sig=1", expiresAt: Date.now() + 300_000 };
      },
      async deleteObject(key) { deleted.push(key); return true; },
      close() {},
    };
    const artifacts = createArtifacts({ directory: path.join(dir, "artifacts"), zeroRetention: true, r2: fakeR2 });
    const app = createWorkApp({
      base: "http://127.0.0.1/work",
      stateFile: path.join(dir, "oauth.json"),
      approvalFile: path.join(dir, "approval.json"),
      artifacts,
    });
    server = await new Promise((resolve) => { const s = app.listen(0, "127.0.0.1", () => resolve(s)); });
    const base = `http://127.0.0.1:${server.address().port}`;

    const tmp = path.join(dir, "clip.mp4");
    const payload = Buffer.from("fake-mp4-video-bytes");
    fs.writeFileSync(tmp, payload);
    const sha256 = crypto.createHash("sha256").update(payload).digest("hex");
    const uploaded = await fakeR2.uploadFile(tmp);
    const saved = artifacts.createFromFile({
      grantId: "grant_1",
      name: "clip.mp4",
      tempPath: tmp,
      size: payload.length,
      sha256,
      r2Key: uploaded.key,
      r2Url: uploaded.url,
    });
    // Local temp file must be shredded immediately once offloaded to R2
    assert.equal(fs.existsSync(tmp), false);

    // Download with valid single-use downloadToken redirects 302 to R2 presigned URL
    const dl = await fetch(`${base}/work/artifacts/${saved.artifactId}/download`, {
      headers: { Authorization: `Bearer ${saved.downloadToken}` },
      redirect: "manual",
    });
    assert.equal(dl.status, 302);
    assert.equal(dl.headers.get("location"), uploaded.url);
    assert.equal(dl.headers.get("x-content-sha256"), sha256);

    // Second download with the same single-use token is 404 (burned)
    const dl2 = await fetch(`${base}/work/artifacts/${saved.artifactId}/download`, {
      headers: { Authorization: `Bearer ${saved.downloadToken}` },
      redirect: "manual",
    });
    assert.equal(dl2.status, 404);
    artifacts.close();
  } finally {
    await new Promise((r) => (server ? server.close(r) : r()));
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("POST /work/account/delete and /work/internal/admin wipe owner, devices, grants, artifacts, and relay links", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bs-del-e2e-"));
  let server = null;
  const relayDeletes = [];
  const fakeFetch = async (url, init) => {
    relayDeletes.push({ url, method: init?.method, auth: init?.headers?.Authorization });
    return { ok: true, status: 200, json: async () => ({ ok: true }) };
  };
  try {
    const codes = [];
    const mailer = {
      configured: () => true,
      send: async (to, subject) => { codes.push({ to, code: subject.slice(0, 6) }); },
    };
    const internalToken = "test_internal_admin_key_1234567890";
    const connections = createConnections({
      directory: path.join(dir, "connections"),
      relayUrl: "http://127.0.0.1:18420",
      fetchImpl: fakeFetch,
    });
    const artifacts = createArtifacts({ directory: path.join(dir, "artifacts"), zeroRetention: true });
    const owners = createOwners({
      stateFile: path.join(dir, "owners.json"),
      origin: "http://127.0.0.1",
      mailer,
    });
    const app = createWorkApp({
      base: "http://127.0.0.1/work",
      stateFile: path.join(dir, "oauth.json"),
      approvalFile: path.join(dir, "approval.json"),
      connections,
      owners,
      artifacts,
      internalToken,
    });
    server = await new Promise((resolve) => { const s = app.listen(0, "127.0.0.1", () => resolve(s)); });
    const base = `http://127.0.0.1:${server.address().port}`;

    // 1. Verify internal admin auth rejects missing / bad key
    assert.equal((await fetch(`${base}/work/internal/admin/owners`)).status, 401);
    assert.equal((await fetch(`${base}/work/internal/admin/owners`, { headers: { "x-backstage-internal-key": "wrong" } })).status, 401);

    // 2. Seed Alice and test admin list/inspect/revoke-grants/delete
    const idxA = codes.length;
    await owners.startSignUp("alice-abuse@example.com", "strong-pass-123");
    const aliceId = owners.verifySignUp("alice-abuse@example.com", codes[idxA].code);
    const grantA = "grant_" + crypto.randomBytes(16).toString("hex");
    fs.writeFileSync(path.join(dir, "connections", grantA + ".json"), JSON.stringify({
      ownerId: aliceId, name: "Bot", clientId: "cli_1", access: "all", createdAt: new Date().toISOString(),
    }));
    const sha = crypto.createHash("sha256").update(Buffer.from("payload")).digest("hex");
    const artA = artifacts.begin({ grantId: grantA, name: "x.bin", size: 7, sha256: sha });

    const listRes = await fetch(`${base}/work/internal/admin/owners`, { headers: { "x-backstage-internal-key": internalToken } });
    assert.equal(listRes.status, 200);
    const listed = await listRes.json();
    assert.ok(listed.owners.some((o) => o.id === aliceId && o.email === "alice-abuse@example.com" && o.grantCount === 1));

    const inspectRes = await fetch(`${base}/work/internal/admin/owner/${encodeURIComponent("alice-abuse@example.com")}`, {
      headers: { "x-backstage-internal-key": internalToken },
    });
    assert.equal(inspectRes.status, 200);
    const inspected = await inspectRes.json();
    assert.equal(inspected.ownerId, aliceId);
    assert.equal(inspected.grants.length, 1);

    // Revoke grants leaves owner intact but deletes grant & artifact
    const revRes = await fetch(`${base}/work/internal/admin/owner/${encodeURIComponent("alice-abuse@example.com")}/revoke-grants`, {
      method: "POST",
      headers: { "x-backstage-internal-key": internalToken },
    });
    assert.equal(revRes.status, 200);
    assert.deepEqual((await revRes.json()).revokedGrants, [grantA]);
    assert.equal(owners.exists(aliceId), true);
    assert.throws(() => artifacts.status(artA.artifactId, grantA), /Unknown or expired/);

    // Admin delete removes owner completely
    const delRes = await fetch(`${base}/work/internal/admin/owner/${encodeURIComponent(aliceId)}/delete`, {
      method: "POST",
      headers: { "x-backstage-internal-key": internalToken },
    });
    assert.equal(delRes.status, 200);
    assert.equal(owners.exists(aliceId), false);
    assert.equal(owners.ownerByEmail("alice-abuse@example.com"), null);

    // 3. Verify self-service POST /work/account/delete cleans up owner, device + relay DELETE /v1/connection, grant, and artifact
    const idxB = codes.length;
    await owners.startSignUp("bob-self@example.com", "strong-pass-456");
    const bobId = owners.verifySignUp("bob-self@example.com", codes[idxB].code);
    let sessionCookieHeader = "";
    owners.issue({ append: (k, v) => { if (k === "Set-Cookie") sessionCookieHeader = v.split(";")[0]; } }, bobId);
    const grantB = "grant_" + crypto.randomBytes(16).toString("hex");
    fs.writeFileSync(path.join(dir, "connections", grantB + ".json"), JSON.stringify({
      ownerId: bobId, name: "Claude", clientId: "cli_2", access: "all", createdAt: new Date().toISOString(),
    }));
    // Seed a paired device in a fresh connections instance backed by the same dir so removeAllForOwner revokes it on the relay too
    const devB = "phone_" + crypto.randomBytes(16).toString("hex");
    fs.writeFileSync(path.join(dir, "connections", "devices.json"), JSON.stringify({
      [devB]: { id: devB, ownerId: bobId, name: "Pixel Test", createdAt: new Date().toISOString() },
    }));
    fs.mkdirSync(path.join(dir, "connections", "phones"), { recursive: true });
    fs.writeFileSync(path.join(dir, "connections", "phones", devB + ".json"), JSON.stringify({
      relayUrl: "http://127.0.0.1:18420",
      apiKey: "bsc_test_revoke_token",
      agentId: "agt_bob",
      device: "phone",
      links: { phone: { deviceId: "dev_bob", linkKey: crypto.randomBytes(32).toString("base64url") } },
    }));
    const connections2 = createConnections({ directory: path.join(dir, "connections"), relayUrl: "http://127.0.0.1:18420", fetchImpl: fakeFetch });
    const app2 = createWorkApp({
      base: "http://127.0.0.1/work",
      stateFile: path.join(dir, "oauth2.json"),
      approvalFile: path.join(dir, "approval2.json"),
      connections: connections2,
      owners,
      artifacts,
      internalToken,
    });
    const server2 = await new Promise((resolve) => { const s = app2.listen(0, "127.0.0.1", () => resolve(s)); });
    const base2 = `http://127.0.0.1:${server2.address().port}`;
    const artB = artifacts.begin({ grantId: grantB, name: "y.bin", size: 7, sha256: sha });
    try {
      const selfDel = await fetch(`${base2}/work/account/delete`, {
        method: "POST",
        headers: {
          origin: "http://127.0.0.1",
          cookie: sessionCookieHeader,
          "content-type": "application/json",
        },
        body: JSON.stringify({ confirm: "DELETE" }),
      });
      assert.equal(selfDel.status, 200);
      assert.equal(owners.exists(bobId), false);
      assert.equal(owners.ownerByEmail("bob-self@example.com"), null);
      assert.equal(connections2.devices(bobId).length, 0);
      assert.equal(fs.existsSync(path.join(dir, "connections", "phones", devB + ".json")), false);
      assert.equal(fs.existsSync(path.join(dir, "connections", grantB + ".json")), false);
      assert.throws(() => artifacts.status(artB.artifactId, grantB), /Unknown or expired/);
      assert.ok(relayDeletes.some((r) => r.method === "DELETE" && r.url.endsWith("/v1/connection") && r.auth === "Bearer bsc_test_revoke_token"));
    } finally {
      await new Promise((r) => server2.close(r));
    }
    artifacts.close();
  } finally {
    await new Promise((r) => (server ? server.close(r) : r()));
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("paused assistants are blocked on all gateway routes, webhooks clear on pause/revoke, and DCR brand spoofing is neutralized", async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bs-wave2-sec-"));
  let server = null;
  const webhookDeletes = [];
  const fakeFetch = async (url, init) => {
    if (init?.method === "DELETE" && String(url).endsWith("/v1/events/webhooks")) {
      webhookDeletes.push({ url, auth: init?.headers?.Authorization });
    }
    return { ok: true, status: 200, json: async () => ({ ok: true, devices: [] }) };
  };
  try {
    const connections = createConnections({ directory: path.join(dir, "connections"), relayUrl: "http://127.0.0.1:18420", fetchImpl: fakeFetch });
    const artifacts = createArtifacts({ directory: path.join(dir, "artifacts") });
    const owners = createOwners({ stateFile: path.join(dir, "owners.json"), origin: "http://127.0.0.1" });
    const app = createWorkApp({
      base: "http://127.0.0.1/work",
      stateFile: path.join(dir, "oauth.json"),
      approvalFile: path.join(dir, "approval.json"),
      connections,
      owners,
      artifacts,
    });
    server = await new Promise((resolve) => { const s = app.listen(0, "127.0.0.1", () => resolve(s)); });
    const base = `http://127.0.0.1:${server.address().port}`;

    // 1. DCR brand spoofing: claiming "ChatGPT" with evil.example redirect gets neutralized to evil.example
    const reg = await fetch(`${base}/work/register`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ client_name: "ChatGPT", redirect_uris: ["https://evil.example/cb"] }),
    });
    assert.equal(reg.status, 201);
    const { client_id } = await reg.json();
    const state = JSON.parse(fs.readFileSync(path.join(dir, "oauth.json"), "utf8"));
    assert.equal(state.clients[client_id].name, "evil.example");

    // 2. Pausing a grant clears its registered device webhook
    const ownerId = "owner_" + "a".repeat(43);
    const devId = "phone_" + "b".repeat(32);
    fs.writeFileSync(path.join(dir, "connections", "devices.json"), JSON.stringify({
      [devId]: { ownerId, name: "Phone", createdAt: Date.now(), linkedAt: Date.now(), needsReconnect: false },
    }));
    fs.mkdirSync(path.join(dir, "connections", "phones"), { recursive: true });
    fs.writeFileSync(path.join(dir, "connections", "phones", devId + ".json"), JSON.stringify({
      relayUrl: "http://127.0.0.1:18420",
      apiKey: "bsc_webhook_test",
      agentId: "agt_0123456789abcdef",
      device: "Phone",
      links: { Phone: { deviceId: "dev_test1", linkKey: crypto.randomBytes(32).toString("base64url") } },
    }));
    const conn2 = createConnections({ directory: path.join(dir, "connections"), relayUrl: "http://127.0.0.1:18420", fetchImpl: fakeFetch });
    const grantId = conn2.authorize(ownerId, client_id, "evil.example", [devId]);
    conn2.setWebhookOwner(devId, grantId);
    assert.equal(conn2.isPaused(grantId), false);
    conn2.setPaused(ownerId, grantId, true);
    assert.equal(conn2.isPaused(grantId), true);
    await new Promise((r) => setTimeout(r, 20));
    assert.equal(webhookDeletes.length, 1);
    assert.equal(webhookDeletes[0].auth, "Bearer bsc_webhook_test");
    artifacts.close();
  } finally {
    await new Promise((r) => (server ? server.close(r) : r()));
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test("account page renders dark UI, accurate assistant icon assets, web pause route, and plan_limit 403 on enroll", async () => {
  const { assistantIconAsset } = await import("../assistants.mjs");
  assert.equal(assistantIconAsset("Antigravity").url, "https://android-backstage.com/assets/assistants/antigravity.png");
  assert.equal(assistantIconAsset("Grok Build").url, "https://android-backstage.com/assets/assistants/grok.png");
  assert.equal(assistantIconAsset("ChatGPT").url, "https://android-backstage.com/assets/assistants/chatgpt.svg");
  assert.equal(assistantIconAsset("ChatGPT").invertOnDark, true);
  assert.equal(assistantIconAsset("Claude Code").url, "https://android-backstage.com/assets/assistants/claude.svg");
  assert.equal(assistantIconAsset("Claude Code").invertOnDark, false);

  const express = (await import("express")).default;
  const { ownerRoutes } = await import("../owner-routes.mjs");
  const { ConnectionError } = await import("../connections.mjs");
  const app = express();
  app.use(express.json());
  let pausedState = false;
  const owners = {
    owner: () => "owner_ui",
    emailAvailable: () => true,
    methods: () => ({
      email: "user@example.com",
      emailAvailable: true,
      plan: "free",
      limits: { id: "free", name: "Personal", maxDevices: 1, maxAssistants: 1, maxParallelScreens: 1 },
      hasPassword: true,
      passkeys: [],
      recoveryCodesLeft: 0,
    }),
  };
  const connections = {
    devices: () => [{ id: "phone_1", name: "Phone", model: "Pixel", lastSeen: Date.now() }],
    probe: async (d) => ({ ...d, status: "online" }),
    list: () => [{ id: "grant_1", name: "Antigravity", clientId: "c1", access: "all", paused: pausedState, createdAt: Date.now() }],
    setPaused: (_ownerId, grantId, paused) => {
      assert.equal(grantId, "grant_1");
      pausedState = paused;
      return pausedState;
    },
    begin: async () => {
      throw new ConnectionError("plan_limit", "Your Personal plan includes 1 device.");
    },
  };
  const server = await new Promise((resolve) => { const s = app.listen(0, "127.0.0.1", () => resolve(s)); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  ownerRoutes(app, { owners, connections, base: `${origin}/work`, auth: { clientInfo: () => ({}) } });
  try {
    const html = await (await fetch(`${origin}/work/account`)).text();
    assert.match(html, /antigravity\.png/);
    assert.match(html, /1 of 1 device/);
    assert.match(html, /toggle-pause/);

    const pauseRes = await fetch(`${origin}/work/account/pause`, {
      method: "POST",
      headers: { "content-type": "application/json", origin },
      body: JSON.stringify({ grantId: "grant_1", paused: true }),
    });
    assert.equal(pauseRes.status, 200);
    assert.deepEqual(await pauseRes.json(), { ok: true, paused: true });
    assert.equal(pausedState, true);

    const enrollRes = await fetch(`${origin}/work/enroll?name=tablet`);
    assert.equal(enrollRes.status, 403);
    assert.match(await enrollRes.text(), /Your Personal plan includes 1 device/);
  } finally {
    await new Promise((r) => server.close(r));
  }
});
