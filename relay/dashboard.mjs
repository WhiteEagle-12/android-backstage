/** Private operational view. Never record request bodies, headers, URLs or encrypted envelopes. */
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {writePrivateJson} from './persist.mjs';
import {requireAccount} from './auth.mjs';
const RETENTION=86400000, LIMIT=2000;
const assets=new Map([['/dashboard/',['index.html','text/html; charset=utf-8']],['/dashboard/dashboard.js',['dashboard.js','text/javascript; charset=utf-8']],['/dashboard/site.css',['site.css','text/css; charset=utf-8']],['/dashboard/backstage.png',['backstage.png','image/png']]]);
const fixed=new Set(['/v1/call','/v1/jobs','/v1/device/hello','/v1/device/next','/v1/device/result','/v1/devices','/v1/me','/v1/uploads','/v1/files','/v1/pair/session']);
export function routeLabel(pathname){
  if(fixed.has(pathname))return pathname;
  if(/^\/v1\/jobs\/[^/]+$/.test(pathname))return '/v1/jobs/:id';
  if(/^\/v1\/devices\/[^/]+$/.test(pathname))return '/v1/devices/:name';
  if(pathname.startsWith('/v1/pair/session/'))return '/v1/pair/session/:id';
  if(pathname.startsWith('/v1/uploads/'))return '/v1/uploads/:id';
  if(pathname.startsWith('/v1/files/'))return '/v1/files/:id';
  return 'other';
}
export function createDashboard({dataDir,store,queue,listedDevice,version,memoryOnly=false,log=console}){
  const file=path.join(dataDir,'dashboard-activity.json'), start=Date.now();
  let events=[],dirty=false,storageError=false;const active=new Map();let nextId=0;
  if(memoryOnly){try{fs.rmSync(file,{force:true});}catch{}}
  else{try{const saved=JSON.parse(fs.readFileSync(file,'utf8'));if(Array.isArray(saved))events=saved;}catch(e){if(e.code!=='ENOENT'){storageError=true;log.error?.('Dashboard history unavailable; starting a new bounded history.');}}}
  const retentionMs = memoryOnly ? 5 * 60_000 : RETENTION;
  function prune(){events=events.filter(e=>Number.isFinite(e.at)&&e.at>Date.now()-retentionMs).slice(-LIMIT);}
  prune();
  function flush(){if(memoryOnly||!dirty)return;try{prune();writePrivateJson(file,events);dirty=false;storageError=false;}catch{storageError=true;log.error?.('Dashboard could not save activity history.');}}
  const timer=setInterval(flush,2000);timer.unref();
  function observe(req,res,principal,pathname){
    if(!principal || !pathname.startsWith('/v1/') || pathname==='/v1/dashboard')return;
    const isDevice=!!principal.accountId,accountId=isDevice?principal.accountId:principal.id;
    const id=++nextId, at=Date.now();
    const event={at,accountId,source:isDevice?'phone':'client',deviceId:isDevice?principal.id:null,method:['GET','POST','PUT','PATCH','DELETE','OPTIONS'].includes(req.method)?req.method:'OTHER',route:routeLabel(pathname),incomingBytes:0,outgoingBytes:0};
    active.set(id,event);
    // Counting data listeners do not parse, retain or modify the stream.
    req.on('data',chunk=>{event.incomingBytes+=chunk.length;});
    const write=res.write,end=res.end;
    const size=(chunk,encoding)=>chunk==null?0:Buffer.isBuffer(chunk)||ArrayBuffer.isView(chunk)?chunk.byteLength:typeof chunk==='string'?Buffer.byteLength(chunk,typeof encoding==='string'?encoding:undefined):0;
    res.write=function(chunk,encoding,...args){event.outgoingBytes+=size(chunk,encoding);return write.call(this,chunk,encoding,...args);};
    res.end=function(chunk,encoding,...args){event.outgoingBytes+=size(chunk,encoding);return end.call(this,chunk,encoding,...args);};
    let recorded=false;
    const finish=()=>{if(recorded)return;recorded=true;active.delete(id);events.push({...event,status:res.writableFinished?res.statusCode:499,durationMs:Math.max(0,Date.now()-at)});prune();dirty=true;};
    res.once('finish',finish);res.once('close',finish);
  }
  function snapshot(account){
    prune();
    const own=events.filter(e=>e.accountId===account.id).map(({accountId,...e})=>e);
    const devices=store.devicesForAccount(account.id).map(listedDevice).map(({inboundUrl,bootId,...d})=>d);
    const jobs=queue.list(account.id).map(j=>({id:j.id,deviceId:j.deviceId,clientId:typeof j.envelope?.from==='string'?j.envelope.from.slice(0,100):null,state:j.state,cancelRequested:!!j.cancelRequested,createdAt:j.createdAt,updatedAt:j.updatedAt,expiresAt:j.expiresAt,requestBytes:Buffer.byteLength(JSON.stringify(j.envelope||{})),responseBytes:j.outcome?.envelope?Buffer.byteLength(JSON.stringify(j.outcome.envelope)):0,transportError:!!j.outcome?.error})).sort((a,b)=>b.createdAt-a.createdAt);
    const summary=own.reduce((a,e)=>{a.requests++;a.incomingBytes+=e.incomingBytes;a.outgoingBytes+=e.outgoingBytes;if(e.status>=400)a.errors++;return a;},{requests:0,incomingBytes:0,outgoingBytes:0,errors:0});
    return {now:Date.now(),account:{name:account.name},server:{version,uptimeSeconds:Math.floor((Date.now()-start)/1000),memoryBytes:process.memoryUsage().rss,historyLimit:LIMIT,historyHours:24,historySaveError:storageError},devices,jobs:jobs.slice(0,300),jobTotal:jobs.length,summary,events:own.slice(-500).reverse(),active:[...active.values()].filter(e=>e.accountId===account.id).map(({accountId,...e})=>({...e,durationMs:Date.now()-e.at}))};
  }
  function handle(req,res,url){
    if(url.pathname==='/'||url.pathname==='/dashboard'){res.writeHead(302,{location:'/dashboard/','cache-control':'no-store'}).end();return true;}
    if(url.pathname==='/v1/dashboard'){
      const account=requireAccount(req,store);
      if(req.method!=='GET'){res.writeHead(405,{allow:'GET'}).end();return true;}
      const data=JSON.stringify(snapshot(account));
      res.writeHead(200,{'content-type':'application/json','cache-control':'no-store','x-content-type-options':'nosniff','content-length':Buffer.byteLength(data)}).end(data);return true;
    }
    if(!url.pathname.startsWith('/dashboard/'))return false;
    const asset=assets.get(url.pathname);
    if(!asset||!['GET','HEAD'].includes(req.method)){res.writeHead(404).end();return true;}
    const data=fs.readFileSync(new URL('./dashboard/'+asset[0],import.meta.url));
    res.writeHead(200,{'content-type':asset[1],'content-length':data.length,'cache-control':'no-store','x-content-type-options':'nosniff','referrer-policy':'no-referrer','content-security-policy':"default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"});res.end(req.method==='HEAD'?undefined:data);return true;
  }
  return {observe,handle,snapshot,close(){clearInterval(timer);flush();}};
}
