import { test } from "node:test";
import assert from "node:assert/strict";
import { createQueue, MAX_REDELIVERIES } from "../queue.mjs";
import { withRelay, pairSession, hello, httpJson, apiHeaders, deviceHeaders, fakeEnvelope, delay } from "./helpers.mjs";

const quiet = { log() {} };
const receiptQueue = () => { const q = createQueue({ receiptWindowMs: 50, log: quiet }); q.setReceipts('d', true); return q; };

test('a reply lost on the way to the phone is delivered again and one result finishes it', async () => {
  const q = receiptQueue(); try {
    q.enqueue('d', { id: 'lost', envelope: {}, expiresAt: Date.now() + 5000 });
    assert.equal((await q.waitForJob('d', 1)).id, 'lost');
    assert.equal(await q.waitForJob('d', 1), null, 'not again inside the window');
    await delay(70);
    assert.equal((await q.waitForJob('d', 1)).id, 'lost', 'no receipt: sent again');
    assert.equal(q.get('lost').redeliveries, 1);
    assert.equal(q.ack('lost', 'd'), true);
    assert.equal(q.complete('lost', { envelope: {} }, 'd'), true);
    assert.equal(q.get('lost').state, 'complete');
    assert.equal(q.complete('lost', { envelope: {} }, 'd'), true, 'the same result again is accepted');
  } finally { q.close(); }
});

test('a job the phone confirmed is not delivered again', async () => {
  const q = receiptQueue(); try {
    q.enqueue('d', { id: 'acked', envelope: {}, expiresAt: Date.now() + 5000 });
    assert.equal((await q.waitForJob('d', 1)).id, 'acked');
    assert.equal(q.ack('acked', 'other'), false, 'only the owning device');
    assert.equal(q.ack('acked', 'd'), true);
    await delay(70);
    assert.equal(await q.waitForJob('d', 1), null);
    assert.equal(q.get('acked').state, 'running');
  } finally { q.close(); }
});

test('a result for a job queued again still finishes it, and a late receipt takes it back', async () => {
  const q = receiptQueue(); try {
    q.enqueue('d', { id: 'slow', envelope: {}, expiresAt: Date.now() + 5000 });
    q.enqueue('d', { id: 'late', envelope: {}, expiresAt: Date.now() + 5000 });
    await q.waitForJob('d', 1);
    await delay(70);
    assert.equal(q.get('slow').state, 'queued');
    assert.equal(q.complete('slow', { envelope: {} }, 'd'), true);
    assert.equal(q.get('slow').state, 'complete');
    q.setLanes('d', 2);
    await q.waitForJob('d', 1);
    await delay(70);
    assert.equal(q.get('late').state, 'queued');
    assert.equal(q.ack('late', 'd'), true);
    assert.equal(q.get('late').state, 'running');
    assert.equal(await q.waitForJob('d', 1), null, 'a confirmed job is not sent again');
  } finally { q.close(); }
});

test('no delivery again after the deadline, and at most a few times', async () => {
  const q = receiptQueue(); try {
    q.enqueue('d', { id: 'late', envelope: {}, expiresAt: Date.now() + 40 });
    await q.waitForJob('d', 1);
    await delay(70);
    assert.equal(await q.waitForJob('d', 1), null);
    assert.equal(q.get('late').state, 'unknown');
    q.enqueue('d', { id: 'gone', envelope: {}, expiresAt: Date.now() + 5000 });
    for (let i = 0; i <= MAX_REDELIVERIES; i++) { assert.equal((await q.waitForJob('d', 1))?.id, 'gone'); await delay(70); }
    assert.equal(await q.waitForJob('d', 1), null, 'gives up after the last try');
    assert.equal(q.get('gone').state, 'running', 'the deadline then decides');
  } finally { q.close(); }
});

