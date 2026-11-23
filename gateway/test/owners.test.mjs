import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createOwners,sessionCookie,challengeCookie} from '../owners.mjs';
import {passkeyFixture} from './passkey-fixture.mjs';
const origin='https://relay.example';
function browser(){const jar={};return {req:{headers:{}},jar,res:{append(_name,line){const first=line.split(';')[0],i=first.indexOf('=');jar[first.slice(0,i)]=first.slice(i+1);}},sync(){this.req.headers.cookie=Object.entries(jar).map(([k,v])=>k+'='+v).join('; ');}};}
test('real passkeys recover the same owner after restart and reject replay, wrong origin and forged signatures',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-owners-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));const stateFile=path.join(dir,'owners.json');let clock=Date.now();
 let owners=createOwners({stateFile,origin,now:()=>clock}),b=browser();const key=passkeyFixture(origin);
 const registration=await owners.options(b.req,b.res,'register');b.sync();const response=key.register(registration);
 const owner=await owners.verify(b.req,b.res,response);b.sync();assert.equal(owners.owner(b.req),owner);
 assert.equal(fs.statSync(stateFile).mode&0o777,0o600);assert.ok(!fs.readFileSync(stateFile,'utf8').includes(b.jar[sessionCookie]));
 await assert.rejects(owners.verify(b.req,b.res,response),/expired/);
 owners=createOwners({stateFile,origin,now:()=>clock});b=browser();
 const options=await owners.options(b.req,b.res,'authenticate');b.sync();assert.equal(await owners.verify(b.req,b.res,key.authenticate(options)),owner);b.sync();assert.equal(owners.owner(b.req),owner);
 for(const mutate of [r=>({...r,response:{...r.response,signature:'AAAA'}}),r=>key.authenticate(options,{origin:'https://evil.example'})]) {
  const next=await owners.options(b.req,b.res,'authenticate');b.sync();await assert.rejects(owners.verify(b.req,b.res,mutate(key.authenticate(next))));
 }
 const stolen=browser();stolen.jar[challengeCookie]=b.jar[challengeCookie];stolen.sync();assert.equal(owners.owner(stolen.req),null);
 clock+=31*86400000;assert.equal(owners.owner(b.req),null);
});
test('adding a passkey is bound to the signed-in owner and challenges expire',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-owner-isolation-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));let clock=Date.now();
 const owners=createOwners({stateFile:path.join(dir,'owners.json'),origin,now:()=>clock});
 const first=browser(),key=passkeyFixture(origin);let options=await owners.options(first.req,first.res,'register');first.sync();const id=await owners.verify(first.req,first.res,key.register(options));first.sync();
 const secondKey=passkeyFixture(origin);options=await owners.options(first.req,first.res,'register');first.sync();
 const stolen=browser();stolen.jar[challengeCookie]=first.jar[challengeCookie];stolen.sync();await assert.rejects(owners.verify(stolen.req,stolen.res,secondKey.register(options)),/Sign in again/);
 options=await owners.options(first.req,first.res,'register');first.sync();assert.equal(await owners.verify(first.req,first.res,secondKey.register(options)),id);
 const fresh=browser();options=await owners.options(fresh.req,fresh.res,'authenticate');fresh.sync();assert.equal(await owners.verify(fresh.req,fresh.res,secondKey.authenticate(options)),id);
 options=await owners.options(fresh.req,fresh.res,'authenticate');fresh.sync();clock+=300001;await assert.rejects(owners.verify(fresh.req,fresh.res,key.authenticate(options)),/expired/);
});
