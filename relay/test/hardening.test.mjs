import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import {createQueue} from '../queue.mjs';
import {withRelay,pairSession,hello,httpJson,apiHeaders,deviceHeaders,fakeEnvelope,delay} from './helpers.mjs';

test('expired and cancelled jobs cannot be delivered',async()=>{
 const q=createQueue();try{
 q.enqueue('d',{id:'expired',envelope:{},expiresAt:Date.now()-1});
 assert.equal(await q.waitForJob('d',1),null);
 q.enqueue('d',{id:'cancelled',envelope:{}});q.cancel('cancelled');assert.equal(await q.waitForJob('d',1),null);
 }finally{q.close();}
});
test('durable ledger retains queued work, marks claimed work unknown, binds results',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-jobs-'));
 let q=createQueue({dataDir:dir});q.enqueue('a',{id:'claimed',envelope:{},accountId:'owner'});await q.waitForJob('a',1);
 q.enqueue('a',{id:'queued',envelope:{},accountId:'owner'});q.close();q=createQueue({dataDir:dir});
 try{assert.equal(q.get('claimed').state,'unknown');assert.equal(q.get('queued').state,'queued');assert.equal(q.complete('claimed',{error:'x'},'b'),false);assert.equal(q.complete('claimed',{error:'x'},'a'),true);}finally{q.close();fs.rmSync(dir,{recursive:true,force:true});}
});
test('admin secret is discarded, async jobs poll, and uploads resume with ownership checks',async()=>{
 await withRelay(async({relay,url,apiKey})=>{
  const d=await pairSession(url,apiKey);assert.equal(relay.store.snapshot().devices[d.claim.deviceId].localToken,undefined);
  await hello(url,d.claim.deviceToken);
  const r=await httpJson(url+'/v1/jobs',{method:'POST',headers:apiHeaders(apiKey),body:{id:'job-long-identifier',device:'phone',envelope:fakeEnvelope(),timeoutMs:5000}});assert.equal(r.status,202);
  const poll=await httpJson(url+'/v1/device/next',{method:'POST',headers:deviceHeaders(d.claim.deviceToken),body:{}});assert.equal(poll.body.id,'job-long-identifier');
  await httpJson(url+'/v1/device/result',{method:'POST',headers:deviceHeaders(d.claim.deviceToken),body:{id:poll.body.id,envelope:fakeEnvelope(d.claim.deviceId)}});
  assert.equal((await httpJson(url+'/v1/jobs/job-long-identifier',{headers:apiHeaders(apiKey)})).body.state,'complete');
  const u=await httpJson(url+'/v1/uploads',{method:'POST',headers:apiHeaders(apiKey),body:{name:'ciphertext.bin',size:6}});assert.equal(u.status,201);
  const upload=url+'/v1/uploads/'+u.body.id;
  for(const [offset,body] of [[0,'abc'],[3,'def']]){
   const part=await httpJson(upload,{method:'PUT',headers:apiHeaders(apiKey,{'x-upload-offset':String(offset)}),body});assert.equal(part.status,200);assert.equal(part.body.offset,offset+3);
  }
  assert.equal((await httpJson(upload,{method:'PUT',headers:apiHeaders(apiKey,{'x-upload-offset':'0'}),body:'abc'})).status,409);
  const done=await httpJson(upload+'/complete',{method:'POST',headers:apiHeaders(apiKey),body:{}});assert.equal(done.status,200);
  assert.equal(await (await fetch(done.body.url)).text(),'abcdef');assert.equal(await (await fetch(done.body.url)).text(),'abcdef');
 });
});

