import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';

const canonical=value=>Array.isArray(value)?value.map(canonical):value&&typeof value==='object'?Object.fromEntries(Object.keys(value).sort().map(k=>[k,canonical(value[k])])):value;
/** Record a write before dispatch, including uploads. An interrupted write is never replayed. */
export const RECEIPT_RETENTION_MS=86400000,MAX_RECEIPTS=16384;
/**
 * Completed receipts replay for 24 hours. Past maxReceipts the oldest completed receipts are evicted first, so a write
 * never fails for quota; receipts whose outcome is unknown (running, interrupted, or result.outcome==='unknown') are
 * never evicted. The directory is listed once per process and tracked in memory afterwards.
 */
export function createRequestJournal({directory,now=Date.now,reconcile,memoryOnly=false,maxReceipts=memoryOnly?2048:MAX_RECEIPTS,retentionMs=memoryOnly?300000:RECEIPT_RETENTION_MS}={}){
 const running=new Map();
 const index=new Map(); // receipt key -> {time,protect}; insertion order is oldest first
 const memory=memoryOnly?new Map():null;
 let scanned=memoryOnly;
 const track=(key,row)=>{index.delete(key);index.set(key,{time:now(),protect:!row.result||row.result.outcome==='unknown'});};
 const load=()=>{
  if(scanned||memoryOnly)return;scanned=true;const found=[];
  for(const entry of fs.readdirSync(directory)){
   if(!entry.endsWith('.json'))continue;const p=path.join(directory,entry);
   try{const row=JSON.parse(fs.readFileSync(p,'utf8'));found.push([entry.slice(0,-5),fs.statSync(p).mtimeMs,!row.result||row.result.outcome==='unknown']);}catch{}
  }
  found.sort((a,b)=>a[1]-b[1]);for(const [key,time,protect] of found)index.set(key,{time,protect});
 };
 /** Drops expired completed receipts, then the oldest completed ones until there is room for one more. */
 const makeRoom=()=>{
  const cutoff=now()-retentionMs;
  for(const [key,entry] of index){
   if(entry.protect&&!memoryOnly)continue;
   if(entry.protect&&memoryOnly&&entry.time>=cutoff&&index.size<maxReceipts)continue;
   if(entry.time>=cutoff&&index.size<maxReceipts)break;
   if(memoryOnly){memory.delete(key);}
   else{try{fs.unlinkSync(path.join(directory,key+'.json'));}catch(e){if(e.code!=='ENOENT')continue;}}
   index.delete(key);
  }
 };
 const hash=value=>createHash('sha256').update(value).digest('hex');
 const readRow=key=>{
  if(memoryOnly){
   const row=memory.get(key);
   const meta=index.get(key);
   if(row&&meta&&!meta.protect&&now()-meta.time>retentionMs){memory.delete(key);index.delete(key);return undefined;}
   return row;
  }
  try{return JSON.parse(fs.readFileSync(path.join(directory,key+'.json'),'utf8'));}catch(e){if(e.code==='ENOENT')return undefined;throw e;}
 };
 const write=(key,row)=>{
  if(memoryOnly){memory.set(key,row);track(key,row);return;}
  const file=path.join(directory,key+'.json');
  fs.mkdirSync(path.dirname(file),{recursive:true,mode:0o700});const tmp=file+'.tmp';fs.writeFileSync(tmp,JSON.stringify(row),{mode:0o600});fs.renameSync(tmp,file);track(key,row);
 };
 return async function execute(owner,name,args,action){
  if(!args.requestId)return action();
  const key=hash(owner+':'+args.requestId),fingerprint=hash(JSON.stringify(canonical({name,args})));
  if(running.has(key)){const active=running.get(key);if(active.fingerprint!==fingerprint)return {outcome:'failed',code:'request_id_conflict',inputDelivered:false,error:'This requestId already names a different write.'};return active.promise;}
  const previous=readRow(key);
  if(previous){
   if(previous.fingerprint!==fingerprint)return {outcome:'failed',code:'request_id_conflict',inputDelivered:false,error:'This requestId already names a different write.'};
   if(previous.result?.outcome==='unknown'&&previous.result.jobId&&reconcile){
    // Query only: even a missing job is not permission to repeat a write.
    try {const result=await reconcile(owner,name,args,previous.result.jobId);if(result){write(key,{...previous,result});return result;}}catch{}
   }
   return previous.result||{outcome:'unknown',code:'input_outcome_unknown',inputDelivered:null,requestId:args.requestId,error:'The earlier write has no receipt. Read the device state before issuing a new write.'};
  }
  if(!memoryOnly)fs.mkdirSync(directory,{recursive:true,mode:0o700});
  load();makeRoom();
  if(index.size>=maxReceipts)return {outcome:'failed',code:'receipt_limit',inputDelivered:false,error:`The receipt store holds ${index.size} writes with an unknown outcome, which are never discarded. Read the device state, then retry later; completed receipts are evicted automatically.`};
  const row={fingerprint,startedAt:now()};write(key,row);
  const promise=(async()=>{try{const result=await Promise.resolve().then(action);write(key,{...row,result});return result;}catch(e){const definite=['busy','invalid_request','relay_busy','submission_rejected','device_offline','device_paused','device_not_authorized','device_required','unknown_device','ambiguous_device','device_link_revoked','artifact_incomplete','transfer_prepare_failed','unknown_artifact','integrity_mismatch','access_revoked','assistant_paused','invalid_argument'].includes(e.code);const result={outcome:definite?'failed':'unknown',code:definite?e.code:'input_outcome_unknown',inputDelivered:definite?false:null,requestId:args.requestId,error:definite?e.message:e.code==='tool_failed'&&e.message?`The phone reported: ${e.message.replace(/\.?$/,'.')} Check the current device state before a new write.`:'The write has no final receipt. Check the current device state before a new write.',...(e.jobId?{jobId:e.jobId}:{})};write(key,{...row,result});return result;}finally{running.delete(key);}})();
  running.set(key,{fingerprint,promise});return promise;
 };
}