test('a queued job that already went out is not called never-sent', async () => {
  const q = receiptQueue(); try {
    q.enqueue('d', { id: 'out', envelope: {}, expiresAt: Date.now() + 5000 });
    await q.waitForJob('d', 1);
    await delay(70); q.get('out');
    assert.equal(q.cancel('out').cancelRequested, true, 'cancel cannot stop it');
    assert.equal(await q.waitForJob('d', 1), null, 'a cancelled job is not sent again');
    q.enqueue('d', { id: 'stop', envelope: {}, expiresAt: Date.now() + 5000 });
    q.setLanes('d', 2);
    await q.waitForJob('d', 1);
    q.cancel('stop');
    await delay(70);
    assert.equal(await q.waitForJob('d', 1), null, 'cancelled inside the window: not sent again');
    q.recoverDevice('d');
    assert.equal(q.get('out').state, 'unknown', 'a new daemon may have run it');
  } finally { q.close(); }
});

test('a poll already waiting gets the job when its receipt window ends', async () => {
  const q = receiptQueue(); try {
    q.setLanes('d', 2);
    q.enqueue('d', { id: 'wait', envelope: {}, expiresAt: Date.now() + 5000 });
    assert.equal((await q.waitForJob('d', 1)).id, 'wait');
    assert.equal((await q.waitForJob('d', 3000))?.id, 'wait', 'the sweeper wakes the open poll');
  } finally { q.close(); }
});

test('a phone that sends no receipts gets each job once', async () => {
  const q = createQueue({ receiptWindowMs: 50, log: quiet }); try {
    q.enqueue('d', { id: 'old', envelope: {}, expiresAt: Date.now() + 5000 });
    await q.waitForJob('d', 1);
    await delay(70);
    assert.equal(await q.waitForJob('d', 1), null);
    assert.equal(q.get('old').state, 'running');
  } finally { q.close(); }
});

test('over HTTP: a phone that says it sends receipts gets an unconfirmed job again; /ack stops it', async () => {
  await withRelay({ pollMs: 150, receiptWindowMs: 100 }, async ({ url, apiKey }) => {
    const { claim } = await pairSession(url, apiKey);
    const phone = deviceHeaders(claim.deviceToken);
    const submit = id => httpJson(`${url}/v1/jobs`, { method: 'POST', headers: apiHeaders(apiKey), body: { id, device: 'phone', envelope: fakeEnvelope(), timeoutMs: 5000 } });
    const next = () => httpJson(`${url}/v1/device/next`, { method: 'POST', headers: phone, body: {} });

    await hello(url, claim.deviceToken);
    await submit('job-old-phone-0001');
    assert.equal((await next()).body.id, 'job-old-phone-0001');
    await delay(150);
    assert.equal((await next()).body.idle, true, 'no receipt flag: sent once');
    await httpJson(`${url}/v1/device/result`, { method: 'POST', headers: phone, body: { id: 'job-old-phone-0001', envelope: fakeEnvelope(claim.deviceId) } });

    await hello(url, claim.deviceToken, { model: 'Pixel', sdk: 34, agentVersion: '2.0.9', receipts: true });
    await submit('job-lost-reply-01');
    assert.equal((await next()).body.id, 'job-lost-reply-01');
    await delay(150);
    assert.equal((await next()).body.id, 'job-lost-reply-01', 'sent again');
    assert.equal((await httpJson(`${url}/v1/device/ack`, { method: 'POST', headers: phone, body: { id: 'job-lost-reply-01' } })).status, 200);
    await delay(150);
    assert.equal((await next()).body.idle, true, 'confirmed: not sent again');
    assert.equal((await httpJson(`${url}/v1/device/ack`, { method: 'POST', headers: phone, body: { id: 'job-nope-000001' } })).status, 404);
    await httpJson(`${url}/v1/device/result`, { method: 'POST', headers: phone, body: { id: 'job-lost-reply-01', envelope: fakeEnvelope(claim.deviceId) } });
    assert.equal((await httpJson(`${url}/v1/jobs/job-lost-reply-01`, { headers: apiHeaders(apiKey) })).body.state, 'complete');
  });
});
