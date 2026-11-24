import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createWorkApp,catalogue} from '../server.mjs';
import {createOwners} from '../owners.mjs';
import {createConnections} from '../connections.mjs';
import {createArtifacts} from '../artifacts.mjs';
import {withRelay,httpJson,deviceHeaders,hello} from '../../relay/test/helpers.mjs';
import {Spake2,ALICE_NAME,BOB_NAME} from '../../cli/spake2.mjs';
import {linkKey,confirmB,b64url,unb64url,openEnvelope,callKey,resultKey,sealEnvelopeV2,decryptFileTo} from '../../cli/crypto.mjs';
import {writePrivateJson} from '../../cli/state.mjs';
import {passkeyFixture,cookies} from './passkey-fixture.mjs';

const base='https://relay.example/work',origin=new URL(base).origin;

/** A simulated Android Backstage phone: claims a pairing code, then answers encrypted jobs. */
async function fakePhone(url,code,model) {
  const bob=new Spake2('bob',BOB_NAME,ALICE_NAME),session=code.split('-')[0];
  const claim=await httpJson(url+'/v1/pair/session/'+session+'/device',{method:'POST',body:{msg:b64url(bob.generateMsg(Buffer.from(code))),model}});
  assert.equal(claim.status,200,JSON.stringify(claim.body));
  const key=linkKey(bob.processMsg(unb64url(claim.body.agentMsg)));
  await httpJson(url+'/v1/pair/session/'+session+'/device-confirm',{method:'PUT',headers:deviceHeaders(claim.body.deviceToken),body:{confirm:b64url(confirmB(key))}});
  let counter=0;
  const phone={deviceId:claim.body.deviceId,token:claim.body.deviceToken,agentId:claim.body.agentId,key,model,received:[],
    online:()=>hello(url,claim.body.deviceToken),
    /** Take the next job, run `handler(plainCall)` and return its result to the relay. */
    async serve(handler=()=>({ok:true,model})) {
      const job=await httpJson(url+'/v1/device/next',{method:'POST',headers:deviceHeaders(claim.body.deviceToken),body:{}});
      assert.ok(job.body.id,'phone expected a job: '+JSON.stringify(job.body));
      const plain=JSON.parse(openEnvelope({key:callKey(key),envelope:job.body.envelope}));
      assert.equal(plain.deviceId,claim.body.deviceId,'a call must carry this phone’s own device ID');
      phone.received.push(plain);
      let body;
      try {const result=await handler(plain);body={id:plain.id,result};} catch(e){body={id:plain.id,error:{message:e.message}};}
      const envelope=sealEnvelopeV2({key:resultKey(key),from:claim.body.deviceId,n:String(++counter),plaintext:JSON.stringify(body)});
      const r=await httpJson(url+'/v1/device/result',{method:'POST',headers:deviceHeaders(claim.body.deviceToken),body:{id:job.body.id,envelope}});assert.equal(r.status,200);
      return plain;
    },
    async revokeLink() {const r=await httpJson(url+'/v1/device/revoke-connection',{method:'POST',headers:deviceHeaders(claim.body.deviceToken),body:{agentId:claim.body.agentId}});assert.equal(r.status,200);}
  };
  return phone;
}

async function harness(t,{url,dataDir}) {
  const stateFile=path.join(dataDir,'oauth.json'),ownerFile=path.join(dataDir,'owners.json'),directory=path.join(dataDir,'connections');
  let clock=Date.now(),server,endpoint,jar='';
  const start=async()=>{
    const connections=createConnections({directory,relayUrl:url,now:()=>clock}),owners=createOwners({stateFile:ownerFile,origin,now:()=>clock});
    const artifacts=createArtifacts({directory:path.join(dataDir,'artifacts'),now:()=>clock});
    const app=createWorkApp({base,stateFile,connections,owners,artifacts,now:()=>clock});
    server=await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s));});endpoint='http://127.0.0.1:'+server.address().port;
    return connections;
  };
  let connections=await start();
  t.after(()=>new Promise(resolve=>server.close(resolve)));
  const post=(route,body,headers={})=>fetch(endpoint+route,{method:'POST',redirect:'manual',headers:{'Content-Type':'application/json',origin,cookie:jar,...headers},body:JSON.stringify(body)});
  const get=route=>fetch(endpoint+route,{headers:{cookie:jar},redirect:'manual'});
  // Owner signs in with a real passkey.
  const key=passkeyFixture(origin);let r=await post('/work/passkeys/options',{mode:'register'});jar=cookies(r,jar);r=await post('/work/passkeys/verify',key.register(await r.json()));assert.equal(r.status,200);jar=cookies(r,jar);
  const register=async(redirect,name)=>{const r=await post('/work/register',{redirect_uris:[redirect],client_name:name});assert.equal(r.status,201);return {...await r.json(),redirect,name};};
  async function authorizePage(client){const verifier=crypto.randomBytes(32).toString('base64url');const params={passkey:'1',client_id:client.client_id,redirect_uri:client.redirect,response_type:'code',resource:base+'/mcp',state:'s',code_challenge_method:'S256',code_challenge:crypto.createHash('sha256').update(verifier).digest('base64url')};const r=await get('/work/authorize?'+new URLSearchParams(params));assert.equal(r.status,200);const html=await r.text();return {verifier,html,ticket:/const ticket="([^"]+)"|ticket="([^"]+)"/.exec(html)?.slice(1).find(Boolean)};}
  async function tokens(client,flow,approval){const approved=await post('/work/authorize',{ticket:flow.ticket,...approval});const approvedBody=await approved.json();assert.equal(approved.status,200,JSON.stringify(approvedBody));const callback=new URL(approvedBody.redirect);const r=await post('/work/token',{grant_type:'authorization_code',client_id:client.client_id,resource:base+'/mcp',redirect_uri:client.redirect,code:callback.searchParams.get('code'),code_verifier:flow.verifier});assert.equal(r.status,200);return (await r.json()).access_token;}
  /** First assistant: its OAuth page enrolls the first phone. */
  async function enrollFirst(client,model){const flow=await authorizePage(client);const code=/<code>([^<]+)<\/code>/.exec(flow.html)[1];const phone=await fakePhone(url,code,model);const token=await tokens(client,flow,{});return {phone,token};}
  /** Later assistants see the consent page with a device picker. */
  async function consent(client,approval){const flow=await authorizePage(client);assert.match(flow.html,/Devices .* may use/);return tokens(client,flow,approval);}
  /** Owner adds or reconnects a device from the account page. */
  async function enroll(query,model){const page=await get('/work/enroll'+query);assert.equal(page.status,200);const html=await page.text();const ticket=/const ticket="([^"]+)"/.exec(html)[1],code=/<code>([^<]+)<\/code>/.exec(html)[1];const phone=await fakePhone(url,code,model);const done=await post('/work/enroll',{ticket});assert.equal(done.status,200);assert.ok((await done.json()).redirect);return phone;}
  let rpcId=0;
  async function call(token,name,args,phone,handler){
    if(['android_shell','android_install','android_push','android_artifact_begin','android_artifact_delete','android_task_begin','android_task_finish','android_open','android_tap'].includes(name))args={requestId:crypto.randomUUID(),...args};
    const request=post('/work/mcp',{jsonrpc:'2.0',id:++rpcId,method:'tools/call',params:{name,arguments:args}},{Authorization:'Bearer '+token,Accept:'application/json, text/event-stream'});
    if(phone)await phone.serve(handler);
    const r=await request;assert.equal(r.status,200);return (await r.json()).result;
  }
  const restart=async()=>{await new Promise(resolve=>server.close(resolve));connections=await start();};
  return {get,post,register,enrollFirst,consent,enroll,call,restart,directory,connections:()=>connections,advance:ms=>{clock+=ms;},endpoint,jar:()=>jar,setJar:v=>{jar=v;}};
}

