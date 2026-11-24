import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { createWorkApp, catalogue, SERVER_INSTRUCTIONS } from '../server.mjs';
import { digest } from '../auth.mjs';
import { writePrivateJson } from '../../cli/state.mjs';
const base = 'https://relay.android-backstage.com/work', redirect = 'https://chatgpt.com/connector_platform_oauth_redirect';
async function fixture(t,{delayMs=0,keepAlive}={}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(),'backstage-work-test-'));
  const calls=[]; let clock=Date.now();
  const app=createWorkApp({base,now:()=>clock,stateFile:path.join(dir,'oauth.json'),approvalFile:path.join(dir,'approval.json'),keepAlive,callTool:async (tool,args)=>{calls.push({tool,args});if(delayMs)await new Promise(r=>setTimeout(r,delayMs));return {ok:true,value:'test-phone'};}});
  const server=await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s));});
  t.after(()=>{server.close();fs.rmSync(dir,{recursive:true,force:true});});
  const url='http://127.0.0.1:'+server.address().port;
  const post=(route,body,headers={})=>fetch(url+route,{method:'POST',headers:{'content-type':'application/json',...headers},body:JSON.stringify(body),redirect:'manual'});
  const reg=await post('/work/register',{redirect_uris:[redirect],token_endpoint_auth_method:'none'});
  const {client_id}=await reg.json();assert.equal(reg.status,201);
  async function grant(overrides={}, json=false) {
    const verifier=crypto.randomBytes(32).toString('base64url');
    const params={client_id,redirect_uri:redirect,response_type:'code',resource:base+'/mcp',scope:'phone:control',state:'state-check',code_challenge_method:'S256',code_challenge:crypto.createHash('sha256').update(verifier).digest('base64url'),...overrides};
    const page=await fetch(url+'/work/authorize?'+new URLSearchParams(params));
    if(page.status!==200)return {page};
    assert.ok(page.headers.get('content-security-policy').includes("form-action 'self' "+redirect+";"), 'the browser must allow the registered OAuth return after form submission');
    const html=await page.text(),ticket=/name="ticket" value="([^"]+)"/.exec(html)[1];
    const nonce=/<script nonce="([^"]+)"/.exec(html)[1];
    assert.ok(page.headers.get('content-security-policy').includes("script-src 'nonce-"+nonce+"'"));
    const owner='owner-'+crypto.randomUUID();writePrivateJson(path.join(dir,'approval.json'),{hash:digest(owner),expires:Date.now()+60000});
    const approve=await post('/work/authorize',{ticket,code:owner},{origin:'https://relay.android-backstage.com',...(json?{accept:'application/json'}:{})});
    assert.equal(approve.status,json?200:303);
    assert.ok(approve.headers.get('content-security-policy').includes("form-action 'self' "+redirect+";"));
    const location=new URL(json?(await approve.json()).redirect:approve.headers.get('location'));assert.equal(location.searchParams.get('iss'),base);assert.equal(location.searchParams.get('state'),'state-check');
    return {code:location.searchParams.get('code'),verifier,ticket,owner};
  }
  const exchange=(g,overrides={})=>post('/work/token',{grant_type:'authorization_code',client_id,resource:base+'/mcp',redirect_uri:redirect,code:g.code,code_verifier:g.verifier,...overrides});
  return {dir,calls,url,post,client_id,grant,exchange,advance:ms=>{clock+=ms;}};
}
test('auth discovery and unauthenticated calls fail without phone access',async t=>{
 const f=await fixture(t);const r=await f.post('/work/mcp',{jsonrpc:'2.0',id:1,method:'tools/list'});assert.equal(r.status,401);assert.match(r.headers.get('www-authenticate'),/oauth-protected-resource/);assert.equal(f.calls.length,0);
 const m=await (await fetch(f.url+'/work/.well-known/oauth-protected-resource')).json();assert.equal(m.resource,base+'/mcp');
 const a=await (await fetch(f.url+'/.well-known/oauth-authorization-server/work')).json();assert.deepEqual(a.code_challenge_methods_supported,['S256']);
});
test('reject foreign callback, missing PKCE, target, and owner approval',async t=>{
 const f=await fixture(t);assert.equal((await f.post('/work/register',{redirect_uris:['http://evil.example/callback']})).status,400);
 for(const overrides of [{code_challenge_method:'plain'},{resource:'https://evil.example'},{redirect_uri:'https://evil.example'},{scope:'admin'}]) assert.equal((await f.grant(overrides)).page.status,400);
 assert.equal((await f.post('/work/authorize',{ticket:'fake',code:'fake'},{origin:'https://relay.android-backstage.com'})).status,403);
 assert.equal((await f.post('/work/authorize',{},{origin:'https://evil.example'})).status,403);
});
test('PKCE, code single-use, resource binding and refresh rotation',async t=>{
 const f=await fixture(t),g=await f.grant();
 assert.equal((await f.exchange(g,{code_verifier:'wrong'})).status,400);
 assert.equal((await f.exchange(g,{resource:'https://evil.example'})).status,400);
 const r=await f.exchange(g);assert.equal(r.status,200);const token=await r.json();
 assert.equal((await f.exchange(g)).status,400);
 assert.equal((await f.post('/work/authorize',{ticket:g.ticket,code:g.owner},{origin:'https://relay.android-backstage.com'})).status,403);
 const req={grant_type:'refresh_token',client_id:f.client_id,resource:base+'/mcp',refresh_token:token.refresh_token};
 assert.equal((await f.post('/work/token',req)).status,200);assert.equal((await f.post('/work/token',req)).status,400);
 const raw=fs.readFileSync(path.join(f.dir,'oauth.json'),'utf8');assert.ok(!raw.includes(token.access_token));assert.ok(!raw.includes(token.refresh_token));
 assert.equal(fs.statSync(path.join(f.dir,'oauth.json')).mode & 0o777,0o600);
});
test('real HTTP MCP initialization, tools and validated phone calls',async t=>{
 const f=await fixture(t),g=await f.grant(),tokens=await (await f.exchange(g)).json();
 const headers={authorization:'Bearer '+tokens.access_token,accept:'application/json, text/event-stream'};
 let id=0; const rpc=async(method,params={})=>{const r=await f.post('/work/mcp',{jsonrpc:'2.0',id:++id,method,params},headers);assert.equal(r.status,200);return r.json();};
 const init=await rpc('initialize',{protocolVersion:'2025-11-25',capabilities:{},clientInfo:{name:'test',version:'1'}});assert.equal(init.result.serverInfo.name,'android-backstage-work');
 const list=await rpc('tools/list');assert.equal(list.result.tools.length,catalogue.length);
 const sequence=list.result.tools.find(t=>t.name==='android_sequence').inputSchema.properties.steps.items;
 assert.equal(sequence.type,'object');
 const waitVerify=list.result.tools.find(t=>t.name==='android_wait').inputSchema.properties.verify;
 assert.equal(waitVerify.type,'object');
 assert.equal(init.result.instructions,SERVER_INSTRUCTIONS);
 assert.ok(!list.result.tools.some(t=>t.name==='android_help'));
 const ok=await rpc('tools/call',{name:'android_packages',arguments:{package:'com.example'}});assert.equal(ok.result.structuredContent.value,'test-phone');assert.equal(f.calls.length,1);
 for(const args of [{},{display:0},{display:1,extra:'bad'}]) {const bad=await rpc('tools/call',{name:'android_observe',arguments:args});assert.equal(bad.result.isError,true);assert.equal(bad.result.structuredContent.inputDelivered,false);assert.ok(bad.result.structuredContent.example.sessionId);assert.ok(bad.result.structuredContent.issues.some(i=>i.field==='sessionId'));}
 const unknown=await rpc('tools/call',{name:'android_missing',arguments:{}});assert.equal(unknown.result.structuredContent.code,'unknown_tool');assert.equal(unknown.result.structuredContent.inputDelivered,false);
 const bad=await rpc('tools/call',{name:'android_push',arguments:{}});assert.equal(bad.result.isError,true);assert.equal(f.calls.length,1);
 const badHold=await rpc('tools/call',{name:'android_tap',arguments:{sessionId:'session_'+'a'.repeat(32),x:100,y:200,holdMs:5000}});
 assert.equal(badHold.result.isError,true);
 assert.ok(badHold.result.structuredContent.issues.some(i=>i.field==='holdMs'));
 assert.ok(!badHold.result.structuredContent.issues.some(i=>i.field==='requestId'),JSON.stringify(badHold.result.structuredContent));
 const seqNorm=await rpc('tools/call',{name:'android_sequence',arguments:{sessionId:'session_'+'a'.repeat(32),steps:[{tool:'android_tap',arguments:{x:100,y:200}}]}});
 assert.equal(seqNorm.result.isError,false,JSON.stringify(seqNorm.result));
 assert.equal(f.calls.at(-1).args.steps[0].tool,'android.tap');
 const cross=await f.post('/work/mcp',{jsonrpc:'2.0',id:6,method:'tools/list'},{...headers,origin:'https://evil.example'});assert.equal(cross.status,403);
});

