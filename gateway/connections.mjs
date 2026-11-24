import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {beginConnection} from '../cli/connect.mjs';
import {writePrivateJson, writePrivateJsonAsync} from '../cli/state.mjs';
import {encryptedCall} from '../cli/backstage.mjs';
import {planLimits} from './plans.mjs';

// Errors that callers can act on. `code` is stable; `message` is for people.
export class ConnectionError extends Error {
  constructor(code,message,extra={}) {super(message);this.code=code;Object.assign(this,extra);}
}
const DEVICE_ID=/^phone_[a-f0-9]{32}$/,GRANT_ID=/^grant_[a-f0-9]{32}$/,OWNER_ID=/^owner_[A-Za-z0-9_-]{43}$/;
const norm=value=>String(value||'').trim().toLowerCase();
export const validDeviceName=name=>typeof name==='string'&&/^[A-Za-z0-9][A-Za-z0-9 ._-]{0,31}$/.test(name.trim());

/**
 * Owner → many devices → many assistant grants.
 *
 * Layout under `directory`:
 *   devices.json            {deviceId:{ownerId,name,createdAt,linkedAt,needsReconnect,lastError}}
 *   phones/<deviceId>.json  the phone link (relay connection key, agent id, link key, counters)
 *   grant_<id>.json         {ownerId,clientId,clientName,access:'all'|[deviceId],createdAt}
 *   grant_<id>.json.revoked tombstone
 *   enrollments.json        legacy {ownerId:deviceId}; read once, never written again
 *
 * A device ID is stable for the life of the record. Reconnecting a phone replaces
 * the link file behind the same ID, so assistant access and names survive.
 */
