import {test} from 'node:test';
import assert from 'node:assert/strict';
import {withRelay,pairSession,hello,httpJson,apiHeaders,fakeEnvelope} from './helpers.mjs';

test('device routing rejects missing, ambiguous, offline and reassigned selections before queueing',async()=>{
 await withRelay(async({relay,url,apiKey})=>{
  const phone=await pairSession(url,apiKey,{name:'phone'});
  const tablet=await pairSession(url,apiKey,{name:'tablet'});
  await hello(url,tablet.claim.deviceToken);
  let n=0;
  const submit=async(device,expectedDeviceId)=>{
   const id=`selection-check-${++n}`;
   const r=await httpJson(url+'/v1/jobs',{method:'POST',headers:apiHeaders(apiKey),body:{id,device,expectedDeviceId,envelope:fakeEnvelope()}});
   if(r.status!==202)assert.equal((await httpJson(url+'/v1/jobs/'+id,{headers:apiHeaders(apiKey)})).status,404);
   return r;
  };
  assert.equal((await submit('')).status,400);
  assert.equal((await submit('phoen')).status,400);
  const offline=await submit('phone',phone.claim.deviceId);
  assert.equal(offline.status,409);assert.match(offline.body.error,/offline/);
  const conflict=await httpJson(url+'/v1/devices/tablet',{method:'PATCH',headers:apiHeaders(apiKey),body:{aliases:['PHONE',phone.claim.deviceId]}});
  assert.equal(conflict.status,409);
  await hello(url,phone.claim.deviceToken);
  relay.store.updateDevice(phone.claim.deviceId,{name:'old-phone'});
  relay.store.updateDevice(tablet.claim.deviceId,{name:'phone'});
  const reassigned=await submit('phone',phone.claim.deviceId);
  assert.equal(reassigned.status,409);assert.match(reassigned.body.error,/paired device ID/);
  // Legacy state or an out-of-band edit can contain duplicate aliases: fail closed.
  relay.store.updateDevice(phone.claim.deviceId,{aliases:['PHONE']});
  const ambiguous=await submit('phone');
  assert.equal(ambiguous.status,409);assert.match(ambiguous.body.error,/ambiguous/);
  assert.equal((await submit(phone.claim.deviceId,phone.claim.deviceId)).status,202);
 });
});