test('one agent uses two named devices; nothing is substituted when a device is missing, ambiguous or offline',async t=>withRelay(async ctx=>{
  const h=await harness(t,ctx);
  const codex=await h.register('http://localhost:1455/callback','Codex');
  const {phone:phoneA,token}=await h.enrollFirst(codex,'NX809J');
  const phoneB=await h.enroll('?name=tablet','SM-T870');
  await phoneA.online();await phoneB.online();
  const listed=await h.call(token,'android_devices',{});assert.ok(!listed.isError,JSON.stringify(listed.structuredContent));
  const names=listed.structuredContent.devices.map(d=>d.name).sort();assert.deepEqual(names,['phone','tablet']);
  for(const d of listed.structuredContent.devices){assert.match(d.id,/^phone_[a-f0-9]{32}$/);assert.equal(d.status,'online');}
  const tablet=listed.structuredContent.devices.find(d=>d.name==='tablet');
  const byName=await h.call(token,'android_packages',{device:'tablet',package:'com.example'},phoneB,()=>({ok:true,value:'SM-T870'}));assert.equal(byName.structuredContent.value,'SM-T870');
  const byId=await h.call(token,'android_packages',{device:tablet.id,package:'com.example'},phoneB,()=>({ok:true,value:'SM-T870'}));assert.equal(byId.structuredContent.value,'SM-T870');
  const other=await h.call(token,'android_packages',{device:'Phone',package:'com.example'},phoneA,()=>({ok:true,value:'NX809J'}));assert.equal(other.structuredContent.value,'NX809J');
  assert.equal(phoneA.received.length,1);assert.equal(phoneB.received.length,2);
  const unnamed=await h.call(token,'android_packages',{package:'com.example'});assert.equal(unnamed.isError,true);assert.equal(unnamed.structuredContent.code,'device_required');assert.equal(unnamed.structuredContent.devices.length,2);
  const unknown=await h.call(token,'android_packages',{device:'watch',package:'com.example'});assert.equal(unknown.structuredContent.code,'unknown_device');
  assert.equal(phoneA.received.length+phoneB.received.length,3,'no phone received the misdirected calls');
  // Offline: a device that stopped polling is reported, never swapped.
  await withRelayOffline(ctx,phoneB,async()=>{const off=await h.call(token,'android_packages',{device:'tablet',package:'com.example'});assert.equal(off.structuredContent.code,'device_offline');assert.match(off.structuredContent.error,/tablet/);});
  // Raw shell forwards to the phone (which enforces the on-device Allow shell commands toggle).
  const shellForwarded=await h.call(token,'android_shell',{device:'phone',command:'echo hi'},phoneA,()=>({ok:false,code:'shell_disabled',inputDelivered:false,error:'Shell commands are off on this phone.'}));assert.equal(shellForwarded.structuredContent.code,'shell_disabled');
  // A rename keeps the ID and the link; the assistant keeps working.
  const rename=await h.post('/work/account/devices/rename',{deviceId:tablet.id,name:'Tab S7'});assert.equal(rename.status,200);
  const renamed=await h.call(token,'android_packages',{device:'tab s7',package:'com.example'},phoneB,()=>({ok:true,value:'SM-T870'}));assert.equal(renamed.structuredContent.value,'SM-T870');
  assert.equal((await h.post('/work/account/devices/rename',{deviceId:tablet.id,name:'phone'})).status,400,'names stay unique per owner');
  const account=await (await h.get('/work/account')).text();assert.match(account,/Tab S7/);assert.match(account,/Add a device/);
}));

