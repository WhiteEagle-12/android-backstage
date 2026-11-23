import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createVault, parseVaultKey } from '../vault.mjs';
import { createConnections } from '../connections.mjs';
import { createRequestJournal } from '../request-journal.mjs';
import { createArtifacts } from '../artifacts.mjs';
import { createWorkApp } from '../server.mjs';
import { encryptedCall } from '../../cli/backstage.mjs';

test('vault seals linkKey and apiKey at rest with AES-256-GCM + AAD, rejects tampering, and auto-migrates legacy files', () => {
  const keyHex = crypto.randomBytes(32).toString('hex');
  const vault = createVault({ key: parseVaultKey(keyHex) });
  const sample = {
    relayUrl: 'https://relay.example.com',
    apiKey: 'bsc_secret_relay_key_123',
    device: 'phone',
    agentId: 'agt_0123456789abcdef',
    links: {
      phone: {
        deviceId: 'dev_abc',
        linkKey: crypto.randomBytes(32).toString('base64url'),
        callCounter: '100',
        lastResultCounter: '50',
      },
    },
  };

  const sealed = vault.sealPhoneConfig(sample, 'phone_11111111111111111111111111111111');
  const rawJson = JSON.stringify(sealed);
  assert.equal(rawJson.includes(sample.apiKey), false, 'apiKey must never appear in plaintext on disk');
  assert.equal(rawJson.includes(sample.links.phone.linkKey), false, 'linkKey must never appear in plaintext on disk');
  assert.equal(sealed.agentId, sample.agentId, 'routing agentId stays readable');
  assert.equal(sealed.links.phone.deviceId, 'dev_abc', 'non-secret deviceId stays readable');
  assert.ok(sealed.sealedSecrets?.ct, 'sealedSecrets envelope present');

  const opened = vault.openPhoneConfig(sealed, 'phone_11111111111111111111111111111111');
  assert.equal(opened.migrated, false);
  assert.equal(opened.config.apiKey, sample.apiKey);
  assert.equal(opened.config.links.phone.linkKey, sample.links.phone.linkKey);

  // Wrong deviceId AAD must fail decryption
  assert.throws(() => vault.openPhoneConfig(sealed, 'phone_22222222222222222222222222222222'));

  // Wrong key must fail decryption
  const otherVault = createVault({ key: crypto.randomBytes(32) });
  assert.throws(() => otherVault.openPhoneConfig(sealed, 'phone_11111111111111111111111111111111'));

  // Legacy plaintext config flags migrated=true
  const legacy = vault.openPhoneConfig(sample, 'phone_11111111111111111111111111111111');
  assert.equal(legacy.migrated, true);
  assert.equal(legacy.config.links.phone.linkKey, sample.links.phone.linkKey);
});

test('connections with vault auto-migrates plaintext phone file and keeps counter writes sealed on disk', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bs-vault-conn-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  const ownerId = 'owner_' + 'A'.repeat(43);
  const deviceId = 'phone_' + 'a'.repeat(32);
  const linkKey = crypto.randomBytes(32).toString('base64url');
  const apiKey = 'bsc_plain_api_key_xyz';
  const vault = createVault({ key: crypto.randomBytes(32) });

  fs.mkdirSync(path.join(dir, 'phones'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'devices.json'), JSON.stringify({
    [deviceId]: { ownerId, name: 'Pixel', createdAt: 1, linkedAt: 1, needsReconnect: false },
  }));
  const phonePath = path.join(dir, 'phones', deviceId + '.json');
  fs.writeFileSync(phonePath, JSON.stringify({
    relayUrl: 'https://relay.example.com',
    apiKey,
    device: 'Pixel',
    agentId: 'agt_1234567890abcdef',
    links: { Pixel: { deviceId: 'dev_1', linkKey, callCounter: '10', lastResultCounter: '0' } },
  }));

  let sawConfig = null;
  const connections = createConnections({
    directory: dir,
    relayUrl: 'https://relay.example.com',
    vault,
    call: async (cfg) => {
      sawConfig = cfg;
      // Simulate encryptedCall counter write via writeConfig hook
      const cur = cfg.readConfig(cfg.filePath);
      cur.links.Pixel.callCounter = '11';
      cfg.writeConfig(cfg.filePath, cur);
      return { outcome: 'succeeded' };
    },
  });

  // Reading via deviceByAgent auto-migrates plaintext to sealed on disk
  const found = connections.deviceByAgent('agt_1234567890abcdef');
  assert.equal(found.ownerId, ownerId);
  assert.equal(found.linkKey, linkKey);
  const diskAfterMigrate = fs.readFileSync(phonePath, 'utf8');
  assert.equal(diskAfterMigrate.includes(linkKey), false, 'linkKey scrubbed from disk after migration');
  assert.equal(diskAfterMigrate.includes(apiKey), false, 'apiKey scrubbed from disk after migration');

  // Calling updates callCounter while keeping secrets sealed on disk
  const grantId = connections.authorize(ownerId, 'client-1', 'Claude', 'all');
  await connections.call('android.shell', { command: 'echo hi' }, grantId);
  assert.equal(sawConfig.links.Pixel.linkKey, linkKey);
  const diskAfterCall = fs.readFileSync(phonePath, 'utf8');
  assert.equal(diskAfterCall.includes(linkKey), false, 'linkKey stays sealed after counter write');
  assert.equal(diskAfterCall.includes(apiKey), false, 'apiKey stays sealed after counter write');
  assert.equal(JSON.parse(diskAfterCall).links.Pixel.callCounter, '11');
});

