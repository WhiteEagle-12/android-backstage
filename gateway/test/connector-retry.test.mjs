import test from 'node:test';
import assert from 'node:assert/strict';
import {request} from '../agent-connector.mjs';

const netError=code=>Object.assign(new TypeError('fetch failed'),{cause:Object.assign(Error(code),{code})});
const ok=body=>({ok:true,status:200,json:async()=>body});
const status=n=>({ok:false,status:n,json:async()=>({})});
const scripted=(...steps)=>{const calls=[];return {calls,fetchImpl:async(url,init)=>{calls.push(JSON.parse(init.body));const step=steps[calls.length-1];if(step instanceof Error)throw step;return step;}};};
const wait=async()=>{};
const write={jsonrpc:'2.0',id:7,method:'tools/call',params:{name:'android_tap',arguments:{requestId:'tap-1'}}};
const unnamedWrite={jsonrpc:'2.0',id:8,method:'tools/call',params:{name:'android_task_begin',arguments:{}}};
const refresh={grant_type:'refresh_token',refresh_token:'r1'};

test('a request whose connection never opened is resent, whatever it is',async()=>{
 for(const body of [write,unnamedWrite,refresh]){
  const s=scripted(netError('UND_ERR_CONNECT_TIMEOUT'),netError('EAI_AGAIN'),ok({done:true}));
  assert.deepEqual(await request('https://x/mcp',body,'t',{fetchImpl:s.fetchImpl,wait}),{done:true});
  assert.equal(s.calls.length,3);
 }
});

test('a lost reply is resent only for MCP messages the server deduplicates',async()=>{
 const s=scripted(netError('ECONNRESET'),ok({result:1}));
 assert.deepEqual(await request('https://x/mcp',write,'t',{fetchImpl:s.fetchImpl,wait}),{result:1});
 assert.equal(s.calls.length,2);
 assert.deepEqual(s.calls[0],s.calls[1]);
 for(const body of [unnamedWrite,refresh]){
  const once=scripted(netError('ECONNRESET'),ok({}));
  await assert.rejects(request('https://x/mcp',body,'t',{fetchImpl:once.fetchImpl,wait}),e=>e.code==='ECONNRESET'&&/ECONNRESET/.test(e.message));
  assert.equal(once.calls.length,1);
 }
});

test('gateway errors retry deduplicated messages; other HTTP errors and the third failure are final',async()=>{
 const s=scripted(status(502),ok({result:2}));
 assert.deepEqual(await request('https://x/mcp',write,'t',{fetchImpl:s.fetchImpl,wait}),{result:2});
 const u=scripted(status(502));
 await assert.rejects(request('https://x/mcp',unnamedWrite,'t',{fetchImpl:u.fetchImpl,wait}),e=>e.status===502);
 const a=scripted(status(401));
 await assert.rejects(request('https://x/mcp',write,'t',{fetchImpl:a.fetchImpl,wait}),/Connection expired/);
 const f=scripted(netError('ECONNREFUSED'),netError('ECONNREFUSED'),netError('ECONNREFUSED'),ok({}));
 await assert.rejects(request('https://x/mcp',write,'t',{fetchImpl:f.fetchImpl,wait}),e=>e.code==='ECONNREFUSED');
 assert.equal(f.calls.length,3);
});

test('notifications and empty replies still return null',async()=>{
 const s=scripted({ok:true,status:202,json:async()=>{throw Error('no body');}});
 assert.equal(await request('https://x/mcp',{jsonrpc:'2.0',method:'notifications/initialized'},'t',{fetchImpl:s.fetchImpl,wait}),null);
});
