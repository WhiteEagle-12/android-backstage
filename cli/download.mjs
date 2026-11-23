import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {writePrivateJson,withConfigLock} from './state.mjs';

const CHUNK=1024*1024;
const digest=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
const exists=file=>{try{fs.lstatSync(file);return true;}catch(e){if(e.code==='ENOENT')return false;throw e;}};

/** Saves each verified chunk before requesting another; the same command resumes. */
export async function downloadFile(call,{remote,local,device,chunkSize=CHUNK}) {
  if(!remote||!local||!device)throw new Error('Choose a device, a phone file and a new local file path.');
  if(!Number.isSafeInteger(chunkSize)||chunkSize<1||chunkSize>CHUNK)throw new Error('Chunk size must be from 1 to 1048576 bytes.');
  const destination=path.resolve(local),record=destination+'.backstage-download.json',partial=destination+'.backstage-part';
  return withConfigLock(record,async()=>{
    if(exists(destination))throw new Error('The local file already exists. Choose a new path.');
    let state;
    if(exists(record)){
      if(!fs.lstatSync(record).isFile()||fs.lstatSync(record).isSymbolicLink())throw new Error('The download record is not a regular file. Choose a new path.');
      state=JSON.parse(fs.readFileSync(record,'utf8'));
      if(state.version!==1||state.remote!==remote||state.device!==device||state.destination!==destination)throw new Error('This partial download belongs to a different file or device. Choose a new path.');
      if(!Number.isSafeInteger(state.offset)||state.offset<0||state.fileId!==null&&!/^file_[a-f0-9]{64}$/.test(state.fileId)||!/^([a-f0-9]{64})$/.test(state.sha256))throw new Error('The download record is invalid. Choose a new path.');
    }else{
      if(exists(partial))throw new Error('A partial file already exists without a download record. Choose a new path.');
      const fd=fs.openSync(partial,'wx',0o600);fs.closeSync(fd);
      state={version:1,device,remote,destination,offset:0,fileId:null,totalBytes:null,sha256:digest(Buffer.alloc(0))};
      writePrivateJson(record,state);
    }
    const fd=fs.openSync(partial,fs.constants.O_RDWR|fs.constants.O_NOFOLLOW);
    let hash=crypto.createHash('sha256'),resumedBytes=state.offset;
    try{
      const stat=fs.fstatSync(fd);if(!stat.isFile()||stat.size<state.offset)throw new Error('The partial file is missing bytes. Choose a new path.');
      // Bytes written just before a crash have no durable receipt yet.
      fs.ftruncateSync(fd,state.offset);
      const buffer=Buffer.alloc(CHUNK);
      for(let at=0;at<state.offset;){const n=fs.readSync(fd,buffer,0,Math.min(CHUNK,state.offset-at),at);if(!n)throw new Error('The partial file ended early.');hash.update(buffer.subarray(0,n));at+=n;}
      if(hash.copy().digest('hex')!==state.sha256)throw new Error('The partial file changed. Choose a new path.');
      for(;;){
        const row=await call('android.pull',{path:remote,offset:state.offset,length:chunkSize,...(state.fileId?{fileId:state.fileId}:{})});
        if(row?.outcome!=='succeeded')throw new Error(row?.error||'The phone file could not be read. Run the same command to resume.');
        if(!/^file_[a-f0-9]{64}$/.test(row.fileId||'')||state.fileId&&row.fileId!==state.fileId||!Number.isSafeInteger(row.totalBytes)||row.totalBytes<0||state.totalBytes!==null&&state.totalBytes!==row.totalBytes)throw new Error('The phone file changed. Choose a new local path to start again.');
        const bytes=Buffer.from(typeof row.dataBase64==='string'?row.dataBase64:'','base64');
        if(row.offset!==state.offset||row.bytes!==bytes.length||bytes.length>chunkSize||row.nextOffset!==state.offset+bytes.length||row.nextOffset>row.totalBytes||row.eof!==(row.nextOffset===row.totalBytes)||!row.eof&&bytes.length===0||digest(bytes)!==row.sha256)throw new Error('The file chunk failed its integrity check. Run the same command to resume.');
        let written=0;while(written<bytes.length)written+=fs.writeSync(fd,bytes,written,bytes.length-written,state.offset+written);
        fs.fsyncSync(fd);hash.update(bytes);
        state={...state,offset:row.nextOffset,fileId:row.fileId,totalBytes:row.totalBytes,sha256:hash.copy().digest('hex')};writePrivateJson(record,state);
        if(row.eof)break;
      }
    }finally{fs.closeSync(fd);}
    // Publish without replacing an existing destination, including a racing writer.
    fs.linkSync(partial,destination);
    fs.unlinkSync(partial);fs.unlinkSync(record);
    return {outcome:'succeeded',path:destination,bytes:state.offset,sha256:state.sha256,resumedBytes};
  });
}