export function createConnections({directory,relayUrl,relayInternalUrl=null,relayInternalToken=null,directRoutes=null,vault=null,planOf=null,begin=beginConnection,call=encryptedCall,fetchImpl=fetch,flushMs=2000,now=Date.now,log=()=>{}}) {
  let planResolver = typeof planOf === 'function' ? planOf : null;
  const limitsFor = ownerId => planResolver ? planLimits(planResolver(ownerId)) : { id: 'unbounded', name: 'Local', maxDevices: 10, maxAssistants: 200, maxParallelScreens: 50, dailyActions: 50_000, screenRecording: true, webhooks: true, maxFileBytes: 128 * 1024 * 1024 };
  fs.mkdirSync(directory,{recursive:true,mode:0o700});
  const phones=path.join(directory,'phones');
  fs.mkdirSync(phones,{recursive:true,mode:0o700});
  const devicesFile=path.join(directory,'devices.json');
  const readJson=(file,fallback)=>{try {return JSON.parse(fs.readFileSync(file,'utf8'));} catch(e) {if(e.code==='ENOENT')return fallback;throw e;}};
  let devices=readJson(devicesFile,{});
  const agentIndex=new Map();
  // In-memory phone config cache + coalesced async disk flush eliminates 4x fsyncSync + 2x AES-GCM decrypts per tool call.
  const phoneCache=new Map();
  const dirtyPhones=new Set();
  let flushTimer=null, flushing=null;
  const sealForDisk=(id,data)=>vault?vault.sealPhoneConfig(data,id):data;
  const scheduleFlush=()=>{
    if(flushTimer||flushMs<=0)return;
    flushTimer=setTimeout(()=>{flushTimer=null;void flushDirtyPhones();},flushMs);
    flushTimer.unref?.();
  };
  async function flushDirtyPhones(){
    if(flushing)return flushing;
    flushing=(async()=>{
      while(dirtyPhones.size){
        const batch=[...dirtyPhones];
        dirtyPhones.clear();
        for(const id of batch){
          const cached=phoneCache.get(id);
          if(!cached||!devices[id])continue;
          const targetFile=phoneFile(id);
          if(!fs.existsSync(targetFile))continue;
          try{await writePrivateJsonAsync(targetFile,sealForDisk(id,cached));}catch(e){log(`flush ${id}: ${e.message}`);}
        }
      }
    })().finally(()=>{flushing=null;});
    return flushing;
  }
  function flushDirtyPhonesSync(){
    if(flushTimer){clearTimeout(flushTimer);flushTimer=null;}
    for(const id of [...dirtyPhones]){
      dirtyPhones.delete(id);
      const cached=phoneCache.get(id);
      if(!cached||!devices[id])continue;
      try{writePrivateJson(phoneFile(id),sealForDisk(id,cached));}catch{}
    }
  }
  const saveDevices=()=>writePrivateJson(devicesFile,devices);
  const phoneFile=id=>{if(!DEVICE_ID.test(id||''))throw new ConnectionError('unknown_device','Unknown device.');return path.join(phones,id+'.json');};
  const writePhoneFile=(id,data,{immediate=true}={})=>{
    if(data?.agentId)agentIndex.set(data.agentId,id);
    phoneCache.set(id,structuredClone(data));
    if(immediate||flushMs<=0){
      dirtyPhones.delete(id);
      return writePrivateJson(phoneFile(id),sealForDisk(id,data));
    }
    dirtyPhones.add(id);
    scheduleFlush();
  };
  const readPhoneFile=id=>{
    if(phoneCache.has(id))return structuredClone(phoneCache.get(id));
    const file=phoneFile(id),raw=readJson(file,null);
    if(raw?.agentId)agentIndex.set(raw.agentId,id);
    if(!raw)return null;
    if(!vault){phoneCache.set(id,structuredClone(raw));return raw;}
    const {config,migrated}=vault.openPhoneConfig(raw,id);
    if(migrated)writePrivateJson(file,vault.sealPhoneConfig(config,id));
    phoneCache.set(id,structuredClone(config));
    return config;
  };
  const dropAgentIndex=deviceId=>{for(const [k,v] of agentIndex)if(v===deviceId)agentIndex.delete(k);};
  const readLinkConfig=target=>target?.legacy?readJson(target.filePath,null):readPhoneFile(target.id);
  const linkIoHooks=target=>target?.legacy?{}:{inProcessLockOnly:true,readConfig:()=>readPhoneFile(target.id)||{},writeConfig:(_p,data)=>writePhoneFile(target.id,data,{immediate:false})};
  const grantFile=id=>{if(!GRANT_ID.test(id||''))throw new ConnectionError('access_revoked','This assistant connection is not valid.');return path.join(directory,id+'.json');};

  // One-time adoption of the single-phone index: the saved phone becomes a named device.
  const legacyIndex=readJson(path.join(directory,'enrollments.json'),{});
  let adopted=false;
  const adopt=(ownerId,deviceId)=>{
    if(!OWNER_ID.test(ownerId||'')||!DEVICE_ID.test(deviceId||'')||devices[deviceId]||!fs.existsSync(path.join(phones,deviceId+'.json')))return;
    const stat=fs.statSync(path.join(phones,deviceId+'.json'));
    devices[deviceId]={ownerId,name:'phone',createdAt:stat.birthtimeMs||stat.mtimeMs,linkedAt:stat.mtimeMs,needsReconnect:false};adopted=true;
  };
  for(const [ownerId,deviceId] of Object.entries(legacyIndex))adopt(ownerId,deviceId);
  // Grants written by the single-phone gateway name their phone directly.
  for(const name of fs.readdirSync(directory))if(/^grant_[a-f0-9]{32}\.json$/.test(name)){const row=readJson(path.join(directory,name),null);if(row?.phoneId)adopt(row.ownerId,row.phoneId);}
  if(adopted)saveDevices();
  for(const id of Object.keys(devices))try{readPhoneFile(id);}catch{}

  const readGrant=id=>{const file=grantFile(id);if(!fs.existsSync(file)||fs.existsSync(file+'.revoked'))return null;const row=readJson(file,null);if(!row)return null;
    // Older grants named one phone and had no access list. They keep working with every device of that owner.
    if(row.phoneId&&!devices[row.phoneId]){adopted=false;adopt(row.ownerId,row.phoneId);if(adopted)saveDevices();}
    if(!row.access)row.access=row.legacyConfigPath||row.links?'legacy':'all';return row;};
  const grantIds=()=>fs.readdirSync(directory).filter(name=>/^grant_[a-f0-9]{32}\.json$/.test(name)).map(name=>name.slice(0,-5));
  const ownerDevices=ownerId=>OWNER_ID.test(ownerId||'')?Object.entries(devices).filter(([,row])=>row.ownerId===ownerId).map(([id,row])=>publicDevice(id,row)):[];
  const publicDevice=(id,row)=>({id,name:row.name,createdAt:row.createdAt,linkedAt:row.linkedAt,needsReconnect:!!row.needsReconnect,lastError:row.lastError||null});
  const findOwned=(ownerId,ref)=>{
    const list=ownerDevices(ownerId),key=norm(ref);if(!key)return null;
    const matches=list.filter(d=>norm(d.id)===key||norm(d.name)===key);
    if(matches.length>1)throw new ConnectionError('ambiguous_device',`"${ref}" matches more than one device. Use its ID from android_devices.`,{devices:matches.map(d=>({id:d.id,name:d.name}))});
    return matches[0]||null;
  };
  const nameTaken=(ownerId,name,exceptId)=>ownerDevices(ownerId).some(d=>d.id!==exceptId&&norm(d.name)===norm(name));
  const uniqueName=(ownerId,base)=>{const raw=validDeviceName(base||'')?base.trim():'phone';if(!nameTaken(ownerId,raw))return raw;for(let i=2;i<1000;i++)if(!nameTaken(ownerId,`${raw} ${i}`))return `${raw} ${i}`;throw new ConnectionError('busy','Too many devices.');};
  const normalizeAccess=(ownerId,access)=>{
    if(access==='all'||access==null)return 'all';
    if(!Array.isArray(access))throw new ConnectionError('invalid_request','Choose which devices this assistant may use.');
    const owned=new Set(ownerDevices(ownerId).map(d=>d.id)),ids=[...new Set(access.filter(id=>owned.has(id)))];
    return ids;
  };
  // `phoneId` is kept as a compatibility field so the previous single-phone release can still read a grant that resolves to one device.
  const writeGrant=(id,row)=>{
    const owned=ownerDevices(row.ownerId).map(d=>d.id);
    const single=Array.isArray(row.access)&&row.access.length===1?row.access[0]:row.access==='all'&&owned.length===1?owned[0]:null;
    if(single)row.phoneId=single;else if(row.phoneId&&!(row.access==='all'||row.access?.includes?.(row.phoneId)))delete row.phoneId;
    writePrivateJson(grantFile(id),row);
  };

  function accessibleDevices(grantId) {
    const grant=readGrant(grantId);if(!grant)throw new ConnectionError('access_revoked','This assistant’s access was removed. Connect Android Backstage again from the assistant.');
    if(grant.access==='legacy'){const file=grant.legacyConfigPath||grantFile(grantId);const config=readJson(file,{});return [{id:grantId,name:config.device||'phone',legacy:true,filePath:file,needsReconnect:false}];}
    const owned=ownerDevices(grant.ownerId);
    return grant.access==='all'?owned:owned.filter(d=>grant.access.includes(d.id));
  }
  function resolveDevice(grantId,ref) {
    const list=accessibleDevices(grantId);
    if(ref==null||String(ref).trim()===''){
      if(list.length===1)return list[0];
      if(list.length===0)throw new ConnectionError('no_devices','No device is available to this assistant. Add or allow a device at the Backstage account page.');
      throw new ConnectionError('device_required',`Say which device: ${list.map(d=>`${d.name} (${d.id})`).join(', ')}. Not choosing one for you.`,{devices:list.map(d=>({id:d.id,name:d.name}))});
    }
    const key=norm(ref),matches=list.filter(d=>norm(d.id)===key||norm(d.name)===key);
    if(matches.length>1)throw new ConnectionError('ambiguous_device',`"${ref}" matches more than one device. Use its ID.`,{devices:matches.map(d=>({id:d.id,name:d.name}))});
    if(matches.length===1)return matches[0];
    const grant=readGrant(grantId);
    if(grant&&grant.access!=='legacy'&&findOwned(grant.ownerId,ref))throw new ConnectionError('device_not_authorized',`This assistant is not allowed to use "${ref}". The owner can allow it at the Backstage account page.`);
    throw new ConnectionError('unknown_device',`No device named "${ref}". Available: ${list.map(d=>d.name).join(', ')||'none'}. Not using another device.`,{devices:list.map(d=>({id:d.id,name:d.name}))});
  }
  const linkPath=device=>device.legacy?device.filePath:phoneFile(device.id);
  // The relay runs on this computer: reach it on loopback instead of out through Cloudflare and back.
  const trim=url=>String(url||'').replace(/\/$/,'');
  const transportFor=config=>relayInternalUrl&&relayUrl&&trim(config?.relayUrl)===trim(relayUrl)?trim(relayInternalUrl):null;
  const relayBase=config=>transportFor(config)||config.relayUrl;
  // Addresses each phone reported at its last relay hello, for the direct route.
  const lanCache=new Map();
  // How the last call to each device travelled (direct or relay), for the call audit.
  const routes=new Map();
  const markLink=(deviceId,patch)=>{probeCache.delete(deviceId);if(devices[deviceId]){Object.assign(devices[deviceId],patch);saveDevices();}};

  // Ask the relay whether one device's link still works and whether the phone is polling.
  // Probes are cached briefly so android_devices under several concurrent assistants does not hammer the relay.
  const probeCache=new Map();const PROBE_TTL=4000;
  async function probe(device,{fresh=false}={}) {
    const cached=probeCache.get(device.id);
    if(!fresh&&cached&&cached.at+PROBE_TTL>now()&&cached.name===device.name)return cached.result;
    const result=await probeUncached(device);
    probeCache.set(device.id,{at:now(),name:device.name,result});return result;
  }
  async function probeUncached(device) {
    if(device.needsReconnect)return {...device,online:false,status:'needs_reconnect'};
    let config;try {config=readLinkConfig(device);} catch {config=null;}
    const link=config?.links?.[config.device];
    if(!config?.relayUrl||!config.apiKey||!link)return {...device,online:false,status:'needs_reconnect'};
    try {
      const response=await fetchImpl(relayBase(config)+'/v1/devices',{headers:{Authorization:'Bearer '+config.apiKey},signal:AbortSignal.timeout(6000)});
      if(response.status===401){markLink(device.id,{needsReconnect:true,lastError:'The phone removed this link.'});return {...device,needsReconnect:true,online:false,status:'needs_reconnect'};}
      if(!response.ok)return {...device,online:false,status:'unknown'};
      const row=(await response.json()).devices?.find(d=>d.id===link.deviceId);
      if(!row)return {...device,online:false,status:'needs_reconnect'};
      const lanAddresses=Array.isArray(row.lanAddresses)?row.lanAddresses:[];
      lanCache.set(device.id,{addresses:lanAddresses,at:now()});
      // A phone the relay has not heard from lately can still answer directly.
      // Avoid stalling android_devices on stale IPs of phones offline for >5 minutes:
      // return cached direct state immediately and refresh in the background.
      let direct=null;
      if(directRoutes&&!device.legacy){
        const lastSeenMs=row.lastSeen?Date.parse(row.lastSeen):NaN;
        const staleOffline=!row.online&&Number.isFinite(lastSeenMs)&&now()-lastSeenMs>5*60_000;
        if(staleOffline){
          direct=directRoutes.status?directRoutes.status(device.id):null;
          void directRoutes.route(device.id,lanAddresses,link.deviceId).catch(()=>{});
        }else{
          direct=await directRoutes.route(device.id,lanAddresses,link.deviceId);
        }
      }
      const online=!!row.online||!!direct;
      return {...device,online,status:online?'online':'offline',route:direct?'direct':row.online?'relay':null,model:row.model||null,lastSeen:row.lastSeen||null,agentVersion:row.agentVersion||null,lanAddresses};
    } catch {return {...device,online:false,status:'unknown'};}
  }

  /** The phone's own listener over the tailnet or LAN when it answers, else null (use the relay). */
  async function directUrlFor(target,config) {
    if(!directRoutes||!target?.id||target.legacy)return null;
    const link=config?.links?.[config.device];if(!link?.deviceId)return null;
    let known=lanCache.get(target.id);
    if(!known||now()-known.at>60000){try{await probe(target);}catch{}known=lanCache.get(target.id);}
    return known?directRoutes.route(target.id,known.addresses,link.deviceId):null;
  }

  function checkAssistantAllowance(ownerId, incomingClientIds) {
    if(!OWNER_ID.test(ownerId||''))return;
    const limits=limitsFor(ownerId);
    const activeGrants=grantIds().map(readGrant).filter(r=>r&&r.ownerId===ownerId);
    const existingClients=new Set(activeGrants.map(r=>r.clientId));
    const uniqueIncoming=new Set((incomingClientIds||[]).filter(Boolean));
    let newCount=0;
    for(const cid of uniqueIncoming)if(!existingClients.has(cid))newCount++;
    if(activeGrants.length+newCount>limits.maxAssistants){
      const noun=limits.maxAssistants===1?'1 connected assistant':`${limits.maxAssistants} connected assistants`;
      throw new ConnectionError('plan_limit',`Your ${limits.name} plan includes up to ${noun}. Remove an existing assistant in Backstage or upgrade to Pro for unlimited assistants.`);
    }
  }
  function authorize(ownerId,clientId,clientName,access='all') {
    if(!OWNER_ID.test(ownerId||'')||ownerDevices(ownerId).length===0)throw new ConnectionError('no_devices','Connect your Android device first');
    const chosen=normalizeAccess(ownerId,access);
    for(const id of grantIds()){const row=readGrant(id);if(row&&row.ownerId===ownerId&&row.clientId===clientId){row.access=chosen;row.clientName=clientName||row.clientName;writeGrant(id,row);return id;}}
    checkAssistantAllowance(ownerId,[clientId]);
    const id='grant_'+crypto.randomBytes(16).toString('hex');
    writeGrant(id,{ownerId,clientId,clientName,access:chosen,createdAt:now()});return id;
  }
  const active=id=>{try {return fs.existsSync(grantFile(id))&&!fs.existsSync(grantFile(id)+'.revoked');} catch {return false;}};
  // What each assistant is doing now: last command time and the screens it holds.
  // Kept in memory; a gateway restart forgets it and leases still expire on the device.
  const work=new Map();
  const activityOf=grantId=>{const row=work.get(grantId);if(!row)return {lastUsedAt:null,sessions:[]};
    for(const [id,s] of row.sessions)if(s.leaseExpiresAt&&s.leaseExpiresAt<now())row.sessions.delete(id);
    return {lastUsedAt:row.lastUsedAt,sessions:[...row.sessions.values()].map(({sessionId,deviceId,mode,display,startedAt})=>({sessionId,deviceId,mode,display,startedAt}))};};
  function activeSessionsForOwner(ownerId){
    if(!OWNER_ID.test(ownerId||''))return 0;
    const seen=new Set();
    for(const id of grantIds()){
      const row=readGrant(id);if(!row||row.ownerId!==ownerId)continue;
      for(const s of activityOf(id).sessions)if(s.mode!=='foreground')seen.add(s.sessionId);
    }
    return seen.size;
  }
  function recordWork(grantId,deviceId,name,args,result){
    const row=work.get(grantId)||{lastUsedAt:0,sessions:new Map()};row.lastUsedAt=now();work.set(grantId,row);
    if((name==='android.task_begin'||name==='android.task'&&args?.operation==='begin')&&result?.sessionId)row.sessions.set(result.sessionId,{sessionId:result.sessionId,deviceId,mode:result.mode||'private',display:result.display??null,startedAt:now(),leaseExpiresAt:result.leaseExpiresAt||null});
    else if((name==='android.task_finish'||name==='android.task'&&args?.operation==='finish')&&args?.sessionId)row.sessions.delete(args.sessionId);
    else if(args?.sessionId&&['session_expired','session_unavailable','display_unavailable'].includes(result?.code))row.sessions.delete(args.sessionId);
    else if(args?.sessionId&&row.sessions.has(args.sessionId)&&result?.leaseExpiresAt)row.sessions.get(args.sessionId).leaseExpiresAt=result.leaseExpiresAt;
  }
  async function callLink(target,name,args){
    const filePath=linkPath(target);const config=readLinkConfig(target);
    if(!config)throw new ConnectionError('device_link_revoked',`"${target.name}" has no working link.`);
    return call({...config,...linkIoHooks(target),file:config,filePath,directUrl:null,transportRelayUrl:transportFor(config)},name,args,{});
  }

  return {
    setPlanResolver(fn) { planResolver = typeof fn === 'function' ? fn : null; },
    checkAssistantAllowance,
    activeSessionsForOwner,
    flush:flushDirtyPhones,
    flushSync:flushDirtyPhonesSync,
    // ---- devices -----------------------------------------------------------
    devices:ownerDevices,
    device:(ownerId,ref)=>findOwned(ownerId,ref),
    /** The device whose link carries this agent identity, with the shared link key. The phone proves possession of that key to manage its owner's account. */
    deviceByAgent(agentId) {
      if(!/^agt_[A-Za-z0-9_-]{8,64}$/.test(agentId||''))return null;
      const indexed=agentIndex.get(agentId);
      if(indexed&&devices[indexed]){
        const config=readPhoneFile(indexed);
        if(config?.agentId===agentId){
          const linkKey=config?.links?.[config.device]?.linkKey;
          if(!linkKey||!devices[indexed].ownerId)return null;
          return {device:publicDevice(indexed,devices[indexed]),ownerId:devices[indexed].ownerId,linkKey};
        }
        agentIndex.delete(agentId);
      }
      for(const [id,row] of Object.entries(devices)){
        const raw=readJson(phoneFile(id),null);
        if(raw?.agentId)agentIndex.set(raw.agentId,id);
        if(raw?.agentId!==agentId)continue;
        const config=readPhoneFile(id);
        const linkKey=config?.links?.[config.device]?.linkKey;
        if(!linkKey||!row.ownerId)return null;
        return {device:publicDevice(id,row),ownerId:row.ownerId,linkKey};
      }
      return null;
    },
    hasPhone(ownerId) {return ownerDevices(ownerId).length>0;},
    rename(ownerId,deviceId,name) {
      const device=findOwned(ownerId,deviceId);if(!device||device.id!==deviceId)throw new ConnectionError('unknown_device','Device not found.');
      if(!validDeviceName(name))throw new ConnectionError('invalid_request','Use 1–32 letters, numbers, spaces, dots, dashes or underscores.');
      if(nameTaken(ownerId,name,deviceId))throw new ConnectionError('invalid_request','Another device already uses that name.');
      markLink(deviceId,{name:name.trim()});return publicDevice(deviceId,devices[deviceId]);
    },
    async remove(ownerId,deviceId) {
      const device=findOwned(ownerId,deviceId);if(!device||device.id!==deviceId)throw new ConnectionError('unknown_device','Device not found.');
      let config=null;try{config=readPhoneFile(deviceId);}catch{}
      dropAgentIndex(deviceId);
      phoneCache.delete(deviceId);dirtyPhones.delete(deviceId);
      delete devices[deviceId];probeCache.delete(deviceId);lanCache.delete(deviceId);routes.delete(deviceId);saveDevices();fs.rmSync(phoneFile(deviceId),{force:true});
      for(const id of grantIds()){const row=readGrant(id);if(row&&Array.isArray(row.access)&&row.access.includes(deviceId)){row.access=row.access.filter(x=>x!==deviceId);writeGrant(id,row);}}
      if(config?.relayUrl&&config?.apiKey&&String(config.apiKey).startsWith('bsc_')){
        try{await fetchImpl(relayBase(config)+'/v1/connection',{method:'DELETE',headers:{Authorization:'Bearer '+config.apiKey},signal:AbortSignal.timeout(5000)});}catch{}
      }
    },
    /** Revoke all grants and delete all devices/links for an owner (account deletion). */
    async removeAllForOwner(ownerId) {
      if(!OWNER_ID.test(ownerId||''))return {grantIds:[],deviceIds:[]};
      const ownedGrants=grantIds().filter(id=>readGrant(id)?.ownerId===ownerId);
      for(const id of ownedGrants){
        fs.rmSync(grantFile(id),{force:true});
        fs.rmSync(grantFile(id)+'.revoked',{force:true});
        work.delete(id);
      }
      const ownedDevices=ownerDevices(ownerId).map(d=>d.id);
      const revokes=[];
      for(const deviceId of ownedDevices){
        let config=null;try{config=readPhoneFile(deviceId);}catch{}
        dropAgentIndex(deviceId);
        phoneCache.delete(deviceId);dirtyPhones.delete(deviceId);
        delete devices[deviceId];probeCache.delete(deviceId);lanCache.delete(deviceId);routes.delete(deviceId);fs.rmSync(phoneFile(deviceId),{force:true});
        if(config?.relayUrl&&config?.apiKey&&String(config.apiKey).startsWith('bsc_')){
          revokes.push(fetchImpl(relayBase(config)+'/v1/connection',{method:'DELETE',headers:{Authorization:'Bearer '+config.apiKey},signal:AbortSignal.timeout(5000)}).catch(()=>{}));
        }
      }
      if(ownedDevices.length)saveDevices();
      if(revokes.length)await Promise.all(revokes);
      return {grantIds:ownedGrants,deviceIds:ownedDevices};
    },
    async status(grantId,ref) {
      const list=ref==null?accessibleDevices(grantId):[resolveDevice(grantId,ref)];
      return Promise.all(list.map(probe));
    },
    probe,
    // ---- grants ------------------------------------------------------------
    authorize,
    list(ownerId) {
      if(!OWNER_ID.test(ownerId||''))return [];
      return grantIds().flatMap(id=>{const row=readGrant(id);return row&&row.ownerId===ownerId?[{id,name:row.clientName,clientId:row.clientId,createdAt:row.createdAt,access:row.access,paused:!!row.paused,...activityOf(id)}]:[];});
    },
    ownerOf:grantId=>{try{return readGrant(grantId)?.ownerId||null;}catch{return null;}},
    /** A paused assistant keeps its access but every phone command is refused until resumed. */
    setPaused(ownerId,grantId,paused) {
      const row=readGrant(grantId);if(!row||row.ownerId!==ownerId)throw new ConnectionError('access_revoked','Connection not found');
      if(paused)row.paused=true;else delete row.paused;writeGrant(grantId,row);return !!row.paused;
    },
    /** End every screen an assistant has open, on its behalf. */
    async stopWork(ownerId,grantId) {
      const row=readGrant(grantId);if(!row||row.ownerId!==ownerId)throw new ConnectionError('access_revoked','Connection not found');
      const open=[...(work.get(grantId)?.sessions.values()||[])];let stopped=0;
      for(const session of open){try{const device=devices[session.deviceId]?{...publicDevice(session.deviceId,devices[session.deviceId])}:null;if(!device)continue;await callLink(device,'android.task_finish',{sessionId:session.sessionId,requestId:'stop-'+crypto.randomBytes(8).toString('hex')});stopped++;}catch{}work.get(grantId)?.sessions.delete(session.sessionId);}
      return stopped;
    },
    setAccess(ownerId,grantId,access) {
      const row=readGrant(grantId);if(!row||row.ownerId!==ownerId)throw new ConnectionError('access_revoked','Connection not found');
      row.access=normalizeAccess(ownerId,access);writeGrant(grantId,row);return row.access;
    },
    revokeOwned(ownerId,id) {
      if(!this.list(ownerId).some(row=>row.id===id))throw new ConnectionError('access_revoked','Connection not found');
      this.revoke(id);
    },
    active,
    revoke(id) {writePrivateJson(grantFile(id)+'.revoked',{revoked:true});fs.rmSync(grantFile(id),{force:true});},
    accessible:accessibleDevices,
    resolve:resolveDevice,
    // ---- enrolment ---------------------------------------------------------
    /**
     * Start a phone approval. With `deviceId`, the finished link replaces that device's
     * link (reconnect / replace). With `clientId`, the poll also creates that assistant's grant.
     */
    async begin({clientName='ChatGPT',ownerId,clientId,name,deviceId}={}) {
      if(ownerId&&!OWNER_ID.test(ownerId))throw new ConnectionError('invalid_request','Invalid owner');
      if(deviceId&&(!ownerId||!devices[deviceId]||devices[deviceId].ownerId!==ownerId))throw new ConnectionError('unknown_device','Device not found.');
      if(ownerId&&!deviceId){
        const limits=limitsFor(ownerId);
        if(ownerDevices(ownerId).length>=limits.maxDevices){
          const noun=limits.maxDevices===1?'1 Android device':`${limits.maxDevices} Android devices`;
          throw new ConnectionError('plan_limit',`Your ${limits.name} plan includes up to ${noun}. Remove a device or upgrade to Pro to add more.`);
        }
      }
      const label=deviceId?devices[deviceId].name:uniqueName(ownerId,name);
      const extraHeaders=relayInternalToken&&ownerId?{'x-backstage-internal-key':relayInternalToken,'x-backstage-owner':ownerId}:null;
      const pairing=await begin({relayUrl,transportRelayUrl:relayInternalUrl||null,extraHeaders,clientName:ownerId?'Android Backstage':clientName,name:label});
      const id='grant_'+crypto.randomBytes(16).toString('hex');
      let saved=false,resultId=id;
      return {clientName,code:pairing.code,link:pairing.link,expiresAt:pairing.expiresAt,cancel:pairing.cancel,deviceName:label,
        async poll() {
          const config=await pairing.poll();
          if(!config)return null;
          if(!saved){
            if(ownerId){
              const targetId=deviceId||'phone_'+crypto.randomBytes(16).toString('hex');
              if(deviceId&&!devices[deviceId])throw new ConnectionError('unknown_device','That device was removed while connecting.');
              if(!deviceId){
                const limits=limitsFor(ownerId);
                if(ownerDevices(ownerId).length>=limits.maxDevices){
                  const noun=limits.maxDevices===1?'1 Android device':`${limits.maxDevices} Android devices`;
                  throw new ConnectionError('plan_limit',`Your ${limits.name} plan includes up to ${noun}. Remove a device or upgrade to Pro to add more.`);
                }
              }
              writePhoneFile(targetId,config);
              probeCache.delete(targetId);devices[targetId]=deviceId?{...devices[deviceId],linkedAt:now(),needsReconnect:false,lastError:null}:{ownerId,name:uniqueName(ownerId,label),createdAt:now(),linkedAt:now(),needsReconnect:false};
              saveDevices();
              resultId=clientId?authorize(ownerId,clientId,clientName,'all'):targetId;
            } else writePrivateJson(grantFile(id),config);
            saved=true;
          }
          return resultId;
        }};
    },
    // ---- calls -------------------------------------------------------------
    /** Encrypt and send one tool call to one resolved device. Errors carry a `code`. */
    async call(name,args,grantId,{device}={}) {
      const target=device||resolveDevice(grantId);
      if(!active(grantId))throw new ConnectionError('access_revoked','This assistant’s access was removed.');
      if(readGrant(grantId)?.paused)throw new ConnectionError('assistant_paused','The owner paused this assistant in Backstage. Ask them to resume it.');
      const filePath=linkPath(target);
      let config;try {config=readLinkConfig(target);} catch {config=null;}
      if(!config)throw new ConnectionError('device_link_revoked',`"${target.name}" has no working link. Reconnect it at the Backstage account page.`);
      try {
        const directUrl=await directUrlFor(target,config);
        routes.set(target.id,directUrl?'direct':'relay');
        // Host environment credentials must never be resolved into a user's link.
        const result=await call({...config,...linkIoHooks(target),file:config,filePath,directUrl:null,transportRelayUrl:transportFor(config)},name,args,{
          authorize:()=>{if(!active(grantId))throw new ConnectionError('access_revoked','This assistant’s access was removed.');},
          directUrl,
          onRoute:(route,why)=>{routes.set(target.id,route==='relay'&&directUrl?'relay_after_direct':route);if(route==='relay'&&directUrl){directRoutes?.failed(target.id);log(`${target.name}: ${name} fell back to the relay (${why})`);}}});
        if(target.id&&devices[target.id]?.needsReconnect)markLink(target.id,{needsReconnect:false,lastError:null});
        recordWork(grantId,target.id,name,args,result);
        return result;
      } catch(e) {
        if(e instanceof ConnectionError)throw e;
        throw classifyCallError(e,target,id=>markLink(id,{needsReconnect:true,lastError:'The phone removed this link.'}));
      } finally {
        if(fs.existsSync(grantFile(grantId)+'.revoked'))fs.rmSync(grantFile(grantId),{force:true});
      }
    },
    directUrl:directUrlFor,
    routeOf:deviceId=>routes.get(deviceId)||null,
    /** Relay address this computer should use for a link's uploads and job reads. */
    transportFor,
    /** Read one job's outcome by ID through the device's link, for uncertain writes. */
    async job(device,jobId) {
      if(!/^[A-Za-z0-9_-]{16,100}$/.test(jobId||''))throw new ConnectionError('invalid_request','Job ID required.');
      const config=readLinkConfig(device);if(!config)throw new ConnectionError('device_link_revoked',`"${device.name}" has no working link.`);
      const response=await fetchImpl(relayBase(config)+'/v1/jobs/'+jobId,{headers:{Authorization:'Bearer '+config.apiKey},signal:AbortSignal.timeout(15000)});
      const body=await response.json().catch(()=>({}));
      if(response.status===401){markLink(device.id,{needsReconnect:true,lastError:'The phone removed this link.'});throw new ConnectionError('device_link_revoked',`"${device.name}" removed this link. Reconnect it at the Backstage account page.`);}
      if(response.status===404)throw new ConnectionError('job_not_found','No saved result is available for this job. Inspect the phone state before starting another write.',{jobId,inputDelivered:null});
      if(!response.ok)throw new ConnectionError('tool_failed',body.error||`HTTP ${response.status}`);
      const {envelope,...rest}=body;
      if(envelope){
        try {
          const {openEnvelope,resultKey,unb64url}=await import('../cli/crypto.mjs');
          const link=config.links[config.device];
          const decoded=JSON.parse(openEnvelope({key:resultKey(unb64url(link.linkKey)),envelope}));
          if(decoded.id===jobId&&envelope.from===link.deviceId)rest.recorded=decoded.error?{error:decoded.error}:{result:decoded.result};
        } catch {rest.recorded=null;}
      }
      return rest;
    },
    linkConfig(device) {return readLinkConfig(device);}
  };
}