async function withRelayOffline(ctx,phone,fn) {
  // Relay liveness is lastSeen within offlineMs; rewind this phone’s lastSeen.
  const device=ctx.relay.store.deviceById(phone.deviceId),saved=device.lastSeen;device.lastSeen=new Date(Date.now()-3600000).toISOString();
  try {await fn();} finally {device.lastSeen=saved;}
}

test('MCP retry recovers a saved encrypted task result without creating a second task',async t=>withRelay(async ctx=>{
 const h=await harness(t,ctx),client=await h.register('http://localhost:1455/callback','Codex');
 const {phone,token}=await h.enrollFirst(client,'NX809J');await phone.online();
 const connections=h.connections(),send=connections.call.bind(connections);
 connections.call=async(...args)=>{await send(...args);throw Object.assign(Error('response lost after execution'),{jobId:phone.received.at(-1).id});};
 const args={device:'phone',requestId:'late-task-result'};
 const first=await h.call(token,'android_task_begin',args,phone,()=>({outcome:'succeeded',sessionId:'session_recovered',display:13}));
 assert.equal(first.structuredContent.outcome,'unknown');assert.ok(first.structuredContent.jobId);
 await h.restart();
 const recovered=await h.call(token,'android_task_begin',args);
 assert.equal(recovered.structuredContent.outcome,'succeeded');assert.equal(recovered.structuredContent.sessionId,'session_recovered');assert.equal(recovered.structuredContent.device.name,'phone');
 const again=await h.call(token,'android_task_begin',args);assert.deepEqual(again.structuredContent,recovered.structuredContent);assert.equal(phone.received.length,1);
}));

test('two assistants: per-device access, separate credentials, revocation and removal leave the rest working, all across restart',async t=>withRelay(async ctx=>{
  const h=await harness(t,ctx);
  const chatgpt=await h.register('https://chatgpt.com/connector_platform_oauth_redirect','ChatGPT'),cursor=await h.register('https://cursor.com/oauth/callback','Cursor');
  const {phone:phoneA,token:chatToken}=await h.enrollFirst(chatgpt,'NX809J');
  const phoneB=await h.enroll('?name=tablet','SM-T870');await phoneA.online();await phoneB.online();
  const all=Object.entries(JSON.parse(fs.readFileSync(path.join(h.directory,'devices.json'))));const idA=all.find(([,d])=>d.name==='phone')[0],idB=all.find(([,d])=>d.name==='tablet')[0];
  // Cursor may use only the tablet.
  const cursorToken=await h.consent(cursor,{approve:true,devices:[idB]});
  const denied=await h.call(cursorToken,'android_packages',{device:'phone',package:'com.example'});assert.equal(denied.structuredContent.code,'device_not_authorized');
  const cursorList=await h.call(cursorToken,'android_devices',{});assert.deepEqual(cursorList.structuredContent.devices.map(d=>d.name),['tablet']);
  // Single accessible device: no device argument needed, and it is still the right one.
  const implicit=await h.call(cursorToken,'android_packages',{package:'com.example'},phoneB,()=>({ok:true,value:'SM-T870'}));assert.equal(implicit.structuredContent.value,'SM-T870');
  // ChatGPT (first assistant) keeps every device; its calls to the phone use the phone’s link, not the tablet’s.
  const chatPhone=await h.call(chatToken,'android_packages',{device:'phone',package:'com.example'},phoneA,()=>({ok:true,value:'NX809J'}));assert.equal(chatPhone.structuredContent.value,'NX809J');
  const chatTablet=await h.call(chatToken,'android_packages',{device:'tablet',package:'com.example'},phoneB,()=>({ok:true,value:'SM-T870'}));assert.equal(chatTablet.structuredContent.value,'SM-T870');
  // Links are per device; grants hold no keys.
  const links=fs.readdirSync(path.join(h.directory,'phones')).filter(f=>f.endsWith('.json'));assert.equal(links.length,2);
  for(const g of fs.readdirSync(h.directory).filter(f=>/^grant_.*\.json$/.test(f))){const row=JSON.parse(fs.readFileSync(path.join(h.directory,g)));assert.ok(!row.links&&!row.apiKey&&!row.linkKey);assert.ok(row.access==='all'||Array.isArray(row.access));}
  const keyA=JSON.parse(fs.readFileSync(path.join(h.directory,'phones',idA+'.json'))).links.phone.linkKey,keyB=JSON.parse(fs.readFileSync(path.join(h.directory,'phones',idB+'.json'))).links.tablet.linkKey;assert.notEqual(keyA,keyB);
  // Owner widens Cursor to every device from the account page.
  const grants=h.connections().list(JSON.parse(fs.readFileSync(path.join(h.directory,'devices.json')))[idA].ownerId);
  const cursorGrant=grants.find(g=>g.name==='Cursor');assert.deepEqual(cursorGrant.access,[idB]);
  assert.equal((await h.post('/work/account/access',{grantId:cursorGrant.id,allDevices:true})).status,200);
  const widened=await h.call(cursorToken,'android_packages',{device:'phone',package:'com.example'},phoneA,()=>({ok:true,value:'NX809J'}));assert.equal(widened.structuredContent.value,'NX809J');
  // Restart: devices, grants, access and links all survive.
  await h.restart();await phoneA.online();await phoneB.online();
  const afterRestart=await h.call(cursorToken,'android_packages',{device:'tablet',package:'com.example'},phoneB,()=>({ok:true,value:'SM-T870'}));assert.equal(afterRestart.structuredContent.value,'SM-T870');
  assert.equal(JSON.parse(fs.readFileSync(path.join(h.directory,'phones',idA+'.json'))).links.phone.linkKey,keyA);
  // Revoke ChatGPT: Cursor and both devices are untouched.
  const chatGrant=grants.find(g=>g.name==='ChatGPT');assert.equal((await h.post('/work/account/revoke',{grantId:chatGrant.id})).status,200);
  assert.equal((await h.post('/work/mcp',{jsonrpc:'2.0',id:9,method:'tools/list'},{Authorization:'Bearer '+chatToken})).status,401);
  const still=await h.call(cursorToken,'android_packages',{device:'phone',package:'com.example'},phoneA,()=>({ok:true,value:'NX809J'}));assert.equal(still.structuredContent.value,'NX809J');
  assert.equal(fs.readdirSync(path.join(h.directory,'phones')).filter(f=>f.endsWith('.json')).length,2);
  // Remove the tablet: the phone keeps working for Cursor; the tablet is gone from its list and its link is deleted.
  assert.equal((await h.post('/work/account/devices/remove',{deviceId:idB})).status,200);
  assert.ok(!fs.existsSync(path.join(h.directory,'phones',idB+'.json')));
  const gone=await h.call(cursorToken,'android_packages',{device:'tablet',package:'com.example'});assert.equal(gone.structuredContent.code,'unknown_device');
  const kept=await h.call(cursorToken,'android_packages',{device:'phone',package:'com.example'},phoneA,()=>({ok:true,value:'NX809J'}));assert.equal(kept.structuredContent.value,'NX809J');
  assert.deepEqual((await h.call(cursorToken,'android_devices',{})).structuredContent.devices.map(d=>d.name),['phone']);
}));

