import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import express from 'express';
import {install,verify} from '../agent-connector.mjs';
function fixture(t){const home=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-install-'));t.after(()=>fs.rmSync(home,{recursive:true,force:true}));const account=path.join(home,'private account','account.json');fs.mkdirSync(path.dirname(account));fs.writeFileSync(account,JSON.stringify({base:'https://example.com/work',access_token:'test-token'}));return {home,account};}
test('Cursor install preserves unrelated servers, backs up settings, and retries without duplicates',t=>{
 const f=fixture(t),file=path.join(f.home,'.cursor','mcp.json');fs.mkdirSync(path.dirname(file));
 const before={mcpServers:{existing:{url:'https://example.org'}},extra:true};fs.writeFileSync(file,JSON.stringify(before));
 const name=install('cursor',f.account,{home:f.home,node:'/path with spaces/node'});
 const after=JSON.parse(fs.readFileSync(file));assert.deepEqual(after.mcpServers.existing,before.mcpServers.existing);assert.equal(after.extra,true);
 assert.deepEqual(JSON.parse(fs.readFileSync(file+'.backstage-backup')),before);
 assert.equal(after.mcpServers[name].args.at(-1),f.account);
 assert.equal(install('cursor',f.account,{home:f.home}),name);assert.equal(Object.keys(JSON.parse(fs.readFileSync(file)).mcpServers).length,2);
});
test('Cursor refuses malformed settings without overwriting them',t=>{
 const f=fixture(t),file=path.join(f.home,'.cursor','mcp.json');fs.mkdirSync(path.dirname(file));
 for(const bad of ['broken','[]','null','{"mcpServers":[]}']){fs.writeFileSync(file,bad);assert.throws(()=>install('cursor',f.account,{home:f.home}));assert.equal(fs.readFileSync(file,'utf8'),bad);}
});
test('Codex and Claude Code use supported user settings commands with unexpanded arguments',t=>{
 const f=fixture(t),calls=[];const exec=(...args)=>calls.push(args);
 install('codex',f.account,{exec,node:'node with spaces'});install('claude-code',f.account,{exec});
 assert.equal(calls[0][0],'codex');assert.deepEqual(calls[0][1].slice(0,2),['mcp','add']);
 assert.deepEqual(calls[1][1].slice(0,6),['mcp','add','--scope','user','--transport','stdio']);
 for(const call of calls){assert.equal(call[1].at(-1),f.account);assert.equal(call[2].timeout,30000);assert.ok(!call[1].includes('test-token'));}
 assert.throws(()=>install('unknown',f.account,{exec}));assert.equal(calls.length,2);
});
test('Phone verification checks each online device through the current native tool',async t=>{
 const f=fixture(t),messages=[];
 const post=async(url,msg,token)=>{messages.push(msg);assert.equal(token,'test-token');return {result:{structuredContent:msg.params?.arguments?.detail==='debug'?{outcome:'succeeded',connected:true,model:'Test Phone'}:{outcome:'succeeded',devices:[{id:'phone-a',online:true},{id:'phone-b',online:false},{id:'phone-c',online:true,needsReconnect:true}]}}};};
 assert.equal(await verify(f.account,{post}),'Test Phone');assert.equal(messages[0].method,'initialize');assert.equal(messages[1].method,'notifications/initialized');
 assert.deepEqual(messages[2].params,{name:'android_devices',arguments:{}});
 assert.deepEqual(messages[3].params,{name:'android_devices',arguments:{device:'phone-a',detail:'debug'}});
 assert.equal(messages.length,4);
});
test('Phone verification rejects missing, offline, malformed, or failed device results',async t=>{
 const f=fixture(t);
 for(const result of [{isError:true},{structuredContent:{outcome:'failed'}},{structuredContent:{outcome:'succeeded',devices:[]}},{structuredContent:{outcome:'succeeded',devices:[{id:'a',online:false}]}},{structuredContent:{outcome:'succeeded',devices:{}}}])await assert.rejects(verify(f.account,{post:async()=>({result})}),/phone did not answer|no phone is online/);
 await assert.rejects(verify(f.account,{post:async()=>({error:{code:-1}})}),/Phone check failed/);
 for(const identity of [{outcome:'succeeded',connected:false,model:'Phone'},{outcome:'succeeded',connected:true,model:''},{outcome:'failed'}])await assert.rejects(verify(f.account,{post:async(url,msg)=>({result:{structuredContent:msg.params?.arguments?.detail?identity:{outcome:'succeeded',devices:[{id:'a',online:true}]}}})}),/phone did not answer/);
});
test('Phone verification accepts text content and reads all online models without choosing a task device',async t=>{
 const f=fixture(t);
 const post=async(url,msg)=>({result:{content:[{type:'text',text:JSON.stringify(msg.params?.arguments?.detail?{outcome:'succeeded',connected:true,model:msg.params.arguments.device}:{outcome:'succeeded',devices:[{id:'A',online:true},{id:'B',online:true}]})}]}});
 assert.equal(await verify(f.account,{post}),'A, B');
});

test('setup script downloads from a hidden release directory and instructions use current tools',async t=>{
 const f=fixture(t),release=path.join(f.home,'.local','release');fs.mkdirSync(release,{recursive:true});
 for(const name of ['phone-api.mjs','agent-connector.mjs'])fs.copyFileSync(new URL('../'+name,import.meta.url),path.join(release,name));
 for(const name of ['connections.mjs','assistants.mjs'])fs.symlinkSync(fileURLToPath(new URL('../'+name,import.meta.url)),path.join(release,name));
 const {phoneRoutes}=await import(pathToFileURL(path.join(release,'phone-api.mjs')).href);
 const app=express();phoneRoutes(app,{connections:{},auth:{},base:'https://example.com/work'});
 const server=await new Promise(r=>{const s=app.listen(0,'127.0.0.1',()=>r(s));});t.after(()=>server.close());
 const base='http://127.0.0.1:'+server.address().port;
 const response=await fetch(base+'/work/connect/agent.mjs');assert.equal(response.status,200);
 assert.match(response.headers.get('content-type'),/javascript/);assert.equal(await response.text(),fs.readFileSync(new URL('../agent-connector.mjs',import.meta.url),'utf8'));
 const guide=await (await fetch(base+'/work/connect/instructions')).json();assert.match(guide.local.next,/android_devices/);assert.doesNotMatch(guide.local.next,/android_getprop/);assert.doesNotMatch(guide.instructions,/T3 Code/);assert.match(guide.verify['claude-code'],/claude -p/);assert.match(guide.verify.codex,/codex exec/);
 assert.equal((await fetch(base+'/work/connect/account.json')).status,404);
});

test('OpenCode settings keep comments, gain one entry, and replace it on reconnect',t=>{
 const f=fixture(t),file=path.join(f.home,'.config','opencode','opencode.jsonc');fs.mkdirSync(path.dirname(file),{recursive:true});
 fs.writeFileSync(file,'{\n  // my settings\n  "$schema": "https://opencode.ai/config.json",\n  "mcp": {\n    "other": {"type":"local","command":["x"]}\n  }\n}\n');
 install('opencode',f.account,{home:f.home,name:'android-backstage',node:'/bin/node'});
 let text=fs.readFileSync(file,'utf8');assert.match(text,/\/\/ my settings/);assert.match(text,/"other"/);
 const parse=t=>JSON.parse(t.replace(/^\s*\/\/.*$/mg,''));
 assert.deepEqual(parse(text).mcp['android-backstage'].command.slice(0,1),['/bin/node']);
 install('opencode',f.account,{home:f.home,name:'android-backstage',node:'/usr/bin/node'});
 text=fs.readFileSync(file,'utf8');assert.equal(text.split('"android-backstage"').length-1,1,'reconnecting replaces the entry');
 assert.deepEqual(parse(text).mcp['android-backstage'].command.slice(0,1),['/usr/bin/node']);assert.ok(parse(text).mcp.other);
});
test('Gemini and Antigravity entries go to their settings files',t=>{
 const f=fixture(t);
 install('gemini',f.account,{home:f.home,name:'android-backstage',node:'node'});install('antigravity',f.account,{home:f.home,name:'android-backstage',node:'node'});
 assert.equal(JSON.parse(fs.readFileSync(path.join(f.home,'.gemini','settings.json'))).mcpServers['android-backstage'].args.at(-1),f.account);
 assert.ok(JSON.parse(fs.readFileSync(path.join(f.home,'.gemini','antigravity','mcp_config.json'))).mcpServers['android-backstage']);
});
test('Grok uses its own settings command and replacing removes the old entry first',t=>{
 const f=fixture(t),calls=[];const exec=(...args)=>calls.push(args);
 install('grok',f.account,{home:f.home,exec,name:'android-backstage',replace:true});
 assert.deepEqual(calls[0][1],['mcp','remove','android-backstage']);assert.deepEqual(calls[1][1].slice(0,5),['mcp','add','-s','user','android-backstage']);assert.equal(calls[1][1].at(-1),f.account);
});