/** Turn transport failures into distinct, actionable codes. */
export function classifyCallError(e,device,markRevoked=()=>{}) {
  const text=String(e?.message||e),jobId=e?.jobId||/job (call-[a-f0-9]+)/.exec(text)?.[1]||null;
  const name=device?.name||'the device';
  if(e?.inputDelivered===false&&e?.code==='relay_busy')return new ConnectionError('relay_busy',`The relay is busy. No command was sent to "${name}". Try again shortly.`,{inputDelivered:false});
  // The phone's own report of a failed download ("curl: (28) Connection timed out"), not a lost reply.
  if(/curl: \(\d+\)/.test(text))return new ConnectionError('tool_failed',text.replace(/^job call-[a-f0-9]+: /,'').replace(/\. Query job status before retrying an action$/,''),{jobId});
  if(e?.inputDelivered!==false&&/cancellation requested|Query its status|timed out|aborted|TimeoutError/i.test(text))
    return new ConnectionError('uncertain',`No result from "${name}" in time. The command may still have run. Check with android_job_status before repeating a write.`,{jobId});
  if(/is offline|last seen/i.test(text))return new ConnectionError('device_offline',`"${name}" is offline. Open Android Backstage on that phone and tap Start. Not using another device.`,{jobId});
  if(/paused|permit expired|access is paused|resume access/i.test(text))return new ConnectionError('device_paused',`Access on "${name}" is paused. Ask the user to resume access in Android Backstage on that phone.`,{jobId});
  if(/unauthorized|cannot access that phone|wrong connection identity/i.test(text)){if(device?.id&&!device.legacy)markRevoked(device.id);return new ConnectionError('device_link_revoked',`"${name}" no longer accepts this link (the app was reset or the link was removed on the phone). Reconnect it at the Backstage account page.`,{jobId});}
  if(/no longer matches|result from mismatch/i.test(text))return new ConnectionError('device_link_revoked',`"${name}" is not the phone that was enrolled. Reconnect or replace it at the Backstage account page.`,{jobId});
  if(e?.inputDelivered===false)return new ConnectionError('submission_rejected',text,{inputDelivered:false});
  return new ConnectionError('tool_failed',text.replace(/^job call-[a-f0-9]+: /,'').replace(/\. Query job status before retrying an action$/,''),{jobId});
}