test('the saved single-phone enrollment and its grants migrate in place and survive restart',async t=>{
  const dataDir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-migrate-'));t.after(()=>fs.rmSync(dataDir,{recursive:true,force:true}));
  const directory=path.join(dataDir,'connections'),ownerId='owner_'+'a'.repeat(43),phoneId='phone_'+'b'.repeat(32),grantA='grant_'+'c'.repeat(32),grantB='grant_'+'d'.repeat(32);
  const link={relayUrl:'http://127.0.0.1:1',apiKey:'bsc_x',agentId:'agt_0123456789abcdef',device:'phone',links:{phone:{deviceId:'dev_1',linkKey:b64url(crypto.randomBytes(32)),callCounter:'5',lastResultCounter:'4'}}};
  writePrivateJson(path.join(directory,'phones',phoneId+'.json'),link);
  writePrivateJson(path.join(directory,'enrollments.json'),{[ownerId]:phoneId});
  writePrivateJson(path.join(directory,grantA+'.json'),{ownerId,clientId:'https://chatgpt.com/oauth/code',clientName:'Codex',phoneId,createdAt:1});
  writePrivateJson(path.join(directory,grantB+'.json'),{ownerId,clientId:'P5dB',clientName:'ChatGPT',phoneId,createdAt:2});
  const calls=[];
  const make=()=>createConnections({directory,relayUrl:'http://127.0.0.1:1',call:async(cfg,name,args)=>{calls.push({file:cfg.filePath,name,args,deviceId:cfg.links[cfg.device].deviceId});return {ok:true};}});
  let c=make();
  assert.deepEqual(c.devices(ownerId).map(d=>({id:d.id,name:d.name})),[{id:phoneId,name:'phone'}]);
  assert.deepEqual(c.list(ownerId).map(g=>[g.name,g.access]),[['Codex','all'],['ChatGPT','all']]);
  assert.equal(c.accessible(grantA)[0].id,phoneId);
  // Calls without a device name still reach the one phone, through the original link file with its counters intact.
  await c.call('android.packages',{package:'com.example'},grantA);
  assert.equal(calls[0].file,path.join(directory,'phones',phoneId+'.json'));assert.equal(calls[0].deviceId,'dev_1');
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(directory,'phones',phoneId+'.json'))),link,'migration never rewrites the link or its counters');
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(directory,'enrollments.json'))),{[ownerId]:phoneId},'the legacy index is left for rollback');
  // Restart with the new index present.
  c=make();assert.equal(c.devices(ownerId).length,1);assert.equal(c.resolve(grantB).id,phoneId);
  // A second device joins the same owner; existing grants (access "all") may use it without re-authorizing.
  const phone2='phone_'+'e'.repeat(32);writePrivateJson(path.join(directory,'phones',phone2+'.json'),{...link,device:'tablet',links:{tablet:{...link.links.phone,deviceId:'dev_2'}}});
  fs.writeFileSync(path.join(directory,'devices.json'),JSON.stringify({...JSON.parse(fs.readFileSync(path.join(directory,'devices.json'))),[phone2]:{ownerId,name:'tablet',createdAt:3,linkedAt:3}}));
  c=make();assert.equal(c.accessible(grantA).length,2);assert.throws(()=>c.resolve(grantA),/Say which device/);
  await c.call('android.packages',{package:'com.example'},grantA,{device:c.resolve(grantA,'tablet')});assert.equal(calls[1].deviceId,'dev_2');
});

