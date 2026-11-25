import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createRequestJournal} from '../request-journal.mjs';
test('same write ID survives concurrent retries and restart without repeated input',async t=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'bs-receipts-'));t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));
 let writes=0;const journal=createRequestJournal({directory});const action=async()=>{writes++;await new Promise(r=>setTimeout(r,10));return {outcome:'succeeded',inputDelivered:true,observation:'obs'};};
 const args={requestId:'r1',source:{url:'https://example.com/app.apk'}};
 const [a,b]=await Promise.all([journal('owner','android_install',args,action),journal('owner','android_install',args,action)]);assert.deepEqual(a,b);assert.equal(writes,1);
 assert.deepEqual(await createRequestJournal({directory})('owner','android_install',args,action),a);assert.equal(writes,1);
 const conflict=await journal('owner','android_install',{...args,source:{url:'https://other.example/app.apk'}},action);assert.equal(conflict.code,'request_id_conflict');assert.equal(conflict.inputDelivered,false);assert.equal(writes,1);
 await journal('other-owner','android_install',args,action);assert.equal(writes,2);
});
test('unknown writes remain unknown and definite preflight failures retain their code',async t=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'bs-receipts-'));t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));const journal=createRequestJournal({directory});let calls=0;
 const args={requestId:'uncertain'};const failed=await journal('a','android_tap',args,()=>{calls++;throw new Error('connection lost');});assert.equal(failed.outcome,'unknown');assert.equal(failed.inputDelivered,null);
 const retry=await journal('a','android_tap',args,()=>{throw new Error('must not run');});assert.deepEqual(retry,failed);assert.equal(calls,1);
 const offline=await journal('a','android_tap',{requestId:'offline'},()=>{const e=new Error('Phone is offline');e.code='device_offline';throw e;});assert.equal(offline.code,'device_offline');assert.equal(offline.inputDelivered,false);
 const file=fs.readdirSync(directory).find(f=>JSON.parse(fs.readFileSync(path.join(directory,f))).result?.outcome==='unknown');const row=JSON.parse(fs.readFileSync(path.join(directory,file)));delete row.result;fs.writeFileSync(path.join(directory,file),JSON.stringify(row));
 assert.equal((await createRequestJournal({directory})('a','android_tap',args,()=>{throw new Error('must not run');})).outcome,'unknown');
});

test('a late receipt replaces an unknown result on retry without repeating the action',async t=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'bs-reconcile-'));t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));
 let calls=0,lookups=0,ready=false;const args={requestId:'late'},done={outcome:'succeeded',sessionId:'session_saved'};
 const reconcile=async(owner,name,input,id)=>{lookups++;assert.equal(owner,'owner');assert.equal(id,'call-123456789abcdef0');return ready?done:null;};
 const journal=createRequestJournal({directory,reconcile});
 const unknown=await journal('owner','android_task_begin',args,()=>{calls++;throw Object.assign(new Error('lost response'),{jobId:'call-123456789abcdef0'});});
 assert.equal(unknown.outcome,'unknown');assert.deepEqual(await journal('owner','android_task_begin',args,()=>{calls++;}),unknown);
 ready=true;assert.deepEqual(await createRequestJournal({directory,reconcile})('owner','android_task_begin',args,()=>{calls++;}),done);
 assert.deepEqual(await createRequestJournal({directory})('owner','android_task_begin',args,()=>{calls++;}),done);assert.equal(calls,1);assert.equal(lookups,2);
});

test('relay rejection is definite and reconciliation failure never repeats unknown input',async t=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'bs-reject-'));t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));
 const journal=createRequestJournal({directory,reconcile:async()=>{throw Error('unknown job');}});
 const blocked=await journal('a','android_shell',{requestId:'full'},()=>{throw Object.assign(Error('queue full'),{code:'relay_busy',inputDelivered:false});});
 assert.equal(blocked.outcome,'failed');assert.equal(blocked.code,'relay_busy');assert.equal(blocked.inputDelivered,false);assert.equal(blocked.jobId,undefined);
 const args={requestId:'unknown'};const first=await journal('a','android_tap',args,()=>{throw Object.assign(Error('lost'),{jobId:'call-123456789abcdef0'});});
 assert.deepEqual(await journal('a','android_tap',args,()=>assert.fail('write repeated')),first);
});

test('a synchronous failure leaves no stale in-flight promise',async t=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'bs-sync-'));t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));
 let lookups=0;const journal=createRequestJournal({directory,reconcile:async()=>{lookups++;return {outcome:'succeeded'};}}),args={requestId:'sync'};
 await journal('a','android_task_begin',args,()=>{throw Object.assign(Error('lost'),{jobId:'call-123456789abcdef0'});});
 assert.equal((await journal('a','android_task_begin',args,()=>assert.fail('must not dispatch'))).outcome,'succeeded');assert.equal(lookups,1);
});

