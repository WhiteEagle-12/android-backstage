import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDirectRoutes, directCandidates, overTailnet } from '../direct-routes.mjs';

const nics = { wlan0: [{ family: 'IPv4', internal: false, address: '10.20.30.197', netmask: '255.255.254.0' }],
  tailscale0: [{ family: 'IPv4', internal: false, address: '100.64.0.1', netmask: '255.255.255.255' }],
  lo: [{ family: 'IPv4', internal: true, address: '127.0.0.1', netmask: '255.0.0.0' }] };

test('direct candidates: tailnet first, then a LAN this computer shares; never cellular or foreign LANs', () => {
  assert.deepEqual(directCandidates(['10.20.30.150', '192.0.0.2', '100.64.0.10', '192.168.1.20', 'x', 5], nics),
    ['100.64.0.10', '10.20.30.150']);
  assert.deepEqual(directCandidates(undefined, nics), []);
  assert.deepEqual(directCandidates(['100.128.0.1', '100.63.255.1'], nics), [], 'outside 100.64.0.0/10 is not the tailnet');
});

test('direct routes probe for the right phone, cache success and failure, and drop after a failed call', async () => {
  let clock = 0, probes = [];
  const answers = new Map([['100.64.0.10', { ok: true, service: 'backstage', deviceId: 'dev_a' }]]);
  const fetchImpl = async (url) => {
    probes.push(url);
    const host = new URL(url).hostname;
    if (!answers.has(host)) throw new Error('ECONNREFUSED');
    return { ok: true, json: async () => answers.get(host) };
  };
  const routes = createDirectRoutes({ fetchImpl, now: () => clock, interfaces: () => nics, okMs: 1000, failMs: 500 });
  assert.equal(await routes.route('phone_1', ['10.20.30.150', '100.64.0.10'], 'dev_a'), 'http://100.64.0.10:18422');
  assert.equal(probes.length, 1, 'tailnet address tried first');
  await routes.route('phone_1', ['100.64.0.10'], 'dev_a');
  assert.equal(probes.length, 1, 'cached');

  routes.failed('phone_1');
  assert.equal(await routes.route('phone_1', ['100.64.0.10'], 'dev_a'), null, 'relay after a failed direct call');
  clock = 600;
  assert.equal(await routes.route('phone_1', ['100.64.0.10'], 'dev_a'), 'http://100.64.0.10:18422', 'probed again later');

  // A different phone now holds the address: never route there.
  answers.set('100.64.0.10', { ok: true, service: 'backstage', deviceId: 'dev_b' });
  clock = 5000;
  assert.equal(await routes.route('phone_1', ['100.64.0.10'], 'dev_a'), null);
  // Older phones do not report their device ID; the encrypted call still checks it.
  answers.set('100.64.0.10', { ok: true, service: 'backstage' });
  clock = 10000;
  assert.equal(await routes.route('phone_1', ['100.64.0.10'], 'dev_a'), 'http://100.64.0.10:18422');
  answers.set('100.64.0.10', { ok: true, service: 'device-agent' });
  clock = 20000;
  assert.equal(await routes.route('phone_1', ['100.64.0.10'], 'dev_a'), null, 'another service on the port');
});

test('direct file delivery needs a tailnet route: a phone reached over plain LAN cannot reach this computer\'s tailnet address', () => {
  assert.equal(overTailnet('http://100.64.0.10:18422'), true);
  assert.equal(overTailnet('http://10.20.30.84:18422'), false);
  assert.equal(overTailnet('http://100.128.0.1:18422'), false);
  assert.equal(overTailnet(null), false);
});

test('concurrent direct route probes deduplicate in-flight requests per device', async () => {
  let probes = 0;
  const fetchImpl = async () => {
    probes++;
    await new Promise(r => setTimeout(r, 30));
    return { ok: true, json: async () => ({ ok: true, service: 'backstage', deviceId: 'dev_a' }) };
  };
  const routes = createDirectRoutes({ fetchImpl, interfaces: () => nics });
  const [a, b, c] = await Promise.all([
    routes.route('phone_1', ['100.64.0.10'], 'dev_a'),
    routes.route('phone_1', ['100.64.0.10'], 'dev_a'),
    routes.route('phone_1', ['100.64.0.10'], 'dev_a'),
  ]);
  assert.equal(a, 'http://100.64.0.10:18422');
  assert.equal(b, a);
  assert.equal(c, a);
  assert.equal(probes, 1, 'three concurrent callers share one probe');
});