test('task ownership and compact action receipts travel unchanged to the selected phone',async t=>withRelay(async ctx=>{
 const h=await harness(t,ctx);const client=await h.register('http://localhost:1455/callback','Codex');const {phone,token}=await h.enrollFirst(client,'NX809J');await phone.online();
 const sessionId='session_'+'a'.repeat(32);
 const started=await h.call(token,'android_task_begin',{},phone,call=>{assert.equal(call.name,'android.task_begin');assert.ok(call.arguments.requestId);return {outcome:'succeeded',sessionId,display:3,leaseExpiresAt:123};});assert.equal(started.structuredContent.sessionId,sessionId);
 const seen=await h.call(token,'android_observe',{sessionId,image:'always'},phone,call=>{assert.equal(call.arguments.sessionId,sessionId);return {outcome:'succeeded',observation:'o',image:{mime:'image/png',pngBase64:'YWJj',displayId:3,width:100,height:50,sourceWidth:200,sourceHeight:100,screenshotId:'shot'}};});assert.equal(seen.content.find(c=>c.type==='image').data,'YWJj');
 const failed=await h.call(token,'android_tap',{sessionId,selector:{label:'Save'},verify:{kind:'exists',selector:{label:'Saved'}}},phone,()=>({outcome:'failed',inputDelivered:true,verification:{matched:false},code:'verification_timeout',observation:'next'}));assert.equal(failed.isError,true);assert.equal(failed.structuredContent.inputDelivered,true);assert.equal(failed.structuredContent.verification.matched,false);
 const bad=await h.call(token,'android_observe',{display:0});assert.equal(bad.structuredContent.code,'invalid_argument');assert.equal(bad.structuredContent.inputDelivered,false);
 const wrong=await h.call(token,'android_observe',{sessionId:'session_'+'b'.repeat(32)},phone,()=>({outcome:'failed',code:'session_not_owned',inputDelivered:false}));assert.equal(wrong.structuredContent.code,'session_not_owned');
 const finished=await h.call(token,'android_task_finish',{sessionId},phone,()=>({outcome:'succeeded',sessionId,released:['display','temporaryFiles']}));assert.deepEqual(finished.structuredContent.released,['display','temporaryFiles']);
}));