test('running deadline releases lease with unknown outcome and late results can resolve it',async()=>{
 const q=createQueue();try{
  q.enqueue('d',{id:'running',envelope:{},expiresAt:Date.now()+30});
  assert.equal((await q.waitForJob('d',1)).id,'running');
  await delay(45);assert.equal(q.get('running').state,'unknown');
  assert.equal(q.complete('running',{envelope:{}},'d'),true);assert.equal(q.get('running').state,'complete');
 }finally{q.close();}
});
test('operator can rotate an account key and another agent reuses the same phone',async()=>{
 await withRelay({adminToken:'test-admin'},async({relay,url,apiKey})=>{
  const d=await pairSession(url,apiKey);
  const created=await httpJson(url+'/v1/pair/session',{method:'POST',headers:apiHeaders(apiKey),body:{name:'phone'}});
  const base=url+'/v1/pair/session/'+created.body.sessionId;
  await httpJson(base+'/agent',{method:'PUT',headers:apiHeaders(apiKey),body:{agentId:'agt_fedcba9876543210',msg:'YWxpY2Vtc2c'}});
  const claim=await httpJson(base+'/device',{method:'POST',headers:deviceHeaders(d.claim.deviceToken),body:{msg:'Ym9ibXNn'}});
  assert.equal(claim.status,200);assert.equal(claim.body.deviceId,d.claim.deviceId);assert.equal(claim.body.deviceToken,d.claim.deviceToken);
  await httpJson(base+'/device-confirm',{method:'PUT',headers:deviceHeaders(d.claim.deviceToken),body:{confirm:'Y29uZmlybUI'}});
  assert.equal((await httpJson(base+'/confirm',{method:'PUT',headers:apiHeaders(apiKey),body:{confirm:'Y29uZmlybUE'}})).status,200);
  assert.equal(relay.store.devicesForAccount('default').length,1);
  assert.equal(relay.store.devicesForAccount('default')[0].agents.length,2);
  assert.equal((await httpJson(url+'/v1/accounts/default/rotate-key',{method:'POST',headers:apiHeaders(apiKey),body:{}})).status,401);
  const rotation=await httpJson(url+'/v1/accounts/default/rotate-key',{method:'POST',headers:apiHeaders('test-admin'),body:{}});
  assert.equal(rotation.status,200);
  assert.equal((await httpJson(url+'/v1/me',{headers:apiHeaders(apiKey)})).status,401);
  assert.equal((await httpJson(url+'/v1/me',{headers:apiHeaders(rotation.body.apiKey)})).status,200);
  assert.equal((await httpJson(url+'/v1/uploads/'+'0'.repeat(32),{headers:apiHeaders(rotation.body.apiKey)})).status,404);
 });
});

test('a phone with several lanes runs that many jobs at once and older phones run one',async()=>{
 const q=createQueue();try{
  for(const id of ['a','b','c'])q.enqueue('d',{id,envelope:{}});
  assert.equal((await q.waitForJob('d',1)).id,'a');
  assert.equal(await q.waitForJob('d',1),null,'one lane until the phone reports more');
  assert.equal(q.setLanes('d',2),2);
  assert.equal((await q.waitForJob('d',1)).id,'b');
  assert.equal(await q.waitForJob('d',1),null,'both lanes busy');
  q.complete('a',{envelope:{}},'d');
  assert.equal((await q.waitForJob('d',1)).id,'c');
  assert.equal(q.setLanes('d',999),16,'lanes are capped');
 }finally{q.close();}
});
test('waiting polls on several lanes each receive a job',async()=>{
 const q=createQueue();try{
  q.setLanes('d',3);
  const waits=[q.waitForJob('d',500),q.waitForJob('d',500),q.waitForJob('d',500)];
  assert.equal(await q.waitForJob('d',1),null,'no more polls than lanes');
  q.enqueue('d',{id:'x',envelope:{}});q.enqueue('d',{id:'y',envelope:{}});
  const got=await Promise.all(waits);
  assert.deepEqual(got.filter(Boolean).map(j=>j.id).sort(),['x','y']);
  assert.equal(got.filter(j=>j===null).length,1);
 }finally{q.close();}
});
test('poll accounting allows one open poll per lane',()=>{
 const q=createQueue();try{
  assert.equal(q.beginPoll('d'),true);assert.equal(q.beginPoll('d'),false);
  q.setLanes('d',2);assert.equal(q.beginPoll('d'),true);assert.equal(q.isPolling('d'),true);
  q.endPoll('d');q.endPoll('d');assert.equal(q.isPolling('d'),false);
 }finally{q.close();}
});
test('a closed poll frees its lane and takes no job',async()=>{
 const q=createQueue();try{
  const gone=new AbortController();
  const dead=q.waitForJob('d',5000,gone.signal);
  const at=Date.now();gone.abort();assert.equal(await dead,null);
  assert.ok(Date.now()-at<1000,'the closed poll leaves at once');
  const live=q.waitForJob('d',500);
  q.enqueue('d',{id:'fresh',envelope:{},expiresAt:Date.now()+5000});
  assert.equal((await live).id,'fresh','the live poll gets the job, not the closed one');
 }finally{q.close();}
});
test('an undelivered job is queued again for the next poll',async()=>{
 const q=createQueue();try{
  q.enqueue('d',{id:'lost',envelope:{},expiresAt:Date.now()+5000});
  assert.equal((await q.waitForJob('d',1)).id,'lost');
  assert.equal(q.release('lost','other'),false,'only the owning device');
  assert.equal(q.release('lost','d'),true);
  assert.equal(q.get('lost').state,'queued');
  assert.equal((await q.waitForJob('d',1)).id,'lost');
 }finally{q.close();}
});
