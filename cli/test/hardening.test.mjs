import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import {sealEnvelopeV2,openEnvelope,callKey,resultKey} from '../crypto.mjs';
import {encryptedCall,resolveConfig} from '../backstage.mjs';
import {runWorkflow,compileReceipt} from '../workflows.mjs';

test('v2 nonce is independent of restored counter state and authenticates header',()=>{
 const key=Buffer.alloc(32,9),a=sealEnvelopeV2({key,from:'a',n:'1',plaintext:'one'}),b=sealEnvelopeV2({key,from:'a',n:'1',plaintext:'two'});
 assert.notEqual(a.r,b.r);assert.equal(openEnvelope({key,envelope:a}).toString(),'one');
 assert.throws(()=>openEnvelope({key,envelope:{...a,n:'2'}}));
});
test('stale clients serialize and reload the durable shared config',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-lock-')),file=path.join(dir,'config.json');
 const key=Buffer.alloc(32,7),jobs=new Map(),seen=[];let counter=1000;
 const server=http.createServer(async(req,res)=>{
   if(req.method==='POST'){
     let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);
     assert.equal(body.device,'phone');assert.equal(body.expectedDeviceId,'dev_test');
     const plain=JSON.parse(openEnvelope({key:callKey(key),envelope:body.envelope}));seen.push(body.envelope.n);
     assert.equal(plain.deviceId,'dev_test');
     jobs.set(body.id,{state:'complete',envelope:sealEnvelopeV2({key:resultKey(key),from:'dev_test',n:String(++counter),plaintext:JSON.stringify({id:plain.id,result:{ok:true}})})});
     res.writeHead(202,{'content-type':'application/json'});res.end(JSON.stringify({id:body.id}));
   }else{res.writeHead(200,{'content-type':'application/json'});res.end(JSON.stringify(jobs.get(req.url.split('/').pop())));}
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 try{
  fs.writeFileSync(file,JSON.stringify({relayUrl:`http://127.0.0.1:${server.address().port}`,apiKey:'bsk_test',device:'phone',agentId:'agt_0123456789abcdef',links:{phone:{deviceId:'dev_test',linkKey:key.toString('base64url'),callCounter:'100',lastResultCounter:'0'}}}));
  const a=resolveConfig({config:file}),b=resolveConfig({config:file});
  await Promise.all([encryptedCall(a,'android.shell',{command:'one'}),encryptedCall(b,'android.shell',{command:'two'})]);
  assert.deepEqual(seen,['100','101']);assert.equal(fs.statSync(file).mode&0o777,0o600);
 }finally{await new Promise(r=>server.close(r));fs.rmSync(dir,{recursive:true,force:true});}
});