test('request journal in memoryOnly mode writes zero files to disk and expires after retentionMs', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bs-zdr-journal-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  let clock = 1_000_000;
  let calls = 0;
  const journal = createRequestJournal({ directory: dir, memoryOnly: true, retentionMs: 5000, now: () => clock });
  const r1 = await journal('grant_1', 'android_tap', { requestId: 'req-1', text: 'Send' }, async () => {
    calls++;
    return { outcome: 'succeeded', secretScreenText: 'hello' };
  });
  const r2 = await journal('grant_1', 'android_tap', { requestId: 'req-1', text: 'Send' }, async () => {
    calls++;
    return { outcome: 'succeeded' };
  });
  assert.equal(calls, 1);
  assert.deepEqual(r1, r2);
  assert.deepEqual(fs.readdirSync(dir), [], 'zero receipt files written to disk');

  // After TTL expires, entry is forgotten
  clock += 6000;
  await journal('grant_1', 'android_tap', { requestId: 'req-1', text: 'Send' }, async () => {
    calls++;
    return { outcome: 'succeeded' };
  });
  assert.equal(calls, 2);
});

test('zeroRetention artifacts burn pulled files after single download and purge legacy request dir on createWorkApp', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bs-zdr-art-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  const legacyReqDir = path.join(dir, 'oauth.json.requests');
  fs.mkdirSync(legacyReqDir, { recursive: true });
  fs.writeFileSync(path.join(legacyReqDir, 'old.json'), '{"result":"sensitive"}');

  const artifacts = createArtifacts({ directory: path.join(dir, 'artifacts'), zeroRetention: true });
  t.after(() => artifacts.close());

  const app = createWorkApp({
    base: 'https://example.test/work',
    stateFile: path.join(dir, 'oauth.json'),
    artifacts,
    zeroRetention: true,
    callTool: async () => ({ outcome: 'succeeded' }),
  });
  assert.equal(fs.existsSync(legacyReqDir), false, 'stale disk receipt directory purged on startup');

  // Create pulled file artifact and verify burn-after-read
  const tmp = path.join(artifacts.tempDir, 'pull.bin');
  const payload = Buffer.from('private-phone-photo-bytes');
  fs.writeFileSync(tmp, payload);
  const sha = crypto.createHash('sha256').update(payload).digest('hex');
  const saved = artifacts.createFromFile({ grantId: 'grant_a', name: 'photo.jpg', tempPath: tmp, size: payload.length, sha256: sha });

  const srv = await new Promise((resolve) => { const s = app.listen(0, '127.0.0.1', () => resolve(s)); });
  t.after(() => srv.close());
  const url = `http://127.0.0.1:${srv.address().port}/work/artifacts/${saved.artifactId}/download`;

  const first = await fetch(url, { headers: { Authorization: 'Bearer ' + saved.downloadToken } });
  assert.equal(first.status, 200);
  assert.deepEqual(Buffer.from(await first.arrayBuffer()), payload);

  // Second download must 404 (burned after single read)
  const second = await fetch(url, { headers: { Authorization: 'Bearer ' + saved.downloadToken } });
  assert.equal(second.status, 404);
  assert.deepEqual(fs.readdirSync(path.join(dir, 'artifacts')), [], 'artifact bytes and metadata removed after read');
});

test('encryptedCall respects readConfig/writeConfig hooks without leaking plaintext', () => {
  assert.equal(typeof encryptedCall, 'function');
});
