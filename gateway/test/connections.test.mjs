import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {withRelay,httpJson,deviceHeaders,hello} from '../../relay/test/helpers.mjs';
import {Spake2,ALICE_NAME,BOB_NAME} from '../../cli/spake2.mjs';
import {linkKey,confirmB,confirmA,b64url,unb64url,openEnvelope,callKey,resultKey,sealEnvelopeV2} from '../../cli/crypto.mjs';
import {createWorkApp} from '../server.mjs';
import {createConnections} from '../connections.mjs';

const base='https://relay.android-backstage.com/work',redirect='https://chatgpt.com/connector_platform_oauth_redirect';
test('phone-approved OAuth gives two users separate encrypted MCP calls, persists grants and revokes a family',async t=>withRelay(async({url,dataDir})=>{
 const directory=path.join(dataDir,'gateway-links'),stateFile=path.join(dataDir,'oauth.json');
 let connections=createConnections({directory,relayUrl:url});
 const makeApp=()=>createWorkApp({base,stateFile,connections,callTool:(...args)=>connections.call(...args)});
 const app=makeApp();
 let server=await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s));});t.after(()=>server.close());
 let origin='http://127.0.0.1:'+server.address().port;
 const post=(route,body,headers={})=>fetch(origin+route,{method:'POST',headers:{'Content-Type':'application/json',...headers},body:JSON.stringify(body)});
 const registered=await post('/work/register',{redirect_uris:[redirect]});const {client_id}=await registered.json();
 async function authorize(){
   const verifier=crypto.randomBytes(32).toString('base64url');
   const page=await fetch(origin+'/work/authorize?'+new URLSearchParams({client_id,redirect_uri:redirect,response_type:'code',resource:base+'/mcp',state:'a',code_challenge_method:'S256',code_challenge:crypto.createHash('sha256').update(verifier).digest('base64url')}));
   assert.equal(page.status,200);const html=await page.text();assert.ok(!html.includes('backstage-work-approve'));assert.ok(!html.includes('accounts.google'));
   const ticket=/const ticket="([^"]+)"/.exec(html)[1],code=/<code>([^<]+)<\/code>/.exec(html)[1];
   const session=code.split('-')[0],bob=new Spake2('bob',BOB_NAME,ALICE_NAME);
   const claim=await httpJson(url+'/v1/pair/session/'+session+'/device',{method:'POST',body:{msg:b64url(bob.generateMsg(Buffer.from(code))),model:'Test'}});assert.equal(claim.status,200);
   const key=linkKey(bob.processMsg(unb64url(claim.body.agentMsg)));
   assert.equal((await httpJson(url+'/v1/pair/session/'+session+'/device-confirm',{method:'PUT',headers:deviceHeaders(claim.body.deviceToken),body:{confirm:b64url(confirmB(key))}})).status,200);
   const approval=await post('/work/authorize',{ticket},{origin:new URL(base).origin});assert.equal(approval.status,200);
   const callback=new URL((await approval.json()).redirect);
   const body={grant_type:'authorization_code',client_id,resource:base+'/mcp',redirect_uri:redirect,code:callback.searchParams.get('code'),code_verifier:verifier};
   assert.equal((await post('/work/token',{...body,code_verifier:'wrong'})).status,400);
   const tokens=await (await post('/work/token',body)).json();assert.ok(tokens.access_token);
   assert.equal((await post('/work/token',body)).status,400);
   assert.equal((await post('/work/authorize',{ticket},{origin:new URL(base).origin})).status,403);
   return {tokens,claim:claim.body,key};
 }
 const A=await authorize(),B=await authorize();
 assert.notEqual(A.claim.deviceId,B.claim.deviceId);
 // Reload link storage, and poison process defaults: these must never redirect a user's call.
 connections=createConnections({directory,relayUrl:url});
 await new Promise(resolve=>server.close(resolve));
 server=await new Promise(resolve=>{const s=makeApp().listen(0,'127.0.0.1',()=>resolve(s));});
 origin='http://127.0.0.1:'+server.address().port;
 const oldKey=process.env.BACKSTAGE_API_KEY,oldRelay=process.env.BACKSTAGE_RELAY_URL;
 process.env.BACKSTAGE_API_KEY='invalid-host-key';process.env.BACKSTAGE_RELAY_URL='http://127.0.0.1:1';
 try {
   for(const [phone,value] of [[A,'phone A'],[B,'phone B']]) {
     await hello(url,phone.claim.deviceToken);
     const call=post('/work/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'android_packages',arguments:{package:'com.example'}}},{authorization:'Bearer '+phone.tokens.access_token,accept:'application/json, text/event-stream'});
     const job=await httpJson(url+'/v1/device/next',{method:'POST',headers:deviceHeaders(phone.claim.deviceToken),body:{}});assert.ok(job.body.id);
     const plain=JSON.parse(openEnvelope({key:callKey(phone.key),envelope:job.body.envelope}));assert.equal(plain.deviceId,phone.claim.deviceId);assert.equal(plain.name,'android.packages');
     const envelope=sealEnvelopeV2({key:resultKey(phone.key),from:phone.claim.deviceId,n:'1',plaintext:JSON.stringify({id:plain.id,result:{ok:true,value}})});
     assert.equal((await httpJson(url+'/v1/device/result',{method:'POST',headers:deviceHeaders(phone.claim.deviceToken),body:{id:job.body.id,envelope}})).status,200);
     const response=await call;assert.equal(response.status,200);assert.equal((await response.json()).result.structuredContent.value,value);
   }
 } finally {if(oldKey===undefined)delete process.env.BACKSTAGE_API_KEY;else process.env.BACKSTAGE_API_KEY=oldKey;if(oldRelay===undefined)delete process.env.BACKSTAGE_RELAY_URL;else process.env.BACKSTAGE_RELAY_URL=oldRelay;}
 const refresh={grant_type:'refresh_token',client_id,resource:base+'/mcp',refresh_token:A.tokens.refresh_token};
 const renewed=await (await post('/work/token',refresh)).json();assert.ok(renewed.access_token);
 assert.equal((await post('/work/token',refresh)).status,400);
 assert.equal((await post('/work/revoke',{client_id,token:renewed.refresh_token})).status,200);
 for(const token of [A.tokens.access_token,renewed.access_token]) assert.equal((await post('/work/mcp',{}, {authorization:'Bearer '+token})).status,401);
 assert.equal((await post('/work/token',{...refresh,refresh_token:renewed.refresh_token})).status,400);
 const files=fs.readdirSync(directory).filter(f=>f.endsWith('.json'));assert.equal(files.length,1);
 assert.equal(fs.statSync(path.join(directory,files[0])).mode&0o777,0o600);
}));

