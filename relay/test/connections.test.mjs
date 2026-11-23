import test from 'node:test';
import assert from 'node:assert/strict';
import {withRelay,httpJson,deviceHeaders,apiHeaders,pairSession,fakeEnvelope} from './helpers.mjs';
import {beginConnection} from '../../cli/connect.mjs';
import {Spake2,ALICE_NAME,BOB_NAME} from '../../cli/spake2.mjs';
import {linkKey,confirmB,confirmA,b64url,unb64url} from '../../cli/crypto.mjs';

import {approve} from './connection-helper.mjs';

test('keyless setup proves phone possession and isolates two phones and sibling connections',()=>withRelay(async({url,apiKey})=>{
  const a=await beginConnection({relayUrl:url});
  const b=await beginConnection({relayUrl:url});
  const A=await approve(url,a), B=await approve(url,b);
  assert.notEqual(A.claim.deviceId,B.claim.deviceId);
  const sibling=await approve(url,await beginConnection({relayUrl:url}),A.claim.deviceToken);
  assert.equal(sibling.claim.deviceId,A.claim.deviceId);
  for(const c of [A,B,sibling]) {
    const headers=apiHeaders(c.config.apiKey);
    const devices=await httpJson(url+'/v1/devices',{headers});
    assert.deepEqual(devices.body.devices.map(d=>d.id),[c.claim.deviceId]);
    assert.equal((await httpJson(url+'/v1/pair/session',{method:'POST',headers,body:{name:'bad'}})).status,401);
    assert.equal((await httpJson(url+'/v1/devices/'+c.claim.deviceId,{method:'DELETE',headers})).status,401);
  }
  const cross=await httpJson(url+'/v1/call',{method:'POST',headers:apiHeaders(A.config.apiKey),body:{device:B.claim.deviceId,envelope:fakeEnvelope(A.config.agentId)}});
  assert.equal(cross.status,403);
  const wrongAgent=await httpJson(url+'/v1/call',{method:'POST',headers:apiHeaders(A.config.apiKey),body:{device:A.claim.deviceId,envelope:fakeEnvelope(sibling.config.agentId)}});
  assert.equal(wrongAgent.status,403);
  const legacy=await pairSession(url,apiKey);
  assert.ok(legacy.claim.deviceId);
}));

test('pair session token cannot authorize another session or mutate the agent message',()=>withRelay(async({url})=>{
  const a=(await httpJson(url+'/v1/pair/connect',{method:'POST',body:{}})).body;
  const b=(await httpJson(url+'/v1/pair/connect',{method:'POST',body:{}})).body;
  const headers=apiHeaders(a.token);
  assert.equal((await httpJson(url+'/v1/devices',{headers})).status,401);
  assert.equal((await httpJson(`${url}/v1/pair/session/${b.sessionId}/agent`,{method:'PUT',headers,body:{agentId:'agt_a',msg:'test'}})).status,401);
  const route=`${url}/v1/pair/session/${a.sessionId}/agent`;
  assert.equal((await httpJson(route,{method:'PUT',headers,body:{agentId:'agt_a',msg:'test'}})).status,200);
  assert.equal((await httpJson(route,{method:'PUT',headers,body:{agentId:'agt_b',msg:'test'}})).status,409);
  assert.equal((await httpJson(`${url}/v1/pair/session/${a.sessionId}/confirm`,{method:'PUT',headers,body:{confirm:'test'}})).status,409);
}));

test('phone can revoke one assistant without affecting its other connections',()=>withRelay(async({url})=>{
  const A=await approve(url,await beginConnection({relayUrl:url}));
  const B=await approve(url,await beginConnection({relayUrl:url}),A.claim.deviceToken);
  const revoke=()=>httpJson(url+'/v1/device/revoke-connection',{method:'POST',headers:deviceHeaders(A.claim.deviceToken),body:{agentId:A.config.agentId}});
  assert.equal((await revoke()).status,200);assert.equal((await revoke()).status,200);
  assert.equal((await httpJson(url+'/v1/devices',{headers:apiHeaders(A.config.apiKey)})).status,401);
  assert.equal((await httpJson(url+'/v1/devices',{headers:apiHeaders(B.config.apiKey)})).status,200);
}));

test('wrong phone proof cancels setup and never enables a connection',()=>withRelay(async({url})=>{
 const c=await beginConnection({relayUrl:url});
 const session=c.code.split('-')[0],bob=new Spake2('bob',BOB_NAME,ALICE_NAME);
 const claim=await httpJson(url+'/v1/pair/session/'+session+'/device',{method:'POST',body:{msg:b64url(bob.generateMsg(Buffer.from('WRNG-WRNGWR')))}});
 const key=linkKey(bob.processMsg(unb64url(claim.body.agentMsg)));
 await httpJson(url+'/v1/pair/session/'+session+'/device-confirm',{method:'PUT',headers:deviceHeaders(claim.body.deviceToken),body:{confirm:b64url(confirmB(key))}});
 await assert.rejects(c.poll(),/code did not match/);
 assert.equal((await httpJson(url+'/v1/pair/session/'+session+'/confirm',{headers:deviceHeaders(claim.body.deviceToken)})).status,401);
}));

test('confirmation response loss can retry safely without deriving a second link',()=>withRelay(async({url})=>{
 let dropped=false;
 const c=await beginConnection({relayUrl:url,fetchImpl:async(u,opts)=>{
   const response=await fetch(u,opts);
   if(u.endsWith('/confirm')&&opts.method==='PUT'&&!dropped){dropped=true;throw new Error('lost response');}
   return response;
 }});
 const session=c.code.split('-')[0],bob=new Spake2('bob',BOB_NAME,ALICE_NAME);
 const claim=await httpJson(url+'/v1/pair/session/'+session+'/device',{method:'POST',body:{msg:b64url(bob.generateMsg(Buffer.from(c.code)))}});
 const key=linkKey(bob.processMsg(unb64url(claim.body.agentMsg)));
 await httpJson(url+'/v1/pair/session/'+session+'/device-confirm',{method:'PUT',headers:deviceHeaders(claim.body.deviceToken),body:{confirm:b64url(confirmB(key))}});
 await assert.rejects(c.poll(),/lost response/);
 const cfg=await c.poll();assert.equal(cfg.links[cfg.device].linkKey,b64url(key));
 assert.equal((await httpJson(url+'/v1/devices',{headers:apiHeaders(cfg.apiKey)})).status,200);
}));
