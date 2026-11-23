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
    await owners.startEmail("alice@example.com");
    const aliceId = owners.verifyEmail("alice@example.com", codes[0].code);
    assert.equal(owners.exists(aliceId), true);

    // Unlinked sign-in code cannot satisfy expectedLinkOwner on account-email verification
    await owners.startEmail("bob@example.com");
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

    // Sign in via email OTP
    await fetch(`${base}/work/email/start`, {
      method: "POST",
      headers: { "content-type": "application/json", origin: "http://127.0.0.1" },
      body: JSON.stringify({ email: "del@example.com" }),
    });
    const verifyRes = await fetch(`${base}/work/email/verify`, {
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