test('an in-flight write cannot restore a revoked grant file',async()=>{
 const os=await import('node:os');const directory=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-revoke-'));
 let release,entered;const ready=new Promise(r=>entered=r);
 const id='grant_'+'a'.repeat(32),file=path.join(directory,id+'.json');fs.writeFileSync(file,JSON.stringify({device:'phone',links:{}}));
 const c=createConnections({directory,relayUrl:'https://example.com',call:async cfg=>{entered();await new Promise(r=>release=r);fs.writeFileSync(cfg.filePath,'{}');return {ok:true};}});
 try {const request=c.call('test',{},id);await ready;c.revoke(id);assert.equal(c.active(id),false);release();await request;assert.equal(c.active(id),false);assert.equal(fs.existsSync(file),false);
 const restarted=createConnections({directory,relayUrl:'https://example.com'});assert.equal(restarted.active(id),false);
 }finally{fs.rmSync(directory,{recursive:true,force:true});}
});
test('revoking a grant while it waits on the shared phone lock prevents dispatch and leaves the other grant active',async t=>{
 const os=await import('node:os');const {withConfigLock,writePrivateJson}=await import('../../cli/state.mjs');
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-shared-lock-'));t.after(()=>fs.rmSync(directory,{recursive:true,force:true}));
 const ownerId='owner_'+'a'.repeat(43),phoneId='phone_'+'b'.repeat(32),idA='grant_'+'c'.repeat(32),idB='grant_'+'d'.repeat(32);
 const c=createConnections({directory,relayUrl:'http://127.0.0.1:1'}),phonePath=path.join(directory,'phones',phoneId+'.json');
 writePrivateJson(phonePath,{device:'phone',links:{}});
 for(const id of [idA,idB])writePrivateJson(path.join(directory,id+'.json'),{ownerId,phoneId});
 let release,entered;const ready=new Promise(r=>entered=r),hold=withConfigLock(phonePath,async()=>{entered();await new Promise(r=>release=r);});await ready;
 const request=c.call('android.packages',{package:'com.example'},idA);const denied=assert.rejects(request,/access was removed/);
 c.revoke(idA);release();await hold;await denied;
 assert.equal(c.active(idA),false);assert.equal(c.active(idB),true);assert.deepEqual(JSON.parse(fs.readFileSync(phonePath)),{device:'phone',links:{}});
});

test('submission errors retain no-input certainty without an invented recovery job',async()=>{
 const {classifyCallError}=await import('../connections.mjs');
 const busy=classifyCallError(Object.assign(Error('queue full'),{code:'relay_busy',inputDelivered:false}),{name:'Redmagic'});
 assert.equal(busy.code,'relay_busy');assert.equal(busy.inputDelivered,false);assert.equal(busy.jobId,undefined);
 const rejected=classifyCallError(Object.assign(Error('submission aborted by relay validation'),{code:'submission_rejected',inputDelivered:false}),{name:'Redmagic'});
 assert.equal(rejected.code,'submission_rejected');assert.equal(rejected.inputDelivered,false);
 const unknown=classifyCallError(Object.assign(Error('fetch failed'),{jobId:'call-123456789abcdef0',inputDelivered:null}),{name:'Redmagic'});
 assert.equal(unknown.jobId,'call-123456789abcdef0');
});

test('a failed phone download is a definite tool failure, even when curl says it timed out',async()=>{
 const {classifyCallError}=await import('../connections.mjs');
 const text='job call-4d1718a063ff2266: command failed (28): rm -f /data/local/tmp/backstage/dl-1.bsf1; curl -L --fail --connect-timeout 15 -o dl http://100.64.0.10:18426/v1/files/f\ncurl: (28) Connection timed out after 15002 milliseconds';
 const failed=classifyCallError(Object.assign(Error(text),{inputDelivered:null}),{name:'Redmagic'});
 assert.equal(failed.code,'tool_failed');assert.equal(failed.jobId,'call-4d1718a063ff2266');assert.match(failed.message,/^command failed \(28\)/);
 const lost=classifyCallError(Object.assign(Error('job call-123456789abcdef0 timed out. Query its status'),{inputDelivered:null}),{name:'Redmagic'});
 assert.equal(lost.code,'uncertain');
});
