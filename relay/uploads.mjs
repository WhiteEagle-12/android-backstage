import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {writePrivateJson} from './persist.mjs';

const ZERO_CHUNK = Buffer.alloc(65536);
function shredSync(filePath, shred) {
  if (shred) {
    try {
      const st = fs.statSync(filePath);
      if (st.isFile() && st.size > 0) {
        const fd = fs.openSync(filePath, 'r+');
        try {
          let rem = st.size, pos = 0;
          while (rem > 0) {
            const n = Math.min(rem, ZERO_CHUNK.length);
            fs.writeSync(fd, ZERO_CHUNK, 0, n, pos);
            pos += n; rem -= n;
          }
        } finally { fs.closeSync(fd); }
      }
    } catch {}
  }
  fs.rmSync(filePath, { force: true });
}

export function createUploads(dataDir, {uploadsDir=null, maxBytes=512*1024*1024, quotaBytes=2*1024*1024*1024, maxAccountBytes=512*1024*1024, ttlMs=900000, shred=false}={}) {
  const dir = uploadsDir || path.join(dataDir, 'uploads');
  fs.mkdirSync(dir,{recursive:true,mode:0o700});
  try { fs.chmodSync(dir, 0o700); } catch {}
  const active=new Set();
  const fail=(msg,status=400)=>Object.assign(new Error(msg),{status});
  const meta=id=>path.join(dir,id+'.json'), blob=id=>path.join(dir,id+'.part');
  function list(){if(!fs.existsSync(dir))return [];return fs.readdirSync(dir).filter(x=>x.endsWith('.json')).map(x=>JSON.parse(fs.readFileSync(path.join(dir,x),'utf8')));}
  function sweep(){for(const u of list())if(u.expiresAt<Date.now()&&!active.has(u.id)){shredSync(blob(u.id),shred);shredSync(meta(u.id),shred);}}
  function get(id,owner){if(!/^[a-f0-9]{32}$/.test(id))throw fail('unknown upload',404); let u;try{u=JSON.parse(fs.readFileSync(meta(id),'utf8'));}catch(e){if(e.code==='ENOENT')throw fail('unknown upload',404);throw e;}if(u.owner!==owner||u.expiresAt<Date.now())throw fail('unknown upload',404);return u;}
  function begin(owner,name,size){
    sweep();if(!Number.isSafeInteger(size)||size<=0||size>maxBytes)throw fail('invalid upload size',413);
    const current=list().filter(u=>!u.file);
    const own=current.filter(u=>u.owner===owner);
    const ownBytes=own.reduce((n,u)=>n+u.size,0);
    if(current.length>=128||current.reduce((n,u)=>n+u.size,0)+size>quotaBytes||own.length>=8||ownBytes+size>maxAccountBytes)throw fail('upload quota exceeded',429);
    const u={id:crypto.randomBytes(16).toString('hex'),owner,name:path.basename(String(name||'encrypted.bin')),size,offset:0,expiresAt:Date.now()+ttlMs};
    fs.writeFileSync(blob(u.id),'',{mode:0o600});writePrivateJson(meta(u.id),u);return u;
  }
  async function append(id,owner,offset,req){
    if(active.has(id))throw fail('upload busy',409); active.add(id);
    try{
      const u=get(id,owner);if(offset!==u.offset)throw fail('offset mismatch; query upload status',409);
      const parts=[];let size=0;
      for await(const part of req){size+=part.length;if(size>8*1024*1024||u.offset+size>u.size)throw fail('chunk too large',413);parts.push(part);}
      if(!size)throw fail('empty chunk');
      const fd=fs.openSync(blob(id),'r+');try{fs.ftruncateSync(fd,u.offset);const bytes=Buffer.concat(parts);let done=0;while(done<bytes.length)done+=fs.writeSync(fd,bytes,done,bytes.length-done,u.offset+done);fs.fsyncSync(fd);}finally{fs.closeSync(fd);}
      u.offset+=size;writePrivateJson(meta(id),u);return u;
    }finally{active.delete(id);}
  }
  async function complete(id,owner,files,{r2=null}={}) {
    if(active.has(id))throw fail('upload busy',409);
    const u=get(id,owner);if(u.offset!==u.size)throw fail('upload incomplete',409);
    if(u.file)return u.file;
    if(r2){
      const uploaded=await r2.uploadFile(blob(id),{ttlSec:Math.round(ttlMs/1000),contentType:'application/octet-stream'});
      u.file={id:uploaded.id,expiresAt:uploaded.expiresAt,url:uploaded.url};
      writePrivateJson(meta(id),u);
      shredSync(blob(id),shred);
      return u.file;
    }
    const file=files.publishLocal(blob(id),{owner,name:u.name,ttl:ttlMs,singleUse:false});
    shredSync(blob(id),shred);
    u.file={id:file.id,expiresAt:file.expiresAt};writePrivateJson(meta(id),u);return u.file;
  }
  const timer=setInterval(sweep,60000);timer.unref();
  return {begin,get,append,complete,close:()=>clearInterval(timer)};
}