test('a build travels privately: upload with integrity check, encrypted staging, install on the named phone; failures are distinct',async t=>withRelay(async ctx=>{
  const h=await harness(t,ctx);
  const codex=await h.register('http://localhost:1455/callback','Codex');
  const {phone,token}=await h.enrollFirst(codex,'NX809J');await phone.online();
  const apk=crypto.randomBytes(3*1024*1024+17),sha256=crypto.createHash('sha256').update(apk).digest('hex');
  const begun=await h.call(token,'android_artifact_begin',{name:'app-debug.apk',size:apk.length,sha256});
  const {artifactId,uploadToken,uploadUrl}=begun.structuredContent;assert.match(artifactId,/^art_/);assert.equal(uploadUrl,base+'/artifacts/'+artifactId);
  const put=(offset,bytes,auth=uploadToken)=>fetch(h.endpoint+'/work/artifacts/'+artifactId,{method:'PUT',headers:{Authorization:'Bearer '+auth,'X-Upload-Offset':String(offset),'Content-Type':'application/octet-stream'},body:bytes});
  // Installing before the upload finishes is refused.
  const early=await h.call(token,'android_install',{source:{artifactId}});assert.equal(early.structuredContent.code,'artifact_incomplete');
  assert.equal((await put(0,apk.subarray(0,1024),'wrong-token')).status,404);
  const first=await put(0,apk.subarray(0,2*1024*1024));assert.equal(first.status,200);assert.equal((await first.json()).offset,2*1024*1024);
  assert.equal((await put(0,apk.subarray(0,10))).status,409,'a stale offset is rejected so nothing is corrupted');
  const rest=await put(2*1024*1024,apk.subarray(2*1024*1024));assert.equal(rest.status,200);const done=await rest.json();assert.equal(done.complete,true);assert.equal(done.sha256,sha256);
  const status=await h.call(token,'android_artifact_status',{artifactId});assert.equal(status.structuredContent.complete,true);
  // The phone receives an encrypted relay URL, a file key and the plaintext digest, and can recover the exact bytes.
  let recovered;
  const installed=await h.call(token,'android_install',{device:'phone',source:{artifactId}},phone,async call=>{
    assert.equal(call.name,'android.install');assert.equal(call.arguments.source.sha256,sha256);assert.match(call.arguments.source.url,/\/v1\/files\/[a-f0-9]{64}$/);
    const cipher=Buffer.from(await (await fetch(call.arguments.source.url)).arrayBuffer());
    const tmp=path.join(os.tmpdir(),'bs-cipher-'+crypto.randomBytes(4).toString('hex'));fs.writeFileSync(tmp+'.bin',cipher);
    const out=decryptFileTo(tmp+'.bin',tmp+'.apk',unb64url(call.arguments.source.fileKey));recovered=fs.readFileSync(tmp+'.apk');fs.rmSync(tmp+'.bin');fs.rmSync(tmp+'.apk');
    assert.equal(out.sha256,sha256);
    assert.ok(!cipher.includes(apk.subarray(100,164)),'the relay stores ciphertext only');
    return {ok:true,installed:'com.example.app'};
  });
  assert.equal(installed.structuredContent.installed,'com.example.app');assert.ok(recovered.equals(apk));
  // A declared digest that does not match the bytes is discarded, never staged.
  const bad=await h.call(token,'android_artifact_begin',{name:'x.apk',size:5,sha256:'0'.repeat(64)});
  const rejected=await fetch(h.endpoint+'/work/artifacts/'+bad.structuredContent.artifactId,{method:'PUT',headers:{Authorization:'Bearer '+bad.structuredContent.uploadToken},body:Buffer.from('hello')});assert.equal(rejected.status,422);
  const missing=await h.call(token,'android_install',{source:{artifactId:bad.structuredContent.artifactId}});assert.equal(missing.structuredContent.code,'unknown_artifact');
  // Another assistant cannot use this assistant’s artifact.
  const cursor=await h.register('https://cursor.com/oauth/callback','Cursor');const cursorToken=await h.consent(cursor,{approve:true,allDevices:true});
  const foreign=await h.call(cursorToken,'android_install',{source:{artifactId}});assert.equal(foreign.structuredContent.code,'unknown_artifact');
  // push and self_update use the same verified artifact; URL installs remain available.
  const pushed=await h.call(token,'android_push',{device:'phone',artifactId,remote:'/sdcard/Download/app.apk'},phone,call=>{assert.equal(call.name,'android.fetch');assert.equal(call.arguments.remote,'/sdcard/Download/app.apk');assert.equal(call.arguments.sha256,sha256);return {ok:true};});assert.ok(!pushed.isError,JSON.stringify(pushed.structuredContent));
  const foreignDelete=await h.call(cursorToken,'android_artifact_delete',{artifactId});assert.equal(foreignDelete.structuredContent.code,'unknown_artifact');
  const deleteArgs={artifactId,requestId:crypto.randomUUID()};const deleted=await h.call(token,'android_artifact_delete',deleteArgs);assert.equal(deleted.structuredContent.deleted,true);
  const repeated=await h.call(token,'android_artifact_delete',deleteArgs);assert.deepEqual(repeated.structuredContent,deleted.structuredContent);
  const gone=await h.call(token,'android_artifact_status',{artifactId});assert.equal(gone.structuredContent.code,'unknown_artifact');
  const byUrl=await h.call(token,'android_install',{device:'phone',source:{url:'https://example.com/app.apk'}},phone,call=>{assert.equal(call.name,'android.install');return {ok:true};});assert.ok(!byUrl.isError,JSON.stringify(byUrl.structuredContent));
  // Single-call large file pull (>1 MiB): phone streams 3 chunks, gateway assembles and returns one verified downloadUrl.
  const big=crypto.randomBytes(2*1024*1024+123),bigSha=crypto.createHash('sha256').update(big).digest('hex'),bigId='file_'+bigSha;
  let pullCalls=0;
  const pullHandler=call=>{
    assert.equal(call.name,'android.pull');pullCalls++;
    const off=call.arguments.offset||0,len=Math.min(call.arguments.length||1048576,big.length-off);
    const slice=big.subarray(off,off+len);
    return {outcome:'succeeded',fileId:bigId,offset:off,bytes:slice.length,nextOffset:off+slice.length,totalBytes:big.length,eof:off+slice.length===big.length,sha256:crypto.createHash('sha256').update(slice).digest('hex'),dataBase64:slice.toString('base64')};
  };
  const pullReq=h.post('/work/mcp',{jsonrpc:'2.0',id:999,method:'tools/call',params:{name:'android_file',arguments:{device:'phone',operation:'pull',path:'/sdcard/Download/clip.mp4'}}},{Authorization:'Bearer '+token,Accept:'application/json, text/event-stream'});
  for(let i=0;i<3;i++)await phone.serve(pullHandler);
  const pulled=(await (await pullReq).json()).result;
  assert.equal(pullCalls,3);assert.equal(pulled.structuredContent.bytes,big.length);assert.equal(pulled.structuredContent.sha256,bigSha);
  assert.match(pulled.structuredContent.downloadUrl,/\/work\/artifacts\/art_[a-f0-9]{24}\/download$/);
  const dl=await fetch(h.endpoint+'/work/artifacts/'+pulled.structuredContent.artifactId+'/download',{headers:{Authorization:'Bearer '+pulled.structuredContent.downloadToken}});
  assert.equal(dl.status,200);assert.equal(dl.headers.get('x-content-sha256'),bigSha);
  assert.ok(Buffer.from(await dl.arrayBuffer()).equals(big));
  // A phone that paused access answers with a distinct code.
  const paused=await h.call(token,'android_packages',{device:'phone',package:'com.example'},phone,()=>{throw new Error('Agent paused or task permit expired');});
  assert.equal(paused.structuredContent.code,'device_paused');
}),{timeout:120000});

