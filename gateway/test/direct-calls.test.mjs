import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createConnections} from '../connections.mjs';

test('gateway calls use the phone direct route when it answers, the relay on loopback, and record the route', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'backstage-direct-calls-'));
  t.after(() => fs.rmSync(directory, {recursive: true, force: true}));
  const ownerId = 'owner_' + 'a'.repeat(43), deviceId = 'phone_' + 'b'.repeat(32);
  fs.mkdirSync(path.join(directory, 'phones'), {recursive: true});
  fs.writeFileSync(path.join(directory, 'devices.json'), JSON.stringify({[deviceId]: {ownerId, name: 'Redmagic', createdAt: 1, linkedAt: 1}}));
  fs.writeFileSync(path.join(directory, 'phones', deviceId + '.json'), JSON.stringify({
    relayUrl: 'https://relay.example.com', apiKey: 'k', device: 'phone', agentId: 'agt_x', links: {phone: {deviceId: 'dev_1', linkKey: 'AA'}}}));
  const fetched = [], calls = [], routed = [];
  const fetchImpl = async url => {
    fetched.push(url);
    return {status: 200, ok: true, json: async () => ({devices: [{id: 'dev_1', online: false, lanAddresses: ['100.64.0.10', '192.0.0.2']}]})};
  };
  const directRoutes = {
    route: async (key, addresses, expected) => { routed.push([key, addresses, expected]); return 'http://100.64.0.10:18422'; },
    failed: key => routed.push(['failed', key]),
  };
  const call = async (cfg, name, args, options) => { calls.push({cfg, name, args, options}); return {ok: true}; };
  const connections = createConnections({directory, relayUrl: 'https://relay.example.com/', relayInternalUrl: 'http://127.0.0.1:18420', directRoutes, call, fetchImpl});
  const grant = connections.authorize(ownerId, 'client', 'Claude', 'all');

  const status = await connections.status(grant);
  assert.equal(status[0].online, true, 'relay says offline but the phone answers directly');
  assert.equal(status[0].route, 'direct');
  assert.equal(fetched[0], 'http://127.0.0.1:18420/v1/devices', 'relay reached on loopback');

  await connections.call('android.shell', {command: 'id'}, grant);
  const sent = calls.at(-1);
  assert.equal(sent.options.directUrl, 'http://100.64.0.10:18422');
  assert.equal(sent.cfg.transportRelayUrl, 'http://127.0.0.1:18420');
  assert.equal(sent.cfg.relayUrl, 'https://relay.example.com', 'the saved link keeps its public relay');
  assert.deepEqual(routed.at(-1), [deviceId, ['100.64.0.10', '192.0.0.2'], 'dev_1']);
  assert.equal(connections.routeOf(deviceId), 'direct');

  sent.options.onRoute('relay', 'direct failed: ECONNREFUSED');
  assert.equal(connections.routeOf(deviceId), 'relay_after_direct');
  assert.deepEqual(routed.at(-1), ['failed', deviceId]);

  // A link that points at some other relay is used as saved.
  const other = createConnections({directory, relayUrl: 'https://another.example.com', relayInternalUrl: 'http://127.0.0.1:18420', directRoutes: null, call, fetchImpl});
  await other.call('android.shell', {command: 'id'}, grant);
  assert.equal(calls.at(-1).cfg.transportRelayUrl, null);
  assert.equal(calls.at(-1).options.directUrl, null);
});