test('missing or unknown selection cannot fall back to another linked device',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-select-')),file=path.join(dir,'config.json');
 try{
  fs.writeFileSync(file,JSON.stringify({links:{tablet:{deviceId:'dev_tablet',linkKey:Buffer.alloc(32,1).toString('base64url')}}}));
  const config={filePath:file,device:'',links:{}};
  await assert.rejects(encryptedCall(config,'android.shell',{command:'id'}),/missing device/);
  await assert.rejects(encryptedCall({...config,device:'phone'},'android.shell',{command:'id'}),/no link for device phone/);
  assert.equal(JSON.parse(fs.readFileSync(file)).links.tablet.callCounter,undefined);
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('workflow stops on failed assertions and only compiles successful evidence',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-flow-'));let calls=0;
 try{
 const r=await runWorkflow({version:1,steps:[{tool:'android.qa_launch',arguments:{query:'${app}'},assert:[{path:'alive',equals:true}]},{tool:'android.apps'}]}, {app:'example.app'},async()=>{calls++;return {alive:false};},{outputDir:dir,device:'phone'});
 assert.equal(r.status,'failed');assert.equal(calls,1);assert.throws(()=>compileReceipt(JSON.parse(fs.readFileSync(r.receipt))));
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});

test('malformed existing configuration fails closed',()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-invalid-')),file=path.join(dir,'config.json');
 try{fs.writeFileSync(file,'{broken');assert.throws(()=>resolveConfig({config:file}),/Cannot read config/);}
 finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('compiled routines preserve dynamic display references and assertions',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-template-'));
 const spec={version:1,steps:[{tool:'android.qa_launch',arguments:{query:'${app}'},saveAs:'launch',assert:[{path:'alive',equals:true}]},{tool:'android.ui_tree',arguments:{display:'${launch.display}'}}]};
 try{
  let n=0;const run=await runWorkflow(spec,{app:'com.example.app'},async()=>++n===1?{alive:true,display:42}:{ok:true},{outputDir:dir});
  assert.deepEqual(compileReceipt(JSON.parse(fs.readFileSync(run.receipt))).steps,spec.steps);
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});

test('self-update reconciles an unknown restart by polling the same job without resubmission',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-update-')),file=path.join(dir,'config.json');
 const key=Buffer.alloc(32,8);let submitted=0,polls=0,job;
 const server=http.createServer(async(req,res)=>{
  res.setHeader('content-type','application/json');
  if(req.method==='POST'){
   submitted++;let raw='';for await(const chunk of req)raw+=chunk;
   const body=JSON.parse(raw),plain=JSON.parse(openEnvelope({key:callKey(key),envelope:body.envelope}));
   job={id:body.id,envelope:sealEnvelopeV2({key:resultKey(key),from:'dev_update',n:'200',plaintext:JSON.stringify({id:plain.id,result:{ok:true,scheduled:true}})})};
   res.writeHead(202);res.end(JSON.stringify({id:job.id,state:'queued'}));
  }else{
   assert.equal(req.url,'/v1/jobs/'+job.id);polls++;
   res.end(JSON.stringify(polls===1?{state:'unknown',error:'device restarted during execution'}:{state:'complete',...job}));
  }
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 try{
  fs.writeFileSync(file,JSON.stringify({relayUrl:`http://127.0.0.1:${server.address().port}`,apiKey:'test',device:'phone',agentId:'agt_0123456789abcdef',links:{phone:{deviceId:'dev_update',linkKey:key.toString('base64url'),callCounter:'100',lastResultCounter:'0'}}}));
  const result=await encryptedCall(resolveConfig({config:file}),'android.maintenance',{});
  assert.equal(result.scheduled,true);assert.equal(submitted,1);assert.equal(polls,2);
 }finally{await new Promise(r=>server.close(r));fs.rmSync(dir,{recursive:true,force:true});}
});

test('a rejected relay submission has no job to query and delivers no input',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-rejected-')),file=path.join(dir,'config.json');
 let requests=0;const server=http.createServer((req,res)=>{requests++;res.writeHead(429,{'content-type':'application/json'});res.end(JSON.stringify({error:'device queue capacity reached'}));});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>{server.close();fs.rmSync(dir,{recursive:true,force:true});});
 fs.writeFileSync(file,JSON.stringify({relayUrl:`http://127.0.0.1:${server.address().port}`,apiKey:'test',device:'phone',agentId:'agt_0123456789abcdef',links:{phone:{deviceId:'dev_rejected',linkKey:Buffer.alloc(32,8).toString('base64url'),callCounter:'100',lastResultCounter:'0'}}}));
 await assert.rejects(encryptedCall(resolveConfig({config:file}),'android.task_begin',{requestId:'one'}),e=>{
  assert.equal(e.inputDelivered,false);assert.equal(e.code,'relay_busy');assert.equal(e.jobId,undefined);assert.doesNotMatch(e.message,/Query job|job call-/);return true;
 });assert.equal(requests,1);
});

test('temporary polling failure recovers the accepted job without another submission',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-poll-')),file=path.join(dir,'config.json');
 const key=Buffer.alloc(32,4);let submissions=0,polls=0,job;
 const server=http.createServer(async(req,res)=>{
  res.setHeader('content-type','application/json');
  if(req.method==='POST'){
   submissions++;let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);job=body.id;
   res.writeHead(202);res.end(JSON.stringify({id:job,state:'queued'}));
  }else if(++polls===1){res.writeHead(503);res.end(JSON.stringify({error:'temporarily unavailable'}));}
  else {res.end(JSON.stringify({state:'complete',envelope:sealEnvelopeV2({key:resultKey(key),from:'dev_poll',n:'200',plaintext:JSON.stringify({id:job,result:{outcome:'succeeded'}})})}));}
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));t.after(()=>{server.close();fs.rmSync(dir,{recursive:true,force:true});});
 fs.writeFileSync(file,JSON.stringify({relayUrl:`http://127.0.0.1:${server.address().port}`,apiKey:'test',device:'phone',agentId:'agt_0123456789abcdef',links:{phone:{deviceId:'dev_poll',linkKey:key.toString('base64url'),callCounter:'100',lastResultCounter:'0'}}}));
 assert.equal((await encryptedCall(resolveConfig({config:file}),'android.shell',{command:'true'})).outcome,'succeeded');assert.equal(submissions,1);assert.equal(polls,2);
});