test('a phone that dropped its link is reported, reconnects under the same device ID and keeps every grant',async t=>withRelay(async ctx=>{
  const h=await harness(t,ctx);
  const codex=await h.register('http://localhost:1455/callback','Codex'),cursor=await h.register('https://cursor.com/oauth/callback','Cursor');
  const {phone,token}=await h.enrollFirst(codex,'NX809J');await phone.online();
  const cursorToken=await h.consent(cursor,{approve:true,allDevices:true});
  const [deviceId]=Object.keys(JSON.parse(fs.readFileSync(path.join(h.directory,'devices.json'))));
  const before=JSON.parse(fs.readFileSync(path.join(h.directory,'phones',deviceId+'.json')));
  // The user removed the link in the phone app (or reinstalled it).
  await phone.revokeLink();
  const lost=await h.call(token,'android_packages',{device:'phone',package:'com.example'});assert.equal(lost.structuredContent.code,'device_link_revoked');assert.match(lost.structuredContent.error,/Reconnect/);
  const listed=await h.call(cursorToken,'android_devices',{});assert.equal(listed.structuredContent.devices[0].status,'needs_reconnect');assert.equal(listed.structuredContent.devices[0].id,deviceId);
  const account=await (await h.get('/work/account')).text();assert.match(account,/Needs reconnecting/);assert.match(account,new RegExp('/work/enroll\\?device='+deviceId));
  assert.equal((await h.get('/work/enroll?device=phone_'+'0'.repeat(32))).status,404);
  // Reconnect: a fresh pairing replaces the link behind the same ID.
  const replacement=await h.enroll('?device='+deviceId,'NX809J');await replacement.online();
  assert.deepEqual(Object.keys(JSON.parse(fs.readFileSync(path.join(h.directory,'devices.json')))),[deviceId]);
  const after=JSON.parse(fs.readFileSync(path.join(h.directory,'phones',deviceId+'.json')));assert.notEqual(after.links.phone.linkKey,before.links.phone.linkKey);assert.notEqual(after.apiKey,before.apiKey);
  for(const tok of [token,cursorToken]){const ok=await h.call(tok,'android_packages',{device:'phone',package:'com.example'},replacement,()=>({ok:true,value:'NX809J'}));assert.equal(ok.structuredContent.value,'NX809J');}
  assert.equal((await h.call(token,'android_devices',{})).structuredContent.devices[0].status,'online');
  assert.equal(h.connections().list(JSON.parse(fs.readFileSync(path.join(h.directory,'devices.json')))[deviceId].ownerId).length,2,'both grants survive the reconnect');
  // Job status lookups go through the current link.
  const fake=await h.call(token,'android_job_status',{device:'phone',jobId:'call-0123456789abcdef'});assert.equal(fake.isError,true);assert.equal(fake.structuredContent.code,'job_not_found');assert.equal(fake.structuredContent.outcome,'unknown');assert.equal(fake.structuredContent.inputDelivered,null);assert.equal(fake.structuredContent.jobId,'call-0123456789abcdef');
}));

test('the phone app manages the account: link-key session, devices, assistants, add-device code and OAuth approval typed on the phone',async t=>withRelay(async ctx=>{
  const h=await harness(t,ctx);
  const codex=await h.register('http://127.0.0.1:1455/callback','Codex');
  const {phone}=await h.enrollFirst(codex,'NX809J');
  const api=(route,body,token)=>fetch(h.endpoint+route,{method:'POST',headers:{'Content-Type':'application/json',origin,...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body)}).then(async r=>({status:r.status,body:await r.json()}));
  // A phone that is not linked cannot start.
  assert.equal((await api('/work/phone/challenge',{agentId:'agt_nobody_nobody_nobody'})).status,404);
  // The linked phone proves its key.
  const challenge=await api('/work/phone/challenge',{agentId:phone.agentId});assert.equal(challenge.status,200);
  const bad=await api('/work/phone/session',{agentId:phone.agentId,nonce:challenge.body.nonce,mac:crypto.randomBytes(32).toString('base64url')});assert.equal(bad.status,403);
  const again=await api('/work/phone/challenge',{agentId:phone.agentId});
  const mac=crypto.createHmac('sha256',phone.key).update('backstage owner v1\n'+again.body.nonce).digest('base64url');
  const session=await api('/work/phone/session',{agentId:phone.agentId,nonce:again.body.nonce,mac});assert.equal(session.status,200,JSON.stringify(session.body));
  const token=session.body.token;
  const account=async()=>{const r=await fetch(h.endpoint+'/work/phone/account',{headers:{Authorization:'Bearer '+token}});assert.equal(r.status,200);return r.json();};
  let view=await account();
  assert.equal(view.devices.length,1);assert.equal(view.devices[0].thisDevice,true);assert.equal(view.assistants.length,1);assert.equal(view.assistants[0].icon,'codex');assert.equal(view.assistants[0].access,'all');
  // Rename from the phone; the same device ID stays.
  const renamed=await api('/work/phone/devices/rename',{deviceId:view.devices[0].id,name:'my phone'},token);assert.equal(renamed.status,200);assert.equal(renamed.body.device.name,'my phone');
  assert.equal((await api('/work/phone/devices/remove',{deviceId:view.devices[0].id},token)).status,400,'a phone cannot remove itself');
  // Add a tablet: this phone shows a code, the tablet claims it.
  const add=await api('/work/phone/devices/add',{name:'tablet'},token);assert.equal(add.status,200);assert.match(add.body.code,/^[A-Z0-9]{4}-[A-Z0-9]{6}$/);
  assert.deepEqual((await api('/work/phone/devices/add/poll',{ticket:add.body.ticket},token)).body,{pending:true});
  const tablet=await fakePhone(ctx.url,add.body.code,'SM-X700');
  const joined=await api('/work/phone/devices/add/poll',{ticket:add.body.ticket},token);assert.match(joined.body.deviceId,/^phone_[a-f0-9]{32}$/);
  view=await account();assert.deepEqual(view.devices.map(d=>d.name).sort(),['my phone','tablet']);
  // A new assistant connects without a passkey: the browser shows a code, the phone approves it for one device.
  const verifier=crypto.randomBytes(32).toString('base64url');
  const cursor=await h.register('https://cursor.com/callback','Cursor');
  const params=new URLSearchParams({client_id:cursor.client_id,redirect_uri:cursor.redirect,response_type:'code',resource:base+'/mcp',state:'s2',code_challenge_method:'S256',code_challenge:crypto.createHash('sha256').update(verifier).digest('base64url')});
  const page=await fetch(h.endpoint+'/work/authorize?'+params,{redirect:'manual'});assert.equal(page.status,200);const html=await page.text();
  const approvalCode=/data-approval="([A-Z2-9]{4}-[A-Z2-9]{4})"/.exec(html)[1],ticket=/ticket="([^"]+)"/.exec(html)[1];
  assert.deepEqual((await api('/work/authorize',{ticket})).body,{pending:true,denied:false});
  assert.equal((await api('/work/phone/approvals/preview',{code:'ZZZZ-ZZZZ'},token)).status,404);
  const preview=await api('/work/phone/approvals/preview',{code:approvalCode.toLowerCase()},token);assert.equal(preview.status,200);assert.equal(preview.body.clientName,'Cursor');assert.equal(preview.body.icon,'cursor');
  const tabletId=view.devices.find(d=>d.name==='tablet').id;
  const approved=await api('/work/phone/approvals/approve',{code:approvalCode,devices:[tabletId]},token);assert.equal(approved.status,200,JSON.stringify(approved.body));
  const done=await api('/work/authorize',{ticket});assert.ok(done.body.redirect,JSON.stringify(done.body));
  const callback=new URL(done.body.redirect);
  const tok=await fetch(h.endpoint+'/work/token',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({grant_type:'authorization_code',code:callback.searchParams.get('code'),redirect_uri:cursor.redirect,client_id:cursor.client_id,code_verifier:verifier,resource:base+'/mcp'})});
  assert.equal(tok.status,200);const cursorToken=(await tok.json()).access_token;
  const list=await h.call(cursorToken,'android_devices',{});assert.deepEqual(list.structuredContent.devices.map(d=>d.name),['tablet'],'Cursor sees only the device approved on the phone');
  const denied=await h.call(cursorToken,'android_packages',{device:'my phone',package:'com.example'});assert.equal(denied.structuredContent.code,'device_not_authorized');
  // The phone widens Cursor's access, then removes Codex; Codex's token stops working while Cursor keeps going.
  view=await account();const cursorGrant=view.assistants.find(a=>a.name==='Cursor'),codexGrant=view.assistants.find(a=>a.name==='Codex');
  assert.equal((await api('/work/phone/assistants/access',{grantId:cursorGrant.id,allDevices:true},token)).body.access,'all');
  assert.equal((await api('/work/phone/assistants/remove',{grantId:codexGrant.id},token)).status,200);
  const ok=await h.call(cursorToken,'android_packages',{device:'my phone',package:'com.example'},phone,()=>({ok:true,value:'NX809J'}));assert.ok(!ok.isError,JSON.stringify(ok.structuredContent));
  view=await account();assert.deepEqual(view.assistants.map(a=>a.name),['Cursor']);
  // Denying a code ends that approval.
  const page2=await fetch(h.endpoint+'/work/authorize?'+params,{redirect:'manual'});const html2=await page2.text();const code2=/data-approval="([A-Z2-9]{4}-[A-Z2-9]{4})"/.exec(html2)[1],ticket2=/ticket="([^"]+)"/.exec(html2)[1];
  assert.equal((await api('/work/phone/approvals/deny',{code:code2},token)).body.denied,true);
  assert.equal((await api('/work/authorize',{ticket:ticket2})).body.denied,true);
  // Sessions end.
  h.advance(13*3600000);
  assert.equal((await fetch(h.endpoint+'/work/phone/account',{headers:{Authorization:'Bearer '+token}})).status,401);
  void tablet;
}));

