import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createConnections} from '../connections.mjs';

const ownerId='owner_'+'A'.repeat(43),deviceId='phone_'+'a'.repeat(32);
function setup(t,callCounter){
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'bs-counter-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  fs.mkdirSync(path.join(dir,'phones'),{recursive:true});
  fs.writeFileSync(path.join(dir,'devices.json'),JSON.stringify({[deviceId]:{ownerId,name:'Pixel',createdAt:1,linkedAt:1,needsReconnect:false}}));
  fs.writeFileSync(path.join(dir,'phones',deviceId+'.json'),JSON.stringify({relayUrl:'https://relay.example.com',apiKey:'bsc_x',device:'Pixel',agentId:'agt_1234567890abcdef',
    links:{Pixel:{deviceId:'dev_1',linkKey:crypto.randomBytes(32).toString('base64url'),callCounter,lastResultCounter:'0'}}}));
  return dir;
}
// Stands in for encryptedCall: number the call through the gateway's link hooks, like sealCall does.
const numbering=used=>async cfg=>{const cur=cfg.readConfig(cfg.filePath);const n=BigInt(cur.links.Pixel.callCounter);used.push(n);cur.links.Pixel.callCounter=String(n+1n);cfg.writeConfig(cfg.filePath,cur);return {outcome:'succeeded'};};

test('a restart never reuses call numbers that were not saved before it',async t=>{
  const dir=setup(t,String(Date.now()-60_000)),used=[];
  let c=createConnections({directory:dir,relayUrl:'https://relay.example.com',flushMs:60_000,call:numbering(used)});
  const grantId=c.authorize(ownerId,'client-1','Claude','all');
  await c.call('android.shell',{command:'true'},grantId);
  c.flushSync();
  // Calls still running at shutdown take numbers after the final save.
  await c.call('android.shell',{command:'true'},grantId);
  await c.call('android.shell',{command:'true'},grantId);
  c=createConnections({directory:dir,relayUrl:'https://relay.example.com',flushMs:60_000,call:numbering(used)});
  await c.call('android.shell',{command:'true'},grantId);
  assert.equal(new Set(used).size,used.length,'every call number is used once');
  assert.ok(used.at(-1)>used.at(-2));
});

test('a refused repeated call number does not mark the link removed, and the next call skips past it',async t=>{
  const dir=setup(t,String(Date.now()+10_000_000)),used=[];let refuse=true;
  const c=createConnections({directory:dir,relayUrl:'https://relay.example.com',call:async(cfg,...rest)=>{
    if(refuse){refuse=false;const cur=cfg.readConfig(cfg.filePath);used.push(BigInt(cur.links.Pixel.callCounter));cur.links.Pixel.callCounter=String(BigInt(cur.links.Pixel.callCounter)+1n);cfg.writeConfig(cfg.filePath,cur);throw Object.assign(new Error('unauthorized: replay'),{inputDelivered:null});}
    return numbering(used)(cfg,...rest);}});
  const grantId=c.authorize(ownerId,'client-1','Claude','all');
  await assert.rejects(c.call('android.shell',{command:'true'},grantId),e=>e.code==='uncertain'&&!/removed|reconnect/i.test(e.message));
  assert.equal(c.devices(ownerId)[0].needsReconnect,false);
  await c.call('android.shell',{command:'true'},grantId);
  assert.ok(used[1]>=used[0]+1024n,'the next call jumps past the phone\'s replay window');
});

test('a link the phone really removed still asks for reconnect',async t=>{
  const dir=setup(t,String(Date.now()));
  const c=createConnections({directory:dir,relayUrl:'https://relay.example.com',call:async()=>{throw Object.assign(new Error('unauthorized: unknown agent'),{inputDelivered:null});}});
  const grantId=c.authorize(ownerId,'client-1','Claude','all');
  await assert.rejects(c.call('android.shell',{command:'true'},grantId),e=>e.code==='device_link_revoked');
  assert.equal(c.devices(ownerId)[0].needsReconnect,true);
});
