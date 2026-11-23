import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawn,spawnSync} from 'node:child_process';
import {withRelay} from './helpers.mjs';
import {beginConnection} from '../../cli/connect.mjs';

test('Android RelayLinker completes keyless setup and adds a second assistant over real HTTP',()=>withRelay(async({url})=>{
 const root=fileURLToPath(new URL('../../',import.meta.url)),out=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-java-'));
 try {
   const compile=spawnSync('javac',['--release','17','-d',out,'-sourcepath',path.join(root,'app/app/src/main/java'),path.join(root,'app/hosttest/RelayConnectionHarness.java')],{encoding:'utf8'});
   assert.equal(compile.status,0,compile.stderr);
   let device;
   for(let i=0;i<2;i++){
     const c=await beginConnection({relayUrl:url});
     const java=spawn('java',['-cp',out,'com.deviceagent.app.RelayConnectionHarness'],{stdio:['pipe','pipe','pipe']});
     let stdout='',stderr='';java.stdout.on('data',d=>stdout+=d);java.stderr.on('data',d=>stderr+=d);
     const done=new Promise((resolve,reject)=>{java.on('error',reject);java.on('close',status=>status===0?resolve(JSON.parse(stdout)):reject(new Error(stderr)));});
     // Attach immediately so a harness failure cannot become an unhandled rejection.
     done.catch(()=>{});
     java.stdin.end(JSON.stringify({url,code:c.code,token:device?.deviceToken || ''}));
     let cfg;for(let n=0;n<20&&!cfg;n++)cfg=await c.poll();
     assert.ok(cfg);const phone=await done;
     assert.equal(phone.linkKey,cfg.links[cfg.device].linkKey);
     assert.equal(phone.agentId,cfg.agentId);
     if(device)assert.equal(phone.deviceId,device.deviceId);
     device=phone;
   }
 }finally{fs.rmSync(out,{recursive:true,force:true});}
}));