test('upload limits and validation failures never claim an uncertain phone write',async t=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'bs-upload-receipts-'));t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));
 const journal=createRequestJournal({directory});
 for(const code of ['busy','invalid_request']){
  const args={requestId:code};
  const result=await journal('a','android_artifact_begin',args,()=>{throw Object.assign(Error('Wait for an upload to expire.'),{code});});
  assert.equal(result.outcome,'failed');assert.equal(result.inputDelivered,false);assert.equal(result.code,code);assert.equal(result.error,'Wait for an upload to expire.');
  assert.deepEqual(await journal('a','android_artifact_begin',args,()=>assert.fail('must not run')),result);
 }
});

 test('pre-dispatch transfer failure is definite and retains its receipt',async()=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'transfer-journal-'));
 try{const execute=createRequestJournal({directory});let attempts=0;const fail=()=>{attempts++;throw Object.assign(new Error('No phone command was sent'),{code:'transfer_prepare_failed'});};
 const first=await execute('owner','android_push',{requestId:'prepare-failed'},fail);
 assert.equal(first.outcome,'failed');assert.equal(first.inputDelivered,false);
 assert.deepEqual(await execute('owner','android_push',{requestId:'prepare-failed'},fail),first);assert.equal(attempts,1);
 }finally{fs.rmSync(directory,{recursive:true,force:true});}
 });

test('a full journal evicts the oldest completed receipts and never fails for quota',async t=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'bs-evict-'));t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));
 let clock=Date.now(),runs=0;const journal=createRequestJournal({directory,now:()=>clock,maxReceipts:4});
 const write=async id=>{clock+=10;return journal('o','android_tap',{requestId:id},()=>{runs++;return {outcome:'succeeded',id};});};
 for(let i=1;i<=4;i++)await write('w'+i);
 await write('w5');assert.equal(runs,5);assert.equal(fs.readdirSync(directory).length,4);
 assert.equal((await write('w5')).id,'w5');assert.equal((await write('w4')).id,'w4');assert.equal(runs,5);  // retained: replayed
 await write('w6');assert.equal(runs,6);await write('w2');assert.equal(runs,7);                                                                      // evicted first: runs again
});
test('receipts with an unknown outcome are never evicted and a journal of only those refuses with a clear message',async t=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'bs-protect-'));t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));
 let clock=Date.now();const journal=createRequestJournal({directory,now:()=>clock,maxReceipts:3});
 clock+=10;const stuck=await journal('o','android_tap',{requestId:'stuck'},()=>{throw new Error('connection lost');});assert.equal(stuck.outcome,'unknown');
 for(let i=1;i<=6;i++){clock+=10;await journal('o','android_tap',{requestId:'d'+i},()=>({outcome:'succeeded'}));}
 assert.equal(fs.readdirSync(directory).length,3);
 assert.deepEqual(await createRequestJournal({directory,now:()=>clock,maxReceipts:3})('o','android_tap',{requestId:'stuck'},()=>assert.fail('repeated')),stuck);
 const tiny=fs.mkdtempSync(path.join(os.tmpdir(),'bs-allunknown-'));t.after(()=>fs.rmSync(tiny,{recursive:true,force:true}));
 const small=createRequestJournal({directory:tiny,now:()=>clock,maxReceipts:2});
 for(const id of ['u1','u2']){clock+=10;await small('o','android_tap',{requestId:id},()=>{throw new Error('lost');});}
 const refused=await small('o','android_tap',{requestId:'u3'},()=>assert.fail('dispatched'));
 assert.equal(refused.code,'receipt_limit');assert.equal(refused.inputDelivered,false);assert.match(refused.error,/unknown outcome/);
});
test('expired completed receipts are dropped on the next write',async t=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'bs-expiry-'));t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));
 let clock=Date.now();const journal=createRequestJournal({directory,now:()=>clock});
 await journal('o','android_tap',{requestId:'old'},()=>({outcome:'succeeded'}));clock+=86400001;
 await journal('o','android_tap',{requestId:'new'},()=>({outcome:'succeeded'}));assert.equal(fs.readdirSync(directory).length,1);
});

test('a write the phone refused keeps the phone\'s reason, still marked unknown',async t=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'bs-receipts-'));t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));const journal=createRequestJournal({directory});
 const r=await journal('a','android_install',{requestId:'self'},()=>{const e=new Error('Refusing self-update: APK signing certificate does not match the installed app.');e.code='tool_failed';throw e;});
 assert.equal(r.outcome,'unknown');assert.match(r.error,/^The phone reported: Refusing self-update: APK signing certificate does not match the installed app\. Check/);
});
