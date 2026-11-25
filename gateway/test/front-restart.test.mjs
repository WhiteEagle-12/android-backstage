import test from 'node:test';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';

const freePort=()=>new Promise(r=>{const s=net.createServer().listen(0,'127.0.0.1',()=>{const p=s.address().port;s.close(()=>r(p));});});
const post=(port,body)=>fetch(`http://127.0.0.1:${port}/work/mcp`,{method:'POST',headers:{'content-type':'application/json','x-forwarded-proto':'https'},body});

test('the front holds Work requests while Work restarts, and sends each one once',async t=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'bs-front-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));
  const [front,relay,work]=await Promise.all([freePort(),freePort(),freePort()]);
  const child=spawn(process.execPath,[new URL('../front.mjs',import.meta.url).pathname],{env:{...process.env,PORT:String(front),BACKSTAGE_INTERNAL_RELAY_PORT:String(relay),BACKSTAGE_WORK_UPSTREAM_PORT:String(work),RELAY_DATA_DIR:dir},stdio:['ignore','pipe','inherit']});
  t.after(()=>child.kill());
  await new Promise(r=>child.stdout.on('data',d=>{if(/ready/.test(d))r();}));
  const seen=[];
  const start=()=>new Promise(r=>{const s=http.createServer((req,res)=>{let b='';req.on('data',c=>b+=c);req.on('end',()=>{seen.push(b);res.end('ok:'+b);});}).listen(work,'127.0.0.1',()=>r(s));});
  // Work is down when the requests arrive and comes up 1.5 s later.
  const pending=[post(front,'{"a":1}'),post(front,'{"a":2}')];
  await new Promise(r=>setTimeout(r,1500));
  let server=await start();
  const replies=await Promise.all(pending);
  assert.deepEqual(await Promise.all(replies.map(r=>r.text())),['ok:{"a":1}','ok:{"a":2}']);
  // A restart while keep-alive sockets sit idle must not fail the next request.
  await new Promise(r=>{server.close(r);server.closeAllConnections();});
  server=await start();t.after(()=>server.close());
  assert.equal(await (await post(front,'{"a":3}')).text(),'ok:{"a":3}');
  assert.deepEqual(seen,['{"a":1}','{"a":2}','{"a":3}']);
});
