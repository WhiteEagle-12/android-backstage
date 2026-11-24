import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {writePrivateJson} from '../cli/state.mjs';
import {ConnectionError} from './connections.mjs';

/**
 * Private, short-lived files an assistant uploads before asking a phone to install
 * or fetch them. Each artifact belongs to one assistant grant. The declared SHA-256
 * must match the received bytes before any phone can be pointed at it. Uploads are
 * resumable by offset so large builds can cross proxies with request-size limits.
 */
const ZERO_CHUNK = Buffer.alloc(65536);

/** Overwrite file bytes with zeros before unlinking (used in zero-retention mode). */
export function shredFile(filePath, shred = true) {
  if (shred) {
    try {
      const st = fs.statSync(filePath);
      if (st.isFile() && st.size > 0) {
        const fd = fs.openSync(filePath, 'r+');
        try {
          let remaining = st.size, pos = 0;
          while (remaining > 0) {
            const n = Math.min(remaining, ZERO_CHUNK.length);
            fs.writeSync(fd, ZERO_CHUNK, 0, n, pos);
            pos += n; remaining -= n;
          }
        } finally { fs.closeSync(fd); }
      }
    } catch { /* best-effort */ }
  }
  fs.rmSync(filePath, { force: true });
}

export function createArtifacts({directory,zeroRetention=false,r2=null,maxBytes=zeroRetention?128*1024*1024:256*1024*1024,ttlMs=zeroRetention?5*60000:45*60000,quotaBytes=zeroRetention?160*1024*1024:1024*1024*1024,maxGrantBytes=zeroRetention?128*1024*1024:256*1024*1024,now=Date.now}) {
  fs.mkdirSync(directory,{recursive:true,mode:0o700});
  const meta=id=>path.join(directory,id+'.json'),blob=id=>path.join(directory,id+'.bin');
  const busy=new Set();
  const purge=id=>{
    const row=read(id);
    if(row?.r2Key&&r2?.deleteObject)void r2.deleteObject(row.r2Key).catch(()=>{});
    shredFile(blob(id),zeroRetention);shredFile(meta(id),zeroRetention);
  };
  const valid=id=>/^art_[a-f0-9]{24}$/.test(id||'');
  const read=id=>{if(!valid(id))return null;try {return JSON.parse(fs.readFileSync(meta(id),'utf8'));} catch {return null;}};
  const save=row=>writePrivateJson(meta(row.id),row);
  const all=()=>fs.readdirSync(directory).filter(n=>n.endsWith('.json')).map(n=>read(n.slice(0,-5))).filter(Boolean);
  function sweep() {
    const t=now();
    const liveIds=new Set();
    for(const row of all()){
      if(row.expiresAt<=t&&!busy.has(row.id))purge(row.id);
      else liveIds.add(row.id);
    }
    // Also shred orphaned .bin files (e.g. aborted single-use downloads or interrupted pulls).
    try {
      for(const name of fs.readdirSync(directory)){
        if(!name.endsWith('.bin'))continue;
        const full=path.join(directory,name);
        if(name.startsWith('art_')){
          const id=name.slice(0,-4);
          if(!liveIds.has(id)&&!busy.has(id)){
            try{if(t-fs.statSync(full).mtimeMs>30000)shredFile(full,zeroRetention);}catch{}
          }
        } else if(name.startsWith('backstage-pull-')){
          try{if(t-fs.statSync(full).mtimeMs>ttlMs)shredFile(full,zeroRetention);}catch{}
        }
      }
    } catch {}
  }
  function checkGrantQuota(rows,grantId,size) {
    const mine=rows.filter(r=>r.grantId===grantId);
    if(mine.length>=6)throw new ConnectionError('busy','Six uploads are still stored. Delete one you no longer need with android_artifact_delete, or wait for it to expire.');
    if(mine.reduce((n,r)=>n+r.size,0)+size>maxGrantBytes)throw new ConnectionError('busy','Upload quota for this connection is full. Delete an earlier upload or wait for it to expire.');
    if(rows.reduce((n,r)=>n+r.size,0)+size>quotaBytes)throw new ConnectionError('busy','Artifact storage is full. Try again later.');
  }
  function begin({grantId,name,size,sha256}) {
    sweep();
    if(!Number.isSafeInteger(size)||size<=0||size>maxBytes)throw new ConnectionError('invalid_request',`size must be 1–${maxBytes} bytes.`);
    if(!/^[a-f0-9]{64}$/i.test(sha256||''))throw new ConnectionError('invalid_request','sha256 must be the lowercase hex SHA-256 of the file.');
    checkGrantQuota(all(),grantId,size);
    const clean=path.basename(String(name||'artifact.bin')).replace(/[^A-Za-z0-9._-]/g,'_').slice(0,80)||'artifact.bin';
    const row={id:'art_'+crypto.randomBytes(12).toString('hex'),grantId,name:clean,size,sha256:sha256.toLowerCase(),offset:0,complete:false,tokenHash:null,createdAt:now(),expiresAt:now()+ttlMs};
    const token='artup_'+crypto.randomBytes(24).toString('base64url');
    row.tokenHash=crypto.createHash('sha256').update(token).digest('hex');
    fs.writeFileSync(blob(row.id),'',{mode:0o600});save(row);
    return {...publicRow(row),uploadToken:token};
  }
  const publicRow=row=>({artifactId:row.id,name:row.name,size:row.size,sha256:row.sha256,offset:row.offset,complete:row.complete,expiresAt:new Date(row.expiresAt).toISOString()});
  function authorizedRow(id,{uploadToken,grantId}) {
    const row=read(id);
    if(!row||row.expiresAt<=now())throw new ConnectionError('unknown_artifact','Unknown or expired artifact. Begin a new upload.');
    let tokenOk=false;
    if(typeof uploadToken==='string'&&typeof row.tokenHash==='string'&&row.tokenHash.length===64){
      const given=crypto.createHash('sha256').update(uploadToken).digest('hex');
      tokenOk=crypto.timingSafeEqual(Buffer.from(given,'utf8'),Buffer.from(row.tokenHash,'utf8'));
    }
    if(!tokenOk&&row.grantId!==grantId)throw new ConnectionError('unknown_artifact','Unknown or expired artifact. Begin a new upload.');
    return row;
  }
  /** Append `stream` at `offset`. Finishing verifies the digest; a mismatch discards the bytes. */
  async function append(id,auth,offset,stream) {
    const row=authorizedRow(id,auth);
    if(row.complete)return publicRow(row);
    if(busy.has(id))throw new ConnectionError('busy','Another upload request for this artifact is in progress.');
    busy.add(id);
    try {
      if(offset!==row.offset)throw new ConnectionError('offset_mismatch',`Resume at offset ${row.offset}.`,{offset:row.offset});
      const fd=fs.openSync(blob(id),'r+');let written=0;
      try {
        fs.ftruncateSync(fd,row.offset);
        for await(const chunk of stream){
          if(row.offset+written+chunk.length>row.size)throw new ConnectionError('invalid_request',`More bytes than the declared size ${row.size}.`);
          let done=0;while(done<chunk.length)done+=fs.writeSync(fd,chunk,done,chunk.length-done,row.offset+written+done);written+=chunk.length;
        }
        fs.fsyncSync(fd);
      } finally {fs.closeSync(fd);}
      row.offset+=written;
      if(row.offset===row.size){
        const digest=await new Promise((resolve,reject)=>{const h=crypto.createHash('sha256');fs.createReadStream(blob(id)).on('data',d=>h.update(d)).on('end',()=>resolve(h.digest('hex'))).on('error',reject);});
        if(digest!==row.sha256){purge(id);throw new ConnectionError('integrity_mismatch',`Received SHA-256 ${digest} does not match the declared ${row.sha256}. The upload was discarded; begin again.`);}
        row.complete=true;
      }
      save(row);return publicRow(row);
    } catch(e) {
      if(!(e instanceof ConnectionError)){purge(id);throw new ConnectionError('upload_failed','The upload stream failed. Begin a new upload.');}
      throw e;
    } finally {busy.delete(id);}
  }
  function status(id,auth) {return publicRow(authorizedRow(id,typeof auth==='string'?{grantId:auth}:auth||{}));}
  /** A complete, verified artifact for this grant, with its private path. */
  function ready(id,grantId) {
    const row=authorizedRow(id,{grantId});
    if(!row.complete)throw new ConnectionError('artifact_incomplete',`Artifact ${id} has ${row.offset} of ${row.size} bytes. Finish the upload first.`,{offset:row.offset,size:row.size});
    return {...publicRow(row),path:blob(id)};
  }
  async function use(id,grantId,action) {
    const row=ready(id,grantId);
    if(busy.has(id))throw new ConnectionError('busy','This upload is in use. Try again when its current operation finishes.');
    busy.add(id);
    try{return await action(row);}finally{busy.delete(id);}
  }
  function remove(id,grantId) {
    if(!valid(id))throw new ConnectionError('unknown_artifact','Unknown artifact.');
    const row=read(id);
    if(!row)return {artifactId:id,deleted:false};
    if(row.grantId!==grantId)throw new ConnectionError('unknown_artifact','Unknown artifact.');
    if(busy.has(id))throw new ConnectionError('busy','This upload is in use. Try again when its current operation finishes.');
    purge(id);
    return {artifactId:id,deleted:true};
  }
  function createFromFile({grantId,name,tempPath,size,sha256,singleUse=zeroRetention,r2Key=null,r2Url=null}) {
    sweep();
    if(!Number.isSafeInteger(size)||size<0||size>maxBytes)throw new ConnectionError('invalid_request',`size must be 0–${maxBytes} bytes.`);
    if(!/^[a-f0-9]{64}$/i.test(sha256||''))throw new ConnectionError('invalid_request','sha256 must be lowercase hex.');
    checkGrantQuota(all(),grantId,size);
    const clean=path.basename(String(name||'file.bin')).replace(/[^A-Za-z0-9._-]/g,'_').slice(0,80)||'file.bin';
    const row={id:'art_'+crypto.randomBytes(12).toString('hex'),grantId,name:clean,size,sha256:sha256.toLowerCase(),offset:size,complete:true,singleUse:!!singleUse,tokenHash:null,createdAt:now(),expiresAt:now()+ttlMs,...(r2Key&&r2Url?{r2Key,r2Url}:{})};
    const token='artdl_'+crypto.randomBytes(24).toString('base64url');
    row.tokenHash=crypto.createHash('sha256').update(token).digest('hex');
    if (r2Key && r2Url) {
      if (tempPath) shredFile(tempPath, zeroRetention);
    } else {
      try {
        fs.renameSync(tempPath,blob(row.id));
      } catch(e) {
        if(e.code!=='EXDEV')throw e;
        fs.copyFileSync(tempPath,blob(row.id));
        shredFile(tempPath,zeroRetention);
      }
      fs.chmodSync(blob(row.id),0o600);
    }
    save(row);
    return {...publicRow(row),downloadToken:token};
  }
  function removeForGrants(grantIds) {
    const set=new Set(grantIds||[]);
    if(!set.size)return 0;
    let removed=0;
    for(const row of all()){
      if(set.has(row.grantId)&&!busy.has(row.id)){
        purge(row.id);removed++;
      }
    }
    return removed;
  }
  function download(id,auth) {
    const row=authorizedRow(id,typeof auth==='string'?{grantId:auth}:auth||{});
    if(!row.complete)throw new ConnectionError('artifact_incomplete',`Artifact ${id} is incomplete.`);
    const singleUse=!!row.singleUse;
    if(singleUse)shredFile(meta(id),zeroRetention);
    const consume=()=>{
      if(row.r2Key&&r2?.deleteObject){
        const t=setTimeout(()=>{void r2.deleteObject(row.r2Key).catch(()=>{});},90_000);
        t.unref?.();
      } else if(!busy.has(id)){
        shredFile(blob(id),zeroRetention);
      }
    };
    return {...publicRow(row),path:blob(id),r2Key:row.r2Key||null,r2Url:row.r2Url||null,singleUse,consume};
  }
  const timer=setInterval(sweep,60000);timer.unref();
  return {begin,append,status,ready,use,remove,removeForGrants,createFromFile,download,sweep,close:()=>clearInterval(timer),maxBytes,ttlMs,r2,tempDir:directory,zeroRetention};
}
