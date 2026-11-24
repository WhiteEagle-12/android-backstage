import fs from 'node:fs';
import path from 'node:path';
import {writePrivateJson} from './persist.mjs';

// A single-writer durable job ledger. Never redeliver a claimed job after a
// restart: its external side effect may have happened before the result saved.
export const MAX_LANES = 16;

export function createQueue({
  dataDir,
  maxPending = 32,
  retentionMs = 86400000,
  quotaBytes = 512 * 1024 * 1024,
  maxAccountJobs = 500,
  maxAccountBytes = 128 * 1024 * 1024,
} = {}) {
  // polls holds waiting long-polls per device; polling counts open polls. A phone
  // advertises how many jobs it runs at once (lanes); older phones run one.
  const jobs = new Map(), results = new Map(), watchers = new Map(), polls = new Map(), polling = new Map(), lanes = new Map();
  const INFLIGHT_HEADROOM = 256 * 1024;
  const laneLimit = deviceId => lanes.get(deviceId) || 1;
  const dir = dataDir && path.join(dataDir, 'jobs');
  if (dir) fs.mkdirSync(dir, {recursive:true, mode:0o700});
  const sizes = new Map();
  const wakeWatchers = jobId => {
    const set = watchers.get(jobId);
    if (!set) return;
    watchers.delete(jobId);
    for (const fn of [...set]) fn();
  };
  const persist = job => { if (dir) writePrivateJson(path.join(dir, job.id + '.json'), job); sizes.set(job.id,Buffer.byteLength(JSON.stringify(job))); };
  const finish = (job, state, outcome) => {
    const updated={...job,state,updatedAt:Date.now(),...(outcome ? {outcome} : {})};
    persist(updated); Object.assign(job,updated);
    const waiter = results.get(job.id);
    if (waiter) { clearTimeout(waiter.timer); results.delete(job.id); waiter.resolve(outcome || {error:{message:state}}); }
    wakeWatchers(job.id);
  };
  if (dir) for (const file of fs.readdirSync(dir).filter(f=>f.endsWith('.json'))) {
    const job = JSON.parse(fs.readFileSync(path.join(dir,file),'utf8'));
    if (!/^[a-zA-Z0-9_-]+$/.test(job.id)) throw new Error('invalid stored job id');
    if (job.state === 'running') { job.state='unknown'; job.outcome={error:{message:'relay restarted during execution; outcome unknown; do not retry blindly'}}; persist(job); }
    jobs.set(job.id, job);sizes.set(job.id,Buffer.byteLength(JSON.stringify(job)));
  }
  function sweep() {
    for (const [id,job] of jobs) {
      if (job.state === 'queued' && job.expiresAt <= Date.now()) finish(job,'expired');
      if (job.state === 'running' && job.expiresAt <= Date.now()) finish(job,'unknown',{error:{message:'Execution deadline passed; outcome unknown; inspect before retry'}});
      if (Date.now() - job.updatedAt > retentionMs && !['queued','running'].includes(job.state)) {
        jobs.delete(id);sizes.delete(id); if(dir) fs.rmSync(path.join(dir,id+'.json'),{force:true});
      }
    }
  }
  const sweeper = setInterval(sweep,1000); sweeper.unref(); sweep();
  function capacity(deviceId, accountId = null) {
    sweep();
    let reserved = 0, devicePending = 0, ownCount = 0, ownBytes = 0;
    for (const j of jobs.values()) {
      const inflight = j.state === 'queued' || j.state === 'running';
      const bytes = (sizes.get(j.id) || 0) + (inflight ? INFLIGHT_HEADROOM : 0);
      reserved += bytes;
      if (inflight && j.deviceId === deviceId) devicePending++;
      if (accountId && j.accountId === accountId) {
        ownCount++;
        ownBytes += bytes;
      }
    }
    if (reserved + INFLIGHT_HEADROOM > quotaBytes || jobs.size >= 10000 || devicePending >= maxPending) {
      const e = new Error('device queue capacity reached'); e.status=429; throw e;
    }
    if (accountId && (ownCount >= maxAccountJobs || ownBytes + INFLIGHT_HEADROOM > maxAccountBytes)) {
      const e = new Error('account queue capacity reached'); e.status=429; throw e;
    }
  }
  function take(deviceId) {
    sweep();
    let running = 0, nextQueued = null;
    for (const j of jobs.values()) {
      if (j.deviceId !== deviceId) continue;
      if (j.state === 'running') running++;
      else if (!nextQueued && j.state === 'queued') nextQueued = j;
    }
    if (running >= laneLimit(deviceId) || !nextQueued) return null;
    const updated={...nextQueued,state:'running',updatedAt:Date.now()}; persist(updated);Object.assign(nextQueued,updated);
    return {id:nextQueued.id,envelope:nextQueued.envelope};
  }
  function wake(deviceId) {
    const waiting=polls.get(deviceId);
    while(waiting?.length) {
      const job=take(deviceId); if(!job)return;
      const waiter=waiting.shift(); if(!waiting.length)polls.delete(deviceId);
      clearTimeout(waiter.timer); waiter.resolve(job);
    }
  }
  function enqueue(deviceId, job) {
    const existing=jobs.get(job.id);
    if (existing) {
      if(existing.deviceId!==deviceId || existing.accountId!==job.accountId || JSON.stringify(existing.envelope)!==JSON.stringify(job.envelope)) throw Object.assign(new Error('job id already used'),{status:409});
      return existing;
    }
    capacity(deviceId, job.accountId);
    const waiter=results.get(job.id);
    const record={...job,deviceId,state:'queued',createdAt:Date.now(),updatedAt:Date.now(),expiresAt:job.expiresAt || waiter?.expiresAt || Date.now()+30000};
    persist(record); jobs.set(job.id,record); wake(deviceId); return record;
  }
  // signal aborts when the phone's connection closes, so a dead poll neither holds
  // a lane nor takes a job it can never receive.
  function waitForJob(deviceId, timeoutMs, signal) {
    if(signal?.aborted)return Promise.resolve(null);
    const job=take(deviceId); if(job)return Promise.resolve(job);
    const waiting=polls.get(deviceId)||[];
    if(waiting.length>=laneLimit(deviceId))return Promise.resolve(null);
    return new Promise(resolve=>{
      const waiter={resolve,timer:null};
      const leave=()=>{clearTimeout(waiter.timer);signal?.removeEventListener('abort',leave);const list=polls.get(deviceId)||[];const i=list.indexOf(waiter);if(i>=0)list.splice(i,1);if(!list.length)polls.delete(deviceId);resolve(null);};
      waiter.resolve=job=>{signal?.removeEventListener('abort',leave);resolve(job);};
      waiter.timer=setTimeout(leave,timeoutMs);
      signal?.addEventListener('abort',leave,{once:true});
      waiting.push(waiter);polls.set(deviceId,waiting);
    });
  }
  // A claimed job whose reply never reached the phone was not delivered; queue it again.
  function release(jobId, deviceId) {
    const job=jobs.get(jobId);
    if(!job || job.deviceId!==deviceId || job.state!=='running' || job.expiresAt<=Date.now())return false;
    const updated={...job,state:'queued',updatedAt:Date.now()};persist(updated);Object.assign(job,updated);
    wake(deviceId);return true;
  }
  function waitForResult(jobId, timeoutMs, {deviceId}={}) {
    const job=jobs.get(jobId);
    if(job?.outcome)return Promise.resolve(job.outcome);
    return new Promise((resolve,reject)=>{
      const expiresAt=Date.now()+timeoutMs;
      const timer=setTimeout(()=>{
        results.delete(jobId);
        const job=jobs.get(jobId);
        if(job?.state==='queued')finish(job,'expired');
        const err=new Error(job?.state==='running'?'device call timed out; outcome unknown; do not retry blindly':'device call timed out');err.code='TIMEOUT';reject(err);
      },timeoutMs);
      results.set(jobId,{resolve,reject,timer,deviceId,expiresAt});
    });
  }
  function complete(jobId,outcome,deviceId) {
    const job=jobs.get(jobId), waiter=results.get(jobId);
    if(!job || (deviceId && job.deviceId!==deviceId))return false;
    if(job.state==='complete') return JSON.stringify(job.outcome)===JSON.stringify(outcome);
    if(!['running','unknown'].includes(job.state))return false;
    finish(job,'complete',outcome);wake(job.deviceId);return true;
  }
  function cancel(id) {
    const job=jobs.get(id);if(!job)return null;
    if(job.state==='queued')finish(job,'cancelled');
    else if(job.state==='running') {persist({...job,cancelRequested:true});job.cancelRequested=true;}
    return job;
  }
  function recoverDevice(id) {
    for (const job of jobs.values()) if (job.deviceId === id && job.state === 'running')
      finish(job,'unknown',{error:{message:'device restarted during execution; outcome unknown; inspect before retry'}});
  }
  function dropDevice(id) {
    for(const job of jobs.values())if(job.deviceId===id && ['queued','running'].includes(job.state))finish(job,'revoked');
    for(const waiter of polls.get(id)||[]){clearTimeout(waiter.timer);waiter.resolve(null);}polls.delete(id);
    polling.delete(id);
  }
  function waitForChange(jobId, timeoutMs, signal) {
    const job = jobs.get(jobId);
    if (!job || !['queued', 'running'].includes(job.state) || timeoutMs <= 0 || signal?.aborted) {
      return Promise.resolve(job || null);
    }
    return new Promise((resolve) => {
      let set = watchers.get(jobId);
      if (!set) { set = new Set(); watchers.set(jobId, set); }
      const done = () => {
        clearTimeout(timer);
        signal?.removeEventListener('abort', done);
        const cur = watchers.get(jobId);
        if (cur) { cur.delete(done); if (!cur.size) watchers.delete(jobId); }
        resolve(jobs.get(jobId) || null);
      };
      const timer = setTimeout(done, Math.min(timeoutMs, 25_000));
      signal?.addEventListener('abort', done, { once: true });
      set.add(done);
    });
  }
  function close() {
    clearInterval(sweeper);
    for(const list of polls.values())for(const w of list){clearTimeout(w.timer);w.resolve(null);}polls.clear();polling.clear();
    for(const w of results.values()){clearTimeout(w.timer);const e=new Error('relay closed; query job status before retry');e.code='CLOSED';w.reject(e);}results.clear();
    for(const id of [...watchers.keys()]) wakeWatchers(id);
  }
  return {enqueue,waitForJob,release,waitForResult,waitForChange,complete,cancel,dropDevice,recoverDevice,close,capacity,
    // A device may hold one open poll per lane; further polls are duplicates.
    setLanes:(id,count)=>{const n=Number.isInteger(count)?Math.min(Math.max(count,1),MAX_LANES):1;lanes.set(id,n);wake(id);return n;},
    lanes:laneLimit,
    beginPoll:id=>{const open=polling.get(id)||0;if(open>=laneLimit(id))return false;polling.set(id,open+1);return true;},
    endPoll:id=>{const open=(polling.get(id)||1)-1;if(open>0)polling.set(id,open);else polling.delete(id);},
    isPolling:id=>(polling.get(id)||0)>0,
    get:id=>{sweep();return jobs.get(id);}, list:accountId=>{sweep();return [...jobs.values()].filter(j=>j.accountId===accountId);},
    metrics:()=>{sweep();const counts={};for(const j of jobs.values())counts[j.state]=(counts[j.state]||0)+1;return counts;}};
}
