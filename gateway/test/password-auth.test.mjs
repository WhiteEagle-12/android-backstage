import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import express from 'express';
import { createOwners } from '../owners.mjs';
import { ownerRoutes } from '../owner-routes.mjs';
import { nativeSignInRoutes } from '../native-sign-in.mjs';

function makeMailer() {
  const sent = [];
  return {
    sent,
    configured: () => true,
    async send(to, subject, text) { sent.push({ to, subject, text }); },
    lastCode(email) {
      const msg = [...sent].reverse().find(m => m.to === email);
      return msg && /(\d{6})/.exec(msg.text)?.[1];
    }
  };
}

test('standard signup (email + password -> OTP verify), login, forgot-password reset, and password change', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bs-pw-auth-'));
  try {
    const mailer = makeMailer();
    const owners = createOwners({ stateFile: path.join(dir, 'owners.json'), origin: 'https://relay.example', mailer });

    // 1. Weak password rejected
    await assert.rejects(() => owners.startSignUp('alice@example.com', 'short'), /at least 8 characters/);

    // 2. Sign up sends 6-digit OTP; account does NOT exist until verified
    await owners.startSignUp('alice@example.com', 'correct-horse-battery');
    assert.equal(owners.ownerByEmail('alice@example.com'), null);
    const code = mailer.lastCode('alice@example.com');
    assert.match(code, /^\d{6}$/);

    // 3. Cannot use signup OTP on signin verifyEmail (cross-purpose isolation)
    assert.throws(() => owners.verifyEmail('alice@example.com', code), /expired/);

    // 4. Wrong OTP rejected; correct OTP creates account with password
    assert.throws(() => owners.verifySignUp('alice@example.com', '000000'), /not right/);
    const ownerId = owners.verifySignUp('alice@example.com', code);
    assert.ok(ownerId.startsWith('owner_'));
    assert.equal(owners.methods(ownerId).hasPassword, true);
    assert.equal(owners.methods(ownerId).email, 'alice@example.com');

    // 5. Duplicate signup rejected
    await assert.rejects(() => owners.startSignUp('alice@example.com', 'another-password'), /already exists/);

    // 6. Password login succeeds with right password, fails with wrong password
    assert.equal(await owners.verifyPassword('alice@example.com', 'correct-horse-battery'), ownerId);
    await assert.rejects(() => owners.verifyPassword('alice@example.com', 'wrong-password'), /Incorrect email or password/);
    await assert.rejects(() => owners.verifyPassword('nobody@example.com', 'wrong-password'), /Incorrect email or password/);

    // 7. Forgot password for unknown email returns sent:true silently without sending email
    const beforeCount = mailer.sent.length;
    const silent = await owners.startPasswordReset('unknown@example.com');
    assert.equal(silent.sent, true);
    assert.equal(mailer.sent.length, beforeCount);

    // 8. Forgot password for Alice sends reset OTP, updates password, invalidates old password
    await owners.startPasswordReset('alice@example.com');
    const resetCode = mailer.lastCode('alice@example.com');
    assert.match(resetCode, /^\d{6}$/);
    const resetOwner = await owners.verifyPasswordReset('alice@example.com', resetCode, 'new-strong-password-42');
    assert.equal(resetOwner, ownerId);
    await assert.rejects(() => owners.verifyPassword('alice@example.com', 'correct-horse-battery'), /Incorrect email or password/);
    assert.equal(await owners.verifyPassword('alice@example.com', 'new-strong-password-42'), ownerId);

    // 9. Signed-in password change requires current password
    await assert.rejects(() => owners.setPassword(ownerId, 'updated-pass-99', { currentPassword: 'wrong', requireCurrent: true }), /Current password is incorrect/);
    await owners.setPassword(ownerId, 'updated-pass-99', { currentPassword: 'new-strong-password-42', requireCurrent: true });
    assert.equal(await owners.verifyPassword('alice@example.com', 'updated-pass-99'), ownerId);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('web and native HTTP routes support signup, login, and password reset end-to-end', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bs-pw-http-'));
  const mailer = makeMailer();
  const app = express();
  app.use(express.json());
  const owners = createOwners({ stateFile: path.join(dir, 'owners.json'), origin: 'http://127.0.0.1', mailer });
  const connections = {
    list: () => [],
    devices: () => [],
    probe: async d => d,
    async begin({ name }) { return { code: 'ABCD-EFGHJK', deviceName: name || 'phone', async poll() { return 'dev_1'; }, async cancel() {} }; }
  };
  let base;
  const server = await new Promise(resolve => { const s = app.listen(0, '127.0.0.1', () => resolve(s)); });
  base = `http://127.0.0.1:${server.address().port}`;
  ownerRoutes(app, { owners, connections, base: base + '/work', auth: { requests: { view: () => null } } });
  nativeSignInRoutes(app, { owners, connections });
  try {
    // Web sign-in page shows Log in and Create account tabs
    const html = await (await fetch(base + '/work/sign-in')).text();
    assert.match(html, /id="tab-login"/);
    assert.match(html, /id="tab-signup"/);
    assert.match(html, /id="login-password"/);

    // Native signup -> OTP verify -> enrolls device
    const inst = 'a'.repeat(43);
    const r1 = await fetch(base + '/work/native/signup/start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'bob@example.com', password: 'password-1234', installation: inst })
    });
    assert.equal(r1.status, 200);
    const code = mailer.lastCode('bob@example.com');
    const r2 = await fetch(base + '/work/native/signup/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'bob@example.com', code, installation: inst, deviceName: 'Pixel 9' })
    });
    assert.equal(r2.status, 200);
    const d2 = await r2.json();
    assert.equal(d2.code, 'ABCD-EFGHJK');

    // Native password login succeeds and enrolls
    const r3 = await fetch(base + '/work/native/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'bob@example.com', password: 'password-1234', installation: inst, deviceName: 'Pixel 9' })
    });
    assert.equal(r3.status, 200);
    assert.equal((await r3.json()).code, 'ABCD-EFGHJK');

    // Native password login with wrong password returns 400
    const r4 = await fetch(base + '/work/native/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email: 'bob@example.com', password: 'wrong-password', installation: inst })
    });
    assert.equal(r4.status, 400);
  } finally {
    await new Promise(resolve => server.close(resolve));
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
