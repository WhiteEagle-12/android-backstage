import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {withRelay,httpJson,apiHeaders,pairSession,hello,fakeEnvelope,startRelay,tmpDir} from './helpers.mjs';
import {routeLabel} from '../dashboard.mjs';
test('dashboard static view has strict CSP; data requires an account key',()=>withRelay(async({url,apiKey})=>{
 const page=await httpJson(url+'/dashboard/');assert.equal(page.status,200);assert.match(page.headers.get('content-security-policy'),/frame-ancestors 'none'/);assert.match(page.text,/Relay dashboard/);
 for(const p of ['/v1/dashboard','/v1/dashboard?api_key='+apiKey])assert.equal((await httpJson(url+p)).status,401);
 assert.equal((await httpJson(url+'/v1/dashboard',{headers:apiHeaders(apiKey)})).headers.get('cache-control'),'no-store');
 assert.equal((await httpJson(url+'/v1/dashboard',{method:'POST',headers:apiHeaders(apiKey)})).status,405);
 assert.equal((await httpJson(url+'/dashboard/no-such-file')).status,404);
}));
test('dashboard isolates accounts and omits secrets, payloads, URLs and error text',()=>withRelay(async({url,apiKey,relay})=>{
 const linked=await pairSession(url,apiKey);await hello(url,linked.claim.deviceToken);
 const other=relay.store.createAccount({name:'Other owner'});
 await httpJson(url+'/v1/jobs',{method:'POST',headers:apiHeaders(apiKey),body:{id:'dashboard_test_job_123',device:'phone',envelope:fakeEnvelope('agt_dashboard','1','SECRET_CIPHERTEXT')}});
 await httpJson(url+'/v1/device/next',{method:'POST',headers:apiHeaders(linked.claim.deviceToken),body:{}});
 await httpJson(url+'/v1/device/result',{method:'POST',headers:apiHeaders(linked.claim.deviceToken),body:{id:'dashboard_test_job_123',error:'PRIVATE_ERROR_CONTENT'}});
 await httpJson(url+'/v1/call?apiKey=SECRET_QUERY',{method:'POST',headers:apiHeaders(apiKey),body:{secret:'PRIVATE_BODY'}});
 const result=await httpJson(url+'/v1/dashboard',{headers:apiHeaders(apiKey)});assert.equal(result.status,200);assert.equal(result.body.devices.length,1);assert.equal(result.body.jobs.length,1);assert.equal(result.body.jobs[0].state,'complete');assert.equal(result.body.jobs[0].transportError,true);assert.ok(result.body.summary.incomingBytes>0);assert.ok(result.body.summary.outgoingBytes>0);assert.ok(result.body.events.some(e=>e.source==='phone'));
 for(const forbidden of [apiKey,linked.claim.deviceToken,'SECRET_CIPHERTEXT','PRIVATE_ERROR_CONTENT','SECRET_QUERY','PRIVATE_BODY','local-test-token'])assert.ok(!result.text.includes(forbidden),forbidden);
 assert.equal((await httpJson(url+'/v1/dashboard',{headers:apiHeaders(linked.claim.deviceToken)})).status,401);
 const isolated=await httpJson(url+'/v1/dashboard',{headers:apiHeaders(other.apiKey)});assert.deepEqual(isolated.body.devices,[]);assert.deepEqual(isolated.body.jobs,[]);assert.deepEqual(isolated.body.events,[]);
}));
test('metadata history survives restart with private file permissions',async()=>{
 const dataDir=tmpDir();let ctx=await startRelay({dataDir});
 try{await httpJson(ctx.url+'/v1/devices',{headers:apiHeaders(ctx.apiKey)});await ctx.relay.close();const file=path.join(dataDir,'dashboard-activity.json');assert.equal(fs.statSync(file).mode&0o777,0o600);ctx=await startRelay({dataDir});const result=await httpJson(ctx.url+'/v1/dashboard',{headers:apiHeaders(ctx.apiKey)});assert.ok(result.body.events.some(e=>e.route==='/v1/devices'));}finally{await ctx.relay.close();fs.rmSync(dataDir,{recursive:true,force:true});}
});
test('route labels never include pairing IDs or arbitrary request paths',()=>{assert.equal(routeLabel('/v1/pair/session/SECRET/device'),'/v1/pair/session/:id');assert.equal(routeLabel('/v1/PRIVATE'), 'other');assert.equal(routeLabel('/v1/devices/PRIVATE'),'/v1/devices/:name');});
