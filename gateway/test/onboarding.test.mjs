import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createOwners,challengeCookie} from '../owners.mjs';
import {createAuth} from '../auth.mjs';
import {createWorkApp} from '../server.mjs';
import {androidOrigins,assetLinks} from '../native-sign-in.mjs';
import {passkeyFixture} from './passkey-fixture.mjs';
import {connect} from '../agent-connector.mjs';
import express from 'express';

const base='https://relay.example/work';
const proof=()=>{const verifier=crypto.randomBytes(32).toString('base64url');return {verifier,challenge:crypto.createHash('sha256').update(verifier).digest('base64url')};};
function fixture(t){
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-onboarding-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  let clock=Date.now();const grants=[];
  const connections={authorize(ownerId,clientId,clientName,access){grants.push({ownerId,clientId,clientName,access});return 'grant_'+grants.length;},active:()=>true};
  const auth=createAuth({base,stateFile:path.join(dir,'auth.json'),connections,owners:{owner:()=>null},now:()=>clock});
  return {dir,auth,grants,connections,advance:()=>clock+=600001};
}
test('native passkeys register and reuse web accounts; origin, channel, replay and expiry are enforced',async t=>{
  const f=fixture(t),owners=createOwners({stateFile:path.join(f.dir,'owners.json'),origin:new URL(base).origin,androidOrigins});
  let ticket;const response={append(_k,v){if(v.startsWith(challengeCookie+'='))ticket=v.split(';')[0].slice(challengeCookie.length+1);}};
  const req=()=>({headers:{cookie:challengeCookie+'='+ticket}});
  const key=passkeyFixture(androidOrigins[0],new URL(base).hostname);
  let options=await owners.options({headers:{}},response,'register',true);
  const id=await owners.verify(req(),{append(){}},key.register(options),true);assert.ok(id.startsWith('owner_'));
  await assert.rejects(owners.verify(req(),response,key.register(options),true),/expired/);
  options=await owners.options({headers:{}},response,'authenticate',true);
  assert.equal(await owners.verify(req(),{append(){}},key.authenticate(options),true),id);
  options=await owners.options({headers:{}},response,'authenticate',true);
  await assert.rejects(owners.verify(req(),response,key.authenticate(options,{origin:'android:apk-key-hash:evil'}),true));
  options=await owners.options({headers:{}},response,'authenticate',true);
  await assert.rejects(owners.verify(req(),response,key.authenticate(options)),/expired/);
  options=await owners.options({headers:{}},response,'authenticate');
  assert.equal(await owners.verify(req(),{append(){}},key.authenticate(options,{origin:new URL(base).origin})),id);
  options=await owners.options({headers:{}},response,'authenticate');
  await assert.rejects(owners.verify(req(),response,key.authenticate(options),true),/expired/);
});
test('phone-minted 144-bit invitation approves in one step, binds one account, checks PKCE, and cannot be reused',t=>{
  const f=fixture(t),p=proof();const invite=f.auth.invites.create('owner_a','phone_a');
  const claim=f.auth.invites.claim({code:invite.code,clientName:'Test agent',challenge:p.challenge});
  assert.equal(claim.approved,true);
  assert.equal(f.grants.length,1);assert.deepEqual(f.grants[0].access,'all');
  assert.throws(()=>f.auth.invites.claim({code:invite.code,clientName:'Other',challenge:p.challenge}));
  assert.throws(()=>f.auth.invites.status(invite.code,'owner_b'));
  assert.throws(()=>f.auth.invites.poll(claim.ticket,'wrong'));
  const result=f.auth.invites.poll(claim.ticket,p.verifier);assert.ok(result.access_token&&result.refresh_token&&result.client_id);
  assert.deepEqual(f.auth.invites.poll(claim.ticket,p.verifier),result,'retry after a lost reply returns the same token');
  assert.equal(f.auth.invites.status(invite.code,'owner_a').state,'approved');
  f.advance();assert.throws(()=>f.auth.invites.poll(claim.ticket,p.verifier));
});
test('cancelled or expired invitations cannot be claimed',t=>{
  for(const operation of ['cancel','expire']){
    const f=fixture(t),p=proof(),invite=f.auth.invites.create('owner_a','phone_a');
    if(operation==='cancel')f.auth.invites.cancel(invite.code,'owner_a');
    if(operation==='expire')f.advance();
    assert.throws(()=>f.auth.invites.claim({code:invite.code,clientName:'Test',challenge:p.challenge}));
    assert.equal(f.grants.length,0);
  }
});
test('an interrupted local claim resumes with the same proof without granting another agent access',async t=>{
  const f=fixture(t),invite=f.auth.invites.create('owner_a','phone_a'),folder=path.join(f.dir,'local');let first=true,original;
  const post=async(url,body)=>{
    if(url.endsWith('/connect/claim')){
      const claim=f.auth.invites.claim(body);
      if(first){first=false;original=claim;throw Error('lost response');}
      assert.equal(claim.ticket,original.ticket);assert.equal(claim.approved,true);
      return claim;
    }
    return f.auth.invites.poll(body.ticket,body.verifier);
  };
  const args={code:invite.code,name:'Cursor',folder,endpoint:base,post,output:()=>{}};
  await assert.rejects(connect(args),/lost response/);assert.equal(f.grants.length,1);
  assert.throws(()=>f.auth.invites.claim({code:invite.code,clientName:'Cursor',challenge:proof().challenge}));
  const account=await connect(args);assert.ok(fs.existsSync(account));assert.equal(f.grants.length,1);assert.ok(!fs.existsSync(path.join(folder,'pending.json')));
});
test('native HTTP enrollment keeps codes internal and returns the same completion on retry',async t=>{
  const f=fixture(t);let began=0,polls=0;
  f.connections.begin=async({ownerId})=>{assert.ok(ownerId.startsWith('owner_'));began++;return {code:'ABCD-EFGHJK',cancel:async()=>{},poll:async()=>++polls===1?null:'phone_new'};};
  const owners=createOwners({stateFile:path.join(f.dir,'owners.json'),origin:new URL(base).origin,androidOrigins});
  const app=createWorkApp({base,stateFile:path.join(f.dir,'oauth-http.json'),connections:f.connections,owners});
  const server=await new Promise(r=>{const s=app.listen(0,'127.0.0.1',()=>r(s));});t.after(()=>server.close());
  const url='http://127.0.0.1:'+server.address().port;
  const post=async(route,body)=>{const r=await fetch(url+route,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});return {status:r.status,body:await r.json()};};
  assert.deepEqual(await (await fetch(url+'/.well-known/assetlinks.json')).json(),assetLinks);
  const start=await post('/work/native/options',{mode:'register',installation:crypto.randomBytes(32).toString('base64url')});assert.equal(start.status,200);
  const key=passkeyFixture(androidOrigins[0],new URL(base).hostname);
  const enrollment=await post('/work/native/verify',{ticket:start.body.ticket,response:key.register(start.body.options)});assert.equal(enrollment.status,200);assert.equal(began,1);
  assert.equal((await post('/work/native/enroll',{ticket:'wrong'})).status,410);
  assert.deepEqual((await post('/work/native/enroll',{ticket:enrollment.body.ticket})).body,{pending:true});
  for(let i=0;i<2;i++)assert.deepEqual((await post('/work/native/enroll',{ticket:enrollment.body.ticket})).body,{deviceId:'phone_new'});
  assert.equal(polls,2);
  assert.equal((await post('/work/native/verify',{ticket:start.body.ticket,response:key.register(start.body.options)})).status,400);
});
test('local setup saves private credentials in one step without printing tokens',async t=>{
  const f=fixture(t),invite=f.auth.invites.create('owner_a','phone_a'),messages=[];
  const file=await connect({code:invite.code,name:'Local test',folder:path.join(f.dir,'client'),endpoint:base,output:line=>messages.push(line),post:async(url,body)=>{
    if(url.endsWith('/claim'))return f.auth.invites.claim(body);
    return f.auth.invites.poll(body.ticket,body.verifier);
  }});
  const state=JSON.parse(fs.readFileSync(file));assert.equal(fs.statSync(file).mode&0o777,0o600);assert.ok(state.access_token);
  assert.ok(messages.every(m=>!m.includes(state.access_token)&&!m.includes(state.refresh_token)));
});
test('cloud invitation completes standard OAuth in one step with callback, state, PKCE and token refresh',async t=>{
  const f=fixture(t),p=proof(),app=express();app.use(express.json());f.auth.routes(app);
  app.get('/probe',f.auth.requireToken,(_req,res)=>res.json({ok:true}));
  const server=await new Promise(r=>{const s=app.listen(0,'127.0.0.1',()=>r(s));});t.after(()=>server.close());
  const url='http://127.0.0.1:'+server.address().port;
  const post=async(route,body)=>{const r=await fetch(url+route,{method:'POST',headers:{'Content-Type':'application/json',origin:new URL(base).origin},body:JSON.stringify(body)});return {status:r.status,body:await r.json()};};
  const client=(await post('/work/register',{client_name:'Cloud agent',redirect_uris:['https://agent.example/callback']})).body;
  const query={client_id:client.client_id,redirect_uri:'https://agent.example/callback',response_type:'code',resource:base+'/mcp',state:'keep-this-state',code_challenge_method:'S256',code_challenge:p.challenge};
  const html=await (await fetch(url+'/work/authorize?'+new URLSearchParams(query))).text();
  const ticket=/ticket="([^"]+)"/.exec(html)[1],invite=f.auth.invites.create('owner_a','phone_a');
  const claim=f.auth.invites.claim({code:invite.code,ticket});assert.equal(claim.clientName,'Cloud agent');assert.equal(claim.approved,true);
  const callback=new URL((await post('/work/authorize',{ticket})).body.redirect);assert.equal(callback.origin,'https://agent.example');assert.equal(callback.searchParams.get('state'),'keep-this-state');
  const body={grant_type:'authorization_code',client_id:client.client_id,resource:base+'/mcp',redirect_uri:query.redirect_uri,code:callback.searchParams.get('code'),code_verifier:p.verifier};
  assert.equal((await post('/work/token',{...body,code_verifier:'a'.repeat(43)})).status,400);
  const tokens=(await post('/work/token',body)).body;assert.ok(tokens.access_token);
  assert.equal((await post('/work/token',body)).status,400);
  const refreshed=await post('/work/token',{grant_type:'refresh_token',client_id:client.client_id,refresh_token:tokens.refresh_token,resource:base+'/mcp'});assert.equal(refreshed.status,200);
  assert.equal((await fetch(url+'/probe',{headers:{Authorization:'Bearer '+refreshed.body.access_token}})).status,200);
});

test('several invitations can wait at once and each approves in one step when claimed',t=>{
  const f=fixture(t),a=proof(),b=proof();
  const first=f.auth.invites.create('owner_a','phone_a'),second=f.auth.invites.create('owner_a','phone_a');
  const c1=f.auth.invites.claim({code:first.code,clientName:'Agent one',challenge:a.challenge});
  const c2=f.auth.invites.claim({code:second.code,clientName:'Agent two',challenge:b.challenge});
  assert.equal(c1.approved,true);assert.equal(c2.approved,true);
  assert.equal(f.grants.length,2);
});
