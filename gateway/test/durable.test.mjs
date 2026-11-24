import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {createWorkApp} from '../server.mjs';
import {createOwners} from '../owners.mjs';
import {createConnections} from '../connections.mjs';
import {withRelay,httpJson,deviceHeaders,hello} from '../../relay/test/helpers.mjs';
import {Spake2,ALICE_NAME,BOB_NAME} from '../../cli/spake2.mjs';
import {linkKey,confirmB,b64url,unb64url,openEnvelope,callKey,resultKey,sealEnvelopeV2} from '../../cli/crypto.mjs';
import {passkeyFixture,cookies} from './passkey-fixture.mjs';
const base='https://relay.example/work',origin=new URL(base).origin;
test('one phone enrollment serves local and cloud MCP clients after restart, refresh, reauthorization and separate revocation',async()=>withRelay(async({url,dataDir})=>{
 const stateFile=path.join(dataDir,'oauth.json'),ownerFile=path.join(dataDir,'owners.json'),directory=path.join(dataDir,'connections');let server,endpoint,jar='',clock=Date.now();
 const start=async()=>{
  const connections=createConnections({directory,relayUrl:url}),owners=createOwners({stateFile:ownerFile,origin,now:()=>clock});
  const app=createWorkApp({base,stateFile,connections,owners,enforcePlans:false,now:()=>clock,callTool:(...args)=>connections.call(...args)});
  server=await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s));});endpoint='http://127.0.0.1:'+server.address().port;
 };
 await start();
 const post=(route,body,headers={})=>fetch(endpoint+route,{method:'POST',redirect:'manual',headers:{'Content-Type':'application/json',origin,cookie:jar,...headers},body:JSON.stringify(body)});
 const get=route=>fetch(endpoint+route,{headers:{cookie:jar},redirect:'manual'});
 try{
  const bundle=await get('/work/passkeys.js');assert.equal(bundle.status,200);assert.match(await bundle.text(),/SimpleWebAuthnBrowser/);
  const key=passkeyFixture(origin);let r=await post('/work/passkeys/options',{mode:'register'});const opts=await r.json();jar=cookies(r,jar);
  r=await post('/work/passkeys/verify',key.register(opts));assert.equal(r.status,200);jar=cookies(r,jar);
  const ownerJar=jar;
  const register=async(redirect,name)=>{const r=await post('/work/register',{redirect_uris:[redirect],client_name:name});assert.equal(r.status,201);return {...await r.json(),redirect};};
  const A=await register('http://localhost:8080/oauth/callback','Local client'),B=await register('https://cloud-client.example/oauth/callback','Cloud client');
  async function authorize(client){const verifier=crypto.randomBytes(32).toString('base64url');const params={passkey:'1',client_id:client.client_id,redirect_uri:client.redirect,response_type:'code',resource:base+'/mcp',state:'test-state',code_challenge_method:'S256',code_challenge:crypto.createHash('sha256').update(verifier).digest('base64url')};const r=await get('/work/authorize?'+new URLSearchParams(params));assert.equal(r.status,200);const html=await r.text();return {verifier,html,ticket:/const ticket="([^"]+)"|ticket="([^"]+)"/.exec(html)?.slice(1).find(Boolean)};}
  const first=await authorize(A);assert.ok(first.ticket);const code=/<code>([^<]+)<\/code>/.exec(first.html)[1];
  const bob=new Spake2('bob',BOB_NAME,ALICE_NAME),session=code.split('-')[0];
  const claim=await httpJson(url+'/v1/pair/session/'+session+'/device',{method:'POST',body:{msg:b64url(bob.generateMsg(Buffer.from(code))),model:'Test phone'}});assert.equal(claim.status,200);
  const phoneKey=linkKey(bob.processMsg(unb64url(claim.body.agentMsg)));
  await httpJson(url+'/v1/pair/session/'+session+'/device-confirm',{method:'PUT',headers:deviceHeaders(claim.body.deviceToken),body:{confirm:b64url(confirmB(phoneKey))}});
  async function finish(client,flow){const approved=await post('/work/authorize',{ticket:flow.ticket,approve:true});assert.equal(approved.status,200);const callback=new URL((await approved.json()).redirect);const r=await post('/work/token',{grant_type:'authorization_code',client_id:client.client_id,resource:base+'/mcp',redirect_uri:client.redirect,code:callback.searchParams.get('code'),code_verifier:flow.verifier});assert.equal(r.status,200);return r.json();}
  let tokensA=await finish(A,first);
  const second=await authorize(B);assert.ok(!second.html.includes('<svg'));assert.match(second.html,/Allow Cloud client/);const tokensB=await finish(B,second);
  assert.equal(fs.readdirSync(path.join(directory,'phones')).filter(x=>x.endsWith('.json')).length,1);
  const phonePath=path.join(directory,'phones',fs.readdirSync(path.join(directory,'phones'))[0]),before=JSON.parse(fs.readFileSync(phonePath));
  await new Promise(resolve=>server.close(resolve));await start();jar=ownerJar;
  await hello(url,claim.body.deviceToken);
  let counter=1;
  async function call(token,value){
   const result=post('/work/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'android_packages',arguments:{package:'com.example'}}},{Authorization:'Bearer '+token,Accept:'application/json, text/event-stream'});
   const job=await httpJson(url+'/v1/device/next',{method:'POST',headers:deviceHeaders(claim.body.deviceToken),body:{}});assert.ok(job.body.id);
   const plain=JSON.parse(openEnvelope({key:callKey(phoneKey),envelope:job.body.envelope}));assert.equal(plain.name,'android.packages');assert.equal(plain.deviceId,claim.body.deviceId);
   const envelope=sealEnvelopeV2({key:resultKey(phoneKey),from:claim.body.deviceId,n:String(counter++),plaintext:JSON.stringify({id:plain.id,result:{ok:true,value}})});
   await httpJson(url+'/v1/device/result',{method:'POST',headers:deviceHeaders(claim.body.deviceToken),body:{id:job.body.id,envelope}});
   const r=await result;assert.equal(r.status,200);assert.equal((await r.json()).result.structuredContent.value,value);
  }
  await call(tokensA.access_token,'local-result');await call(tokensB.access_token,'cloud-result');
  const refreshed=await post('/work/token',{grant_type:'refresh_token',client_id:A.client_id,resource:base+'/mcp',refresh_token:tokensA.refresh_token});assert.equal(refreshed.status,200);tokensA=await refreshed.json();
  const reauth=await authorize(A);assert.ok(!reauth.html.includes('<svg'));await finish(A,reauth);
  const account=await (await get('/work/account')).text();assert.equal((account.match(/data-grant=/g)||[]).length,2);
  const grants=new Map(fs.readdirSync(directory).filter(x=>/^grant_.*\.json$/.test(x)).map(x=>[JSON.parse(fs.readFileSync(path.join(directory,x))).clientId,x.slice(0,-5)]));
  assert.equal((await post('/work/account/revoke',{grantId:grants.get(A.client_id)},{origin:'https://evil.example'})).status,403);
  assert.equal((await post('/work/account/revoke',{grantId:grants.get(A.client_id)})).status,200);
  assert.equal((await post('/work/mcp',{}, {Authorization:'Bearer '+tokensA.access_token})).status,401);
  await call(tokensB.access_token,'still-connected');
  const after=JSON.parse(fs.readFileSync(phonePath));assert.equal(after.agentId,before.agentId);assert.equal(after.links[after.device].linkKey,before.links[before.device].linkKey);
  jar='';const stranger=await get('/work/account');assert.equal(stranger.status,303);assert.equal((await post('/work/account/revoke',{grantId:grants.get(B.client_id)})).status,401);
  // A new browser signs into the existing owner with its passkey, without pairing.
  r=await post('/work/passkeys/options',{mode:'authenticate'});const authOpts=await r.json();jar=cookies(r,jar);r=await post('/work/passkeys/verify',key.authenticate(authOpts));assert.equal(r.status,200);jar=cookies(r,jar);
  assert.match(await (await get('/work/account')).text(),/Cloud client/);
 }finally{await new Promise(resolve=>server.close(resolve));}
}));
