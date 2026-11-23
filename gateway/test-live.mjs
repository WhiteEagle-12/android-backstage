/** Owner-invoked public endpoint check. Does not save tokens or read personal phone content. */
import fs from 'node:fs';
import crypto from 'node:crypto';
import path from 'node:path';
import { writePrivateJson } from '../cli/state.mjs';
import { digest } from './auth.mjs';
const base=process.env.BACKSTAGE_WORK_BASE || 'https://relay.android-backstage.com/work';
const dir=process.env.BACKSTAGE_WORK_STATE;
if(!dir)throw new Error('BACKSTAGE_WORK_STATE required');
const post=async(route,body,headers={})=>fetch(base+route,{method:'POST',headers:{'content-type':'application/json',...headers},body:JSON.stringify(body),redirect:'manual',signal:AbortSignal.timeout(90000)});
const redirect='https://chatgpt.com/connector_platform_oauth_redirect';
const reg=await post('/register',{redirect_uris:[redirect],token_endpoint_auth_method:'none'});if(reg.status!==201)throw new Error('registration failed '+reg.status);
const {client_id}=await reg.json();
const verifier=crypto.randomBytes(32).toString('base64url');
const query=new URLSearchParams({client_id,redirect_uri:redirect,response_type:'code',resource:base+'/mcp',scope:'phone:control',state:'live-test',code_challenge_method:'S256',code_challenge:crypto.createHash('sha256').update(verifier).digest('base64url')});
const page=await fetch(base+'/authorize?'+query);const html=await page.text();const ticket=/name="ticket" value="([^"]+)"/.exec(html)?.[1];if(!ticket)throw new Error('No approval form');
const owner=crypto.randomBytes(32).toString('base64url');writePrivateJson(path.join(dir,'approval.json'),{hash:digest(owner),expires:Date.now()+60000});
const approve=await post('/authorize',{ticket,code:owner},{origin:new URL(base).origin});if(approve.status!==303)throw new Error('approval failed '+approve.status);
const code=new URL(approve.headers.get('location')).searchParams.get('code');
const exchange=await post('/token',{grant_type:'authorization_code',client_id,resource:base+'/mcp',redirect_uri:redirect,code,code_verifier:verifier});if(exchange.status!==200)throw new Error('token failed '+exchange.status);
const tokens=await exchange.json(),headers={authorization:'Bearer '+tokens.access_token,accept:'application/json, text/event-stream'};
const results=[];
try{
 let id=0;const rpc=async(method,params={})=>{const r=await post('/mcp',{jsonrpc:'2.0',id:++id,method,params},headers);if(r.status!==200)throw new Error('MCP HTTP '+r.status);const j=await r.json();if(j.error)throw new Error(JSON.stringify(j.error));return j.result;};
 const init=await rpc('initialize',{protocolVersion:'2025-11-25',capabilities:{},clientInfo:{name:'backstage-owner-live-test',version:'1'}});
 const list=await rpc('tools/list');
 const model=await rpc('tools/call',{name:'android_getprop',arguments:{key:'ro.product.model'}});
 if(model.isError)throw new Error('phone model failed');
 const nonce='backstage-work-'+crypto.randomBytes(8).toString('hex');
 const echo=await rpc('tools/call',{name:'android_shell',arguments:{command:'echo '+nonce}});
 if(echo.isError || !JSON.stringify(echo).includes(nonce))throw new Error('live nonce did not return from phone');
 const blocked=await rpc('tools/call',{name:'android_ui_tree',arguments:{display:0}});if(!blocked.isError)throw new Error('main display should be rejected');
 console.log(JSON.stringify({passed:true,at:new Date().toISOString(),endpoint:base+'/mcp',server:init.serverInfo,toolCount:list.tools.length,model:model.structuredContent,liveNonce:nonce,mainDisplayRejected:true,testSource:'public HTTPS MCP harness; not a ChatGPT Work conversation'},null,2));
}finally{
 await post('/revoke',{client_id,token:tokens.access_token});await post('/revoke',{client_id,token:tokens.refresh_token});
}
