import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createWorkApp} from '../server.mjs';
const base='https://relay.example/work';
async function fixture(t){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-auth-'));let clock=Date.now(),finish,started;
 const polled=new Promise(r=>started=r),revoked=[];
 const connections={begin:async({clientName})=>({clientName,code:'ABCD-EFGHJK',link:'androidbackstage://connect?code=ABCD-EFGHJK',cancel:async()=>{},poll:async()=>{started();return new Promise(r=>finish=r);}}),active:()=>true,revoke:id=>revoked.push(id)};
 const app=createWorkApp({base,stateFile:path.join(dir,'oauth.json'),connections,now:()=>clock,callTool:async()=>{throw Error('must not call phone');}});
 const server=await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s));});
 t.after(()=>{server.close();fs.rmSync(dir,{recursive:true,force:true});});const url='http://127.0.0.1:'+server.address().port;
 const post=(route,body,origin=new URL(base).origin)=>fetch(url+route,{method:'POST',headers:{'Content-Type':'application/json',origin},body:JSON.stringify(body)});
 async function page(redirect='http://127.0.0.1:1455/callback',name='Codex'){
  const reg=await post('/work/register',{redirect_uris:[redirect],client_name:name});const client=await reg.json();
  assert.equal(reg.status,201);
  const response=await fetch(url+'/work/authorize?'+new URLSearchParams({client_id:client.client_id,redirect_uri:redirect,response_type:'code',resource:base+'/mcp',state:'test',code_challenge_method:'S256',code_challenge:'a'.repeat(43)}));
  assert.equal(response.status,200);const html=await response.text();assert.match(html,/Scan with that device/);assert.match(html,/<svg/);
  return {html,ticket:/const ticket="([^"]+)"/.exec(html)[1]};
 }
 return {post,page,polled,finish:id=>finish(id),revoked,advance:()=>clock+=600001};
}
test('native loopback callback works and untrusted client labels stay escaped',async t=>{
 const f=await fixture(t),p=await f.page(undefined,'Codex <img src=x>');assert.match(p.html,/Codex &lt;img src=x&gt;/);assert.ok(!p.html.includes('<img src=x>'));
 for(const callback of ['http://example.com/callback','https://user:password@example.com/callback','https://example.com/callback#secret'])assert.equal((await f.post('/work/register',{redirect_uris:[callback]})).status,400);
 for(const callback of ['http://localhost:1455/callback','https://another-client.example/callback'])assert.equal((await f.post('/work/register',{redirect_uris:[callback]})).status,201);
});
test('cancellation during phone approval never issues an OAuth code',async t=>{
 const f=await fixture(t),p=await f.page();const polling=f.post('/work/authorize',{ticket:p.ticket});await f.polled;
 assert.equal((await f.post('/work/authorize',{ticket:p.ticket,cancel:true})).status,200);
 f.finish('grant_'+'a'.repeat(32));assert.equal((await polling).status,403);assert.equal(f.revoked.length,1);
});
test('approval after ticket expiry fails closed and cross-origin approval is denied',async t=>{
 const f=await fixture(t),p=await f.page();assert.equal((await f.post('/work/authorize',{ticket:p.ticket},'https://evil.example')).status,403);
 const polling=f.post('/work/authorize',{ticket:p.ticket});await f.polled;f.advance();f.finish('grant_'+'b'.repeat(32));assert.equal((await polling).status,403);assert.equal(f.revoked.length,1);
});
test('metadata clients are bound to their declared callbacks and browser preflights',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-cimd-'));let reads=0;
 const clientId='https://client.example/oauth/client.json';
 const app=createWorkApp({base,stateFile:path.join(dir,'oauth.json'),owners:{owner:()=>null},connections:{},callTool:async()=>{},metadataReader:async id=>{assert.equal(id,clientId);reads++;return {name:'Cloud client',redirects:['https://client.example/callback'],method:'none',metadata:true,expires:Date.now()+300000};}});
 const server=await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s));});t.after(()=>{server.close();fs.rmSync(dir,{recursive:true,force:true});});const url='http://127.0.0.1:'+server.address().port;
 const query={client_id:clientId,redirect_uri:'https://client.example/callback',response_type:'code',resource:base+'/mcp',state:'cimd',code_challenge_method:'S256',code_challenge:'a'.repeat(43)};
 let r=await fetch(url+'/work/authorize?'+new URLSearchParams(query),{redirect:'manual'});assert.equal(r.status,200);{const html=await r.text();assert.ok(html.indexOf('Send to my phone')>=0&&html.indexOf('Send to my phone')<html.indexOf('Connection code from Backstage'));}assert.equal(reads,1);
 r=await fetch(url+'/work/authorize?'+new URLSearchParams({...query,passkey:'1'}),{redirect:'manual'});assert.equal(r.status,303);assert.ok(r.headers.get('location').startsWith('/work/sign-in?'));
 r=await fetch(url+'/work/authorize?'+new URLSearchParams({...query,redirect_uri:'https://client.example/other'}));assert.equal(r.status,400);assert.equal(reads,1);
 r=await fetch(url+'/work/mcp',{method:'OPTIONS',headers:{Origin:'https://client.example','Access-Control-Request-Method':'POST'}});assert.equal(r.status,204);assert.equal(r.headers.get('Access-Control-Allow-Origin'),'https://client.example');
 r=await fetch(url+'/work/mcp',{method:'OPTIONS',headers:{Origin:'https://evil.example'}});assert.equal(r.status,403);
 r=await fetch(url+'/.well-known/oauth-protected-resource/work/mcp');assert.equal((await r.json()).resource,base+'/mcp');
});