test('expired access and refresh tokens are rejected',async t=>{
 const f=await fixture(t),g=await f.grant(),tokens=await (await f.exchange(g)).json();
 f.advance(3600001);
 const r=await f.post('/work/mcp',{jsonrpc:'2.0',id:1,method:'tools/list'},{authorization:'Bearer '+tokens.access_token});assert.equal(r.status,401);
 f.advance(31*86400000);
 const refresh=await f.post('/work/token',{grant_type:'refresh_token',client_id:f.client_id,resource:base+'/mcp',refresh_token:tokens.refresh_token});assert.equal(refresh.status,400);
 assert.equal(f.calls.length,0);
});

test('browser background approval keeps single-use owner approval and PKCE',async t=>{
 const f=await fixture(t),g=await f.grant({},true);
 assert.equal((await f.exchange(g,{code_verifier:'incorrect'})).status,400);
 assert.equal((await f.exchange(g)).status,200);
 assert.equal((await f.post('/work/authorize',{ticket:g.ticket,code:g.owner},{origin:'https://relay.android-backstage.com',accept:'application/json'})).status,403);
});
test('a slow tool call starts its JSON reply early and pads it with spaces until the result',async t=>{
 const f=await fixture(t,{delayMs:400,keepAlive:{firstMs:100,everyMs:100}}),g=await f.grant(),tokens=await (await f.exchange(g)).json();
 const headers={authorization:'Bearer '+tokens.access_token,accept:'application/json, text/event-stream'};
 const started=Date.now();
 const r=await f.post('/work/mcp',{jsonrpc:'2.0',id:9,method:'tools/call',params:{name:'android_packages',arguments:{package:'com.example'}}},headers);
 assert.ok(Date.now()-started<350,'headers arrive before the result');
 assert.equal(r.status,200);assert.match(r.headers.get('content-type'),/application\/json/);
 const text=await r.text();
 assert.match(text,/^ {2,}\{/);
 const reply=JSON.parse(text);assert.equal(reply.id,9);assert.equal(reply.result.structuredContent.value,'test-phone');
 const fast=await fixture(t,{keepAlive:{firstMs:100,everyMs:100}}),fg=await fast.grant(),ft=await (await fast.exchange(fg)).json();
 const quick=await fast.post('/work/mcp',{jsonrpc:'2.0',id:1,method:'tools/call',params:{name:'android_packages',arguments:{package:'com.example'}}},{...headers,authorization:'Bearer '+ft.access_token});
 assert.match(await quick.text(),/^\{/);
 const list=await fast.post('/work/mcp',{jsonrpc:'2.0',id:2,method:'tools/list'},{...headers,authorization:'Bearer '+ft.access_token});
 assert.ok(list.headers.get('content-length'),'other messages keep their normal reply');
});