test('write calls auto-generate unique requestId when omitted while preserving explicit idempotency',async t=>withRelay(async ctx=>{
  const h=await harness(t,ctx);
  const codex=await h.register('http://localhost:1455/callback','Codex');
  const {phone,token}=await h.enrollFirst(codex,'NX809J');await phone.online();
  const seen=[];
  const postMcp=(name,args)=>h.post('/work/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name,arguments:args}},{Authorization:'Bearer '+token,Accept:'application/json, text/event-stream'});
  // Two write calls without requestId both succeed with distinct auto_* IDs (no request_id_conflict).
  for(const pkg of ['com.example.one','com.example.two']){
    const req=postMcp('android_task',{device:'phone',operation:'begin',package:pkg});
    await phone.serve(call=>{seen.push(call.arguments.requestId);return {outcome:'succeeded',sessionId:'sess_'+pkg};});
    const res=(await (await req).json()).result;
    assert.ok(!res.isError,JSON.stringify(res.structuredContent));
  }
  assert.equal(seen.length,2);
  assert.match(seen[0],/^auto_[a-f0-9]{24}$/);
  assert.match(seen[1],/^auto_[a-f0-9]{24}$/);
  assert.notEqual(seen[0],seen[1]);
  // Read-only calls without requestId do not receive one.
  let readArgs=null;
  const readReq=postMcp('android_task',{device:'phone',operation:'list'});
  await phone.serve(call=>{readArgs=call.arguments;return {outcome:'succeeded',sessions:[]};});
  assert.ok(!(await (await readReq).json()).result.isError);
  assert.equal(readArgs.requestId,undefined);
  // Explicit duplicate requestId with different arguments still returns request_id_conflict.
  const first=postMcp('android_task',{device:'phone',operation:'begin',package:'com.example.a',requestId:'explicit-1'});
  await phone.serve(()=>({outcome:'succeeded',sessionId:'sess_a'}));
  assert.ok(!(await (await first).json()).result.isError);
  const conflict=(await (await postMcp('android_task',{device:'phone',operation:'begin',package:'com.example.b',requestId:'explicit-1'})).json()).result;
  assert.equal(conflict.structuredContent.code,'request_id_conflict');
}));
