import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createOwners } from '../owners.mjs';
import { createBilling } from '../billing.mjs';
import { createWorkApp } from '../server.mjs';
import { createVault } from '../vault.mjs';
import { totpNow, verifyTotp } from '../totp.mjs';

function cookieJar() {
  const map = new Map();
  return {
    capture(res) {
      for (const h of res.headers.getSetCookie?.() || []) {
        const [pair, ...attrs] = h.split(';').map(s => s.trim());
        const eq = pair.indexOf('=');
        if (eq <= 0) continue;
        const name = pair.slice(0, eq), val = pair.slice(eq + 1);
        const maxAgeZero = attrs.some(a => /^max-age=0$/i.test(a));
        if (!val || maxAgeZero) map.delete(name);
        else map.set(name, val);
      }
    },
    header() {
      return [...map.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
    },
    has(name) {
      return map.has(name);
    },
    clear() {
      map.clear();
    },
  };
}

test('TOTP MFA enforces 2-step web & native login, blocks replay, seals secrets in vault, and supports recovery codes', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bs-mfa-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  let clock = Date.UTC(2026, 10, 24, 18, 0, 0);
  const sent = [];
  const mailer = { configured: () => true, send: async (to, sub, body) => sent.push({ to, sub, body }) };
  const vault = createVault({ key: crypto.randomBytes(32) });
  const connections = {
    devices: () => [],
    probe: async d => d,
    list: () => [],
    async begin({ name }) { return { code: 'ABCD-EFGHJK', deviceName: name || 'phone', async poll() { return 'dev_1'; }, async cancel() {} }; },
  };
  const billing = createBilling({ secretKey: '', webhookSecret: '' });
  const server4 = await new Promise(resolve => {
    const tmpSrv = createWorkApp({
      base: 'http://127.0.0.1/work',
      stateFile: path.join(dir, 'o4.json'),
      connections,
      owners: createOwners({ stateFile: path.join(dir, 'owners4.json'), origin: 'http://127.0.0.1', mailer, vault, now: () => clock }),
      billing,
      enforcePlans: false,
      now: () => clock,
    }).listen(0, '127.0.0.1', () => resolve(tmpSrv));
  });
  t.after(() => server4.close());
  const boundBase = `http://127.0.0.1:${server4.address().port}`;
  const jar = cookieJar();
  const post = async (p, body) => {
    const r = await fetch(`${boundBase}${p}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', origin: 'http://127.0.0.1', ...(jar.header() ? { cookie: jar.header() } : {}) },
      body: JSON.stringify(body),
    });
    jar.capture(r);
    return { status: r.status, body: await r.json() };
  };

  // 1. Sign up -> gets 7-day Pro trial
  assert.equal((await post('/work/signup/start', { email: 'mfa@example.com', password: 'password-1234' })).status, 200);
  const signupCode = /\b(\d{6})\b/.exec(sent[sent.length - 1].body)[1];
  assert.equal((await post('/work/signup/verify', { email: 'mfa@example.com', code: signupCode })).status, 200);
  assert.equal(jar.has('__Host-backstage-owner'), true);

  // 2. Enable TOTP MFA -> generates QR + Base32 secret -> confirm auto-creates 10 recovery codes
  const setup = await post('/work/account/mfa/setup', {});
  assert.equal(setup.status, 200);
  assert.match(setup.body.secret, /^[A-Z2-7]{32}$/);
  assert.match(setup.body.qrSvg, /<svg/);

  const liveCode = totpNow(setup.body.secret, clock);
  assert.equal(verifyTotp(setup.body.secret, liveCode, { now: clock }).ok, true);
  const confirmed = await post('/work/account/mfa/confirm', { code: liveCode });
  assert.equal(confirmed.status, 200);
  assert.equal(confirmed.body.enabled, true);
  assert.equal(confirmed.body.recoveryCodes.length, 10);

  // Verify TOTP secret is sealed in owners4.json (not stored plaintext!)
  const rawDisk = fs.readFileSync(path.join(dir, 'owners4.json'), 'utf8');
  assert.equal(rawDisk.includes(setup.body.secret), false);

  // 3. Sign out and log back in -> Step 1 returns mfaRequired without session cookie!
  await post('/work/account/sign-out', {});
  jar.clear();
  const step1 = await post('/work/login', { email: 'mfa@example.com', password: 'password-1234' });
  assert.equal(step1.status, 200);
  assert.equal(step1.body.mfaRequired, true);
  assert.equal(jar.has('__Host-backstage-owner'), false);
  assert.equal(jar.has('__Host-backstage-mfa'), true);

  // Replaying the same 30s TOTP code used during setup is rejected!
  const replay = await post('/work/login/mfa', { code: liveCode });
  assert.equal(replay.status, 400);

  // Advance 30s -> fresh TOTP code completes login and issues session cookie!
  clock += 30_000;
  const nextCode = totpNow(setup.body.secret, clock);
  const step2 = await post('/work/login/mfa', { code: nextCode });
  assert.equal(step2.status, 200);
  assert.equal(jar.has('__Host-backstage-owner'), true);
  assert.equal(jar.has('__Host-backstage-mfa'), false);

  // 4. Native login requires mfaCode when MFA is enabled
  const inst = 'b'.repeat(43);
  const nativeNoMfa = await post('/work/native/login', { email: 'mfa@example.com', password: 'password-1234', installation: inst });
  assert.equal(nativeNoMfa.status, 401);
  assert.equal(nativeNoMfa.body.mfaRequired, true);

  clock += 30_000;
  const nativeWithMfa = await post('/work/native/login', {
    email: 'mfa@example.com',
    password: 'password-1234',
    installation: inst,
    mfaCode: totpNow(setup.body.secret, clock),
  });
  assert.equal(nativeWithMfa.status, 200);
  assert.equal(nativeWithMfa.body.code, 'ABCD-EFGHJK');

  // 5. Recovery code also satisfies Step 2 MFA and can disable MFA
  const recCode = confirmed.body.recoveryCodes[0];
  const disabled = await post('/work/account/mfa/disable', { code: recCode });
  assert.equal(disabled.status, 200);
  assert.equal(disabled.body.enabled, false);
});

test('Stripe billing creates $4.50/mo promo checkout, verifies webhook HMAC, locks promo, and downgrades on cancel', async (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bs-bill-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));

  let clock = Date.UTC(2026, 10, 24, 18, 0, 0);
  const sent = [];
  const mailer = { configured: () => true, send: async (to, sub, body) => sent.push({ to, sub, body }) };
  const owners = createOwners({ stateFile: path.join(dir, 'owners.json'), origin: 'http://127.0.0.1', mailer, now: () => clock });
  await owners.startSignUp('buyer@example.com', 'password-1234');
  const code = /\b(\d{6})\b/.exec(sent[0].body)[1];
  const ownerId = owners.verifySignUp('buyer@example.com', code);

  const stripeCalls = [];
  const billing = createBilling({
    secretKey: 'sk_test_fake',
    webhookSecret: 'whsec_test_secret',
    priceProMonthly: 'price_pro_9',
    couponPro50: 'coupon_launch50',
    priceProYearly: 'price_pro_84',
    priceTeamMonthly: 'price_team_39',
    now: () => clock,
    fetchImpl: async (url, init) => {
      stripeCalls.push({ url, body: init.body });
      return new Response(JSON.stringify({ id: 'cs_test_1', url: 'https://checkout.stripe.com/c/pay/cs_test_1' }), { status: 200 });
    },
  });

  // Checkout includes 50% forever coupon + launch50_lifetime metadata
  const session = await billing.createCheckoutSession({
    ownerId,
    email: 'buyer@example.com',
    offer: 'pro_monthly_promo',
    successUrl: 'https://relay.android-backstage.com/work/account?billing=success',
    cancelUrl: 'https://relay.android-backstage.com/work/account?billing=cancelled',
  });
  assert.equal(session.url, 'https://checkout.stripe.com/c/pay/cs_test_1');
  const params = new URLSearchParams(stripeCalls[0].body);
  assert.equal(params.get('discounts[0][coupon]'), 'coupon_launch50');
  assert.equal(params.get('metadata[promo]'), 'launch50_lifetime');

  // Yearly checkout also stacks the 50% lifetime launch coupon on top of the yearly price ($84 -> $42/yr)
  await billing.createCheckoutSession({
    ownerId,
    email: 'buyer@example.com',
    offer: 'pro_yearly',
    successUrl: 'https://relay.android-backstage.com/work/account?billing=success',
    cancelUrl: 'https://relay.android-backstage.com/work/account?billing=cancelled',
  });
  const yearlyParams = new URLSearchParams(stripeCalls[1].body);
  assert.equal(yearlyParams.get('line_items[0][price]'), 'price_pro_84');
  assert.equal(yearlyParams.get('discounts[0][coupon]'), 'coupon_launch50');
  assert.equal(yearlyParams.get('metadata[promo]'), 'launch50_lifetime');

  const app = createWorkApp({
    base: 'http://127.0.0.1/work',
    stateFile: path.join(dir, 'oauth.json'),
    connections: { devices: () => [], probe: async d => d, list: () => [] },
    owners,
    billing,
    now: () => clock,
  });
  const srv = await new Promise(resolve => { const s = app.listen(0, '127.0.0.1', () => resolve(s)); });
  t.after(() => srv.close());
  const base = `http://127.0.0.1:${srv.address().port}`;

  const signStripe = (payload) => {
    const ts = Math.floor(clock / 1000);
    const sig = crypto.createHmac('sha256', 'whsec_test_secret').update(`${ts}.${payload}`).digest('hex');
    return `t=${ts},v1=${sig}`;
  };

  // Bad signature rejected
  const badPayload = JSON.stringify({ type: 'checkout.session.completed', data: { object: {} } });
  const badRes = await fetch(`${base}/work/billing/webhook`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'stripe-signature': 't=1,v1=deadbeef' },
    body: badPayload,
  });
  assert.equal(badRes.status, 400);

  // Valid checkout.session.completed activates Pro and locks $4.50/mo promo
  const okPayload = JSON.stringify({
    type: 'checkout.session.completed',
    data: {
      object: {
        mode: 'subscription',
        customer: 'cus_123',
        subscription: 'sub_123',
        client_reference_id: ownerId,
        metadata: { ownerId, plan: 'pro', promo: 'launch50_lifetime' },
      },
    },
  });
  const okRes = await fetch(`${base}/work/billing/webhook`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'stripe-signature': signStripe(okPayload) },
    body: okPayload,
  });
  assert.equal(okRes.status, 200);
  assert.equal(owners.planOf(ownerId), 'pro');
  assert.equal(owners.methods(ownerId).basePlan, 'pro');
  assert.equal(owners.methods(ownerId).billing.promoLocked, true);

  // Advancing past 7 days sends ZERO trial reminders once subscribed!
  clock += 8 * 86400_000;
  assert.equal((await owners.processTrialReminders()).sent, 0);
  assert.equal(owners.planOf(ownerId), 'pro');

  // Subscription deletion downgrades back to Personal (free) and ends the lifetime promo lock
  const delPayload = JSON.stringify({
    type: 'customer.subscription.deleted',
    data: { object: { id: 'sub_123', customer: 'cus_123', status: 'canceled', metadata: { ownerId, plan: 'pro' } } },
  });
  const delRes = await fetch(`${base}/work/billing/webhook`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'stripe-signature': signStripe(delPayload) },
    body: delPayload,
  });
  assert.equal(delRes.status, 200);
  assert.equal(owners.planOf(ownerId), 'free');
  assert.equal(owners.methods(ownerId).billing.promoLocked, false);
});
