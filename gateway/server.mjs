import express from 'express';
import {createHash, randomBytes, timingSafeEqual} from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { CallToolRequestSchema, ListToolsRequestSchema } from '@modelcontextprotocol/sdk/types.js';
import Ajv from 'ajv';
import {advertisedSchema} from '../cli/advertised-schema.mjs';
import {invalidArguments} from './validation.mjs';
import { createConnections, ConnectionError } from './connections.mjs';
import { createArtifacts, shredFile } from './artifacts.mjs';
import { createR2FromEnv } from '../relay/r2.mjs';
import { loadVaultFromEnv } from './vault.mjs';
import { createDirectRoutes, overTailnet } from './direct-routes.mjs';
import {createRequestJournal} from './request-journal.mjs';
import {jsonKeepAlive} from './json-keepalive.mjs';
import { createAuth } from './auth.mjs';
import {createOwners} from './owners.mjs';
import {planLimits} from './plans.mjs';
import {createMailer} from './mail.mjs';
import {createAppReleases} from './app-releases.mjs';
import {ownerRoutes} from './owner-routes.mjs';
import {phoneRoutes} from './phone-api.mjs';
import {nativeSignInRoutes,androidOrigins} from './native-sign-in.mjs';
import { mcpTools, toolAnnotations, asToolResult, VERSION, INSTRUCTIONS as PHONE_INSTRUCTIONS } from '../cli/tools.mjs';
import { encryptAndUpload } from '../cli/backstage.mjs';

export const uiTools=new Set(['observe','open','tap','type','key','gesture','scroll','scroll_until','wait','sequence','task','task_begin','task_inspect','task_finish','task_share','task_list','handoff','record','confirm'].map(n=>'android.'+n));
const DEVICE_PROP={device:{type:'string'}};
const schema=(properties,required=[],extra={})=>({type:'object',properties:{...DEVICE_PROP,...properties},required,additionalProperties:false,...extra});
const requestId={type:'string',pattern:'^[A-Za-z0-9_-]{1,128}$'};
const gatewayTools=[
 {name:'android.devices',description:'List linked devices (detail:debug reads battery/network).',annotations:toolAnnotations('android.devices',true),inputSchema:schema({detail:{enum:['compact','debug']}})},
 {name:'android.job_status',description:'Check an earlier call outcome by jobId.',annotations:toolAnnotations('android.job_status',true),inputSchema:schema({jobId:{type:'string'}},['jobId'])},
 {name:'android.artifact',description:'Manage uploads: begin (name,size,sha256), status (artifactId), delete (artifactId).',annotations:toolAnnotations('android.artifact',false),inputSchema:schema({
  operation:{type:'string',enum:['begin','status','delete']},
  name:{type:'string',minLength:1},size:{type:'integer',minimum:1},sha256:{type:'string',minLength:1},
  artifactId:{type:'string',minLength:1},requestId
 },['operation'],{allOf:[{oneOf:[
  {properties:{operation:{const:'begin'}},required:['name','size','sha256','requestId'],not:{required:['artifactId']}},
  {properties:{operation:{const:'status'}},required:['artifactId'],not:{anyOf:['name','size','sha256','requestId'].map(k=>({required:[k]}))}},
  {properties:{operation:{const:'delete'},artifactId:{pattern:'^art_[a-f0-9]{24}$'}},required:['artifactId','requestId'],not:{anyOf:['name','size','sha256'].map(k=>({required:[k]}))}}
 ]}]})},
 {name:'android.file',description:'Files: pull (path; auto-returns single downloadUrl for >1 MiB or download:true), push (artifactId,remote), fetch (url,remote).',annotations:toolAnnotations('android.file',false),inputSchema:schema({
  operation:{type:'string',enum:['pull','push','fetch']},
  path:{type:'string',minLength:1},offset:{type:'integer',minimum:0,maximum:9007199254740991},length:{type:'integer',minimum:1,maximum:1048576},fileId:{type:'string',pattern:'^file_[a-f0-9]{64}$'},download:{type:'boolean'},
  artifactId:{type:'string',minLength:1},remote:{type:'string',minLength:1},
  url:{type:'string',minLength:1},fileKey:{type:'string',minLength:1},sha256:{type:'string',minLength:1},requestId
 },['operation'],{allOf:[{oneOf:[
  {properties:{operation:{const:'pull'}},required:['path'],not:{anyOf:['artifactId','remote','url','fileKey','sha256','requestId'].map(k=>({required:[k]}))}},
  {properties:{operation:{const:'push'}},required:['artifactId','remote','requestId'],not:{anyOf:['path','offset','length','fileId','download','url','fileKey','sha256'].map(k=>({required:[k]}))}},
  {properties:{operation:{const:'fetch'}},required:['url','remote','requestId'],not:{anyOf:['path','offset','length','fileId','download','artifactId'].map(k=>({required:[k]}))}}
 ]}]})},
 {name:'android.install',description:'Install APK from source {artifactId} or {url,sha256?}.',annotations:toolAnnotations('android.install',false),inputSchema:schema({requestId,source:{oneOf:[{type:'object',properties:{artifactId:{type:'string'}},required:['artifactId'],additionalProperties:false},{type:'object',properties:{url:{type:'string',pattern:'^https?://[^\\s]+$'},sha256:{type:'string'}},required:['url'],additionalProperties:false}]}},['source','requestId'])}
];
const replaced=new Set(gatewayTools.map(t=>t.name));
const allStrictCatalogue=[...mcpTools({strict:true}).filter(t=>!replaced.has(t.name)).map(t=>({...t,inputSchema:{...t.inputSchema,properties:{...DEVICE_PROP,...t.inputSchema.properties}}})),...gatewayTools].map(t=>({...t,name:t.name.replaceAll('.','_'),description:t.description.replace(/\bandroid\.([a-z_]+)\b/g,'android_$1'),annotations:t.annotations||toolAnnotations(t.name),securitySchemes:[{type:'oauth2',scopes:['phone:control']}],_meta:{securitySchemes:[{type:'oauth2',scopes:['phone:control']}]}}));
// Raw shell is exposed on hosted MCP and enforced on-device via the phone's Allow shell commands toggle (off by default).
export const strictCatalogue=allStrictCatalogue;
export const catalogue=strictCatalogue.map(t=>({...t,inputSchema:advertisedSchema(t.inputSchema,t.inputSchema,{toolName:t.name})}));
export const TOOL_CONTRACT={version:VERSION,sha256:createHash('sha256').update(JSON.stringify(catalogue)).digest('hex')};
const ajv=new Ajv({strict:false,allErrors:true});
const validators=new Map(allStrictCatalogue.map(t=>[t.name,ajv.compile(t.inputSchema)]));
export const SERVER_INSTRUCTIONS=PHONE_INSTRUCTIONS.replace(/\bandroid\./g,'android_')+'\nSafety: Always call android_confirm before irreversible actions (sending messages, posting, paying, or deleting).\nUploads: android_artifact begin -> android_install / android_file push -> android_artifact delete.';

/** Accept legacy pre-consolidation tool names seamlessly without advertising them in tools/list. */
export function normalizeToolCall(name, rawArgs = {}) {
  const args = rawArgs && typeof rawArgs === 'object' && !Array.isArray(rawArgs) ? { ...rawArgs } : rawArgs;
  const legacyTask = { android_task_begin: 'begin', android_task_inspect: 'inspect', android_task_list: 'list', android_task_finish: 'finish', android_task_share: 'share', android_handoff: 'handoff' };
  if (Object.hasOwn(legacyTask, name)) return { name: 'android_task', args: { operation: legacyTask[name], ...args } };
  if (name === 'android_record') {
    const { operation: sub, ...rest } = args || {};
    return { name: 'android_task', args: { ...(sub !== undefined ? { operation: 'record_' + sub } : {}), ...rest } };
  }
  const legacyFile = { android_pull: 'pull', android_push: 'push', android_fetch: 'fetch' };
  if (Object.hasOwn(legacyFile, name)) return { name: 'android_file', args: { operation: legacyFile[name], ...args } };
  const legacyArtifact = { android_artifact_begin: 'begin', android_artifact_status: 'status', android_artifact_delete: 'delete' };
  if (Object.hasOwn(legacyArtifact, name)) return { name: 'android_artifact', args: { operation: legacyArtifact[name], ...args } };
  if (name === 'android_scroll_until') return { name: 'android_scroll', args };
  if (name === 'android_clipboard') return { name: 'android_device', args: { operation: 'clipboard', ...args } };
  if (name === 'android_media') return { name: 'android_device', args: { operation: 'media', ...args } };
  if (name === 'android_location') return { name: 'android_device', args: { operation: 'location', ...args } };
  if (name === 'android_device_control') {
    const { operation: sub, ...rest } = args || {};
    return { name: 'android_device', args: { operation: 'control', ...(sub !== undefined ? { action: sub } : {}), ...rest } };
  }
  if (name === 'android_audio') {
    const { operation: sub, ...rest } = args || {};
    return { name: 'android_device', args: { operation: 'audio', ...(sub !== undefined ? { action: sub } : {}), ...rest } };
  }
  if (name === 'android_sequence' && Array.isArray(args?.steps)) {
    args.steps = args.steps.map(step => {
      if (step && typeof step === 'object' && !Array.isArray(step) && typeof step.tool === 'string' && /^android_[a-z_]+$/.test(step.tool)) {
        return { ...step, tool: 'android.' + step.tool.slice(8) };
      }
      return step;
    });
  }
  return { name, args };
}

/** Translate consolidated MCP tool calls to phone wire names so every 2.x phone (old and new) executes them seamlessly. */
function toPhoneWire(engine, rest) {
  if (engine === 'android.task') {
    const { operation, ...args } = rest;
    if (operation === 'handoff') return ['android.handoff', args];
    if (operation?.startsWith('record_')) return ['android.record', { operation: operation.slice(7), ...args }];
    return ['android.task_' + operation, args];
  }
  if (engine === 'android.file') {
    const { operation, download, ...args } = rest;
    return ['android.' + operation, args];
  }
  if (engine === 'android.scroll' && rest.until) {
    return ['android.scroll_until', rest];
  }
  if (engine === 'android.device') {
    const { operation, action, ...args } = rest;
    if (operation === 'control') return ['android.device_control', { operation: action, ...args }];
    if (operation === 'audio') return ['android.audio', { operation: action, ...args }];
    if (operation === 'media') return ['android.media', { action, ...args }];
    return ['android.' + operation, args];
  }
  return [engine, rest];
}

const failure=(e)=>{
  if(e instanceof ConnectionError){const {code,message,jobId,devices,heldBy,expiresAt,offset,size}=e;return asToolResult({ok:false,code,error:message,...(jobId?{jobId}:{}),...(devices?{devices}:{}),...(heldBy?{heldBy}:{}),...(expiresAt?{expiresAt}:{}),...(offset!=null?{offset}:{}),...(size!=null?{size}:{})},true);}
  return asToolResult({ok:false,code:'tool_failed',error:'The phone action did not finish. The phone may be offline, paused, or this connection may no longer have access. Do not retry a write unless its outcome is known.'},true);
};

export function createWorkApp({base,stateFile,approvalFile,connections,owners,artifacts,metadataReader,callTool,directFilesUrl=null,keepAlive,allowTestRedirect=false,zeroRetention=false,allowShell=process.env.BACKSTAGE_ALLOW_HOSTED_SHELL!=='0',enforcePlans=process.env.BACKSTAGE_ENFORCE_PLANS!=='0',internalToken=process.env.RELAY_INTERNAL_TOKEN||process.env.RELAY_ADMIN_TOKEN||null,audit=()=>{},now=Date.now}) {
  if(enforcePlans&&connections&&owners&&typeof owners.planOf==='function')connections.setPlanResolver?.(ownerId=>owners.planOf(ownerId));
  const dailyUsage=new Map();
  const ownerLimits=grantId=>{
    if(!enforcePlans||!connections||!owners||typeof owners.planOf!=='function')return null;
    const ownerId=connections.ownerOf?.(grantId);
    if(!ownerId)return null;
    return {ownerId,limits:planLimits(owners.planOf(ownerId))};
  };
  const consumeDailyAction=(ownerId,limits)=>{
    const day=new Date(now()).toISOString().slice(0,10);
    let row=dailyUsage.get(ownerId);
    if(!row||row.day!==day){
      if(!dailyUsage.has(ownerId)&&dailyUsage.size>=10000){
        for(const [k,v] of dailyUsage)if(v.day!==day)dailyUsage.delete(k);
        if(dailyUsage.size>=10000){const oldest=dailyUsage.keys().next().value;if(oldest!==undefined)dailyUsage.delete(oldest);}
      }
      row={day,count:0};
      dailyUsage.set(ownerId,row);
    }
    if(row.count>=limits.dailyActions){
      throw new ConnectionError('plan_limit',`Daily action limit reached for the ${limits.name} plan (${limits.dailyActions}/day). Wait until tomorrow or upgrade to Pro.`);
    }
    row.count++;
  };
  if(zeroRetention&&stateFile)fs.rmSync(stateFile+'.requests',{recursive:true,force:true});
  const journal=createRequestJournal({directory:stateFile+'.requests',memoryOnly:zeroRetention,now,reconcile:async(grantId,name,args,jobId)=>{
    if(!connections?.active(grantId))return null;
    const device=connections.resolve(grantId,args.device),job=await connections.job(device,jobId);
    if(job.state==='complete'&&job.recorded?.result)return {...job.recorded.result,device:{id:device.id,name:device.name}};
    if(['cancelled','expired'].includes(job.state))return {outcome:'failed',inputDelivered:false,code:'job_not_executed',jobId,error:'The queued command did not run. Start a new request if it is still needed.'};
    return null;
  }});
  const app = express(); app.disable('x-powered-by');
  const trustProxy = process.env.BACKSTAGE_TRUST_PROXY || 'loopback';
  app.set('trust proxy', trustProxy.includes(',') ? trustProxy.split(',').map(s=>s.trim()).filter(Boolean) : trustProxy);
  const auth = createAuth({base,stateFile,approvalFile,connections,owners,metadataReader,allowTestRedirect,now});
  // Artifact bytes stream straight to disk; they must not meet the JSON body parser.
  app.put('/work/artifacts/:id',async(req,res)=>{
    if(!artifacts)return res.status(404).json({error:'artifacts_unavailable'});
    const bearer=/^Bearer ([A-Za-z0-9_-]+)$/.exec(req.headers.authorization||'')?.[1]||null;
    const grantId=bearer?auth.grantForToken(bearer):null;
    const offset=req.headers['x-upload-offset']==null?0:Number(req.headers['x-upload-offset']);
    if(!Number.isSafeInteger(offset)||offset<0)return res.status(400).json({error:'invalid_request',message:'X-Upload-Offset must be a byte offset.'});
    try {res.json(await artifacts.append(req.params.id,{uploadToken:bearer,grantId},offset,req));}
    catch(e){if(e instanceof ConnectionError)res.status({unknown_artifact:404,offset_mismatch:409,busy:409,integrity_mismatch:422}[e.code]||400).json({error:e.code,message:e.message,...(e.offset!=null?{offset:e.offset}:{})});else res.status(500).json({error:'upload_failed'});}
  });
  app.get('/work/artifacts/:id',(req,res)=>{
    if(!artifacts)return res.status(404).json({error:'artifacts_unavailable'});
    const bearer=/^Bearer ([A-Za-z0-9_-]+)$/.exec(req.headers.authorization||'')?.[1]||null;
    try {res.json(artifacts.status(req.params.id,{uploadToken:bearer,grantId:auth.grantForToken(bearer)}));}
    catch(e){res.status(e.code==='unknown_artifact'?404:400).json({error:e.code||'invalid_request',message:e.message});}
  });
  app.get('/work/artifacts/:id/download',(req,res)=>{
    if(!artifacts)return res.status(404).json({error:'artifacts_unavailable'});
    const bearer=/^Bearer ([A-Za-z0-9_-]+)$/.exec(req.headers.authorization||'')?.[1]||null;
    try {
      const file=artifacts.download(req.params.id,{uploadToken:bearer,grantId:auth.grantForToken(bearer)});
      if(file.r2Url){
        res.set({'X-Content-Sha256':file.sha256,'Cache-Control':'no-store'});
        if(file.singleUse&&file.consume)try{file.consume();}catch{}
        return res.redirect(302,file.r2Url);
      }
      res.set({'Content-Type':'application/octet-stream','Content-Disposition':`attachment; filename="${file.name}"`,'Content-Length':String(file.size),'X-Content-Sha256':file.sha256,'Cache-Control':'no-store'});
      const stream=fs.createReadStream(file.path);
      if(file.singleUse&&file.consume)stream.once('close',()=>{try{file.consume();}catch{}});
      stream.pipe(res);
    } catch(e){res.status(e.code==='unknown_artifact'?404:400).json({error:e.code||'invalid_request',message:e.message});}
  });
  app.use(express.json({limit:'128kb'})); app.use(express.urlencoded({extended:false,limit:'8kb'}));
  auth.routes(app);
  const nativeSignIn = (connections&&owners) ? nativeSignInRoutes(app,{connections,owners,now}) : null;
  const phoneApi = (connections&&owners) ? phoneRoutes(app,{connections,auth,owners,now,base,artifacts,nativeSignIn,appReleases:createAppReleases()}) : null;
  if(owners)ownerRoutes(app,{base,connections,owners,auth,artifacts,nativeSignIn,phoneApi,now});

  // Loopback-only internal admin routes (blocked by gateway/front.mjs externally; requires x-backstage-internal-key).
  if (owners && internalToken) {
    const expectedHash = createHash('sha256').update(String(internalToken)).digest();
    const isLoopback = addr => addr === '127.0.0.1' || addr === '::1' || addr === '::ffff:127.0.0.1';
    const requireInternalAdmin = (req, res, next) => {
      if (!isLoopback(req.socket?.remoteAddress || '')) return res.status(403).json({ error: 'loopback_required' });
      const given = String(req.headers['x-backstage-internal-key'] || '');
      if (!given) return res.status(401).json({ error: 'unauthorized' });
      const givenHash = createHash('sha256').update(given).digest();
      if (!timingSafeEqual(givenHash, expectedHash)) return res.status(401).json({ error: 'unauthorized' });
      next();
    };
    const resolveOwnerId = ref => {
      const raw = String(ref || '').trim();
      if (!raw) return null;
      if (owners.exists(raw)) return raw;
      return owners.ownerByEmail?.(raw) || null;
    };
    app.get('/work/internal/admin/owners', requireInternalAdmin, (_req, res) => {
      const list = (owners.listOwners?.() || []).map(o => ({
        ...o,
        deviceCount: connections ? connections.devices(o.id).length : 0,
        grantCount: connections ? connections.list(o.id).length : 0,
      }));
      res.json({ owners: list });
    });
    app.get('/work/internal/admin/owner/:ref', requireInternalAdmin, (req, res) => {
      const ownerId = resolveOwnerId(req.params.ref);
      if (!ownerId) return res.status(404).json({ error: 'owner_not_found' });
      const methods = owners.methods(ownerId);
      const devices = connections ? connections.devices(ownerId).map(d => ({ id: d.id, name: d.name, model: d.model || null, lastSeen: d.lastSeen || null })) : [];
      const grants = connections ? connections.list(ownerId).map(g => ({ id: g.id, name: g.name || null, access: g.access, paused: !!g.paused, createdAt: g.createdAt })) : [];
      res.json({ ownerId, methods, devices, grants });
    });
    app.post('/work/internal/admin/owner/:ref/plan', requireInternalAdmin, (req, res) => {
      const ownerId = resolveOwnerId(req.params.ref);
      if (!ownerId) return res.status(404).json({ error: 'owner_not_found' });
      try {
        const updated = owners.setPlan(ownerId, req.body?.plan);
        res.json({ ok: true, ...updated });
      } catch (e) {
        res.status(400).json({ error: e.message });
      }
    });
    app.post('/work/internal/admin/owner/:ref/revoke-grants', requireInternalAdmin, (req, res) => {
      const ownerId = resolveOwnerId(req.params.ref);
      if (!ownerId) return res.status(404).json({ error: 'owner_not_found' });
      const grants = connections ? connections.list(ownerId).map(g => g.id) : [];
      for (const gid of grants) { try { connections.revokeOwned(ownerId, gid); } catch {} }
      auth?.revokeGrants?.(grants);
      artifacts?.removeForGrants?.(grants);
      auth?.purgeOwner?.(ownerId);
      res.json({ ok: true, ownerId, revokedGrants: grants });
    });
    app.post('/work/internal/admin/owner/:ref/delete', requireInternalAdmin, async (req, res) => {
      const ownerId = resolveOwnerId(req.params.ref);
      if (!ownerId) return res.status(404).json({ error: 'owner_not_found' });
      auth?.purgeOwner?.(ownerId);
      await nativeSignIn?.purgeOwner?.(ownerId);
      await phoneApi?.purgeOwner?.(ownerId);
      const removed = connections ? await connections.removeAllForOwner(ownerId) : { grantIds: [], deviceIds: [] };
      auth?.revokeGrants?.(removed.grantIds);
      artifacts?.removeForGrants?.(removed.grantIds);
      owners.deleteOwner(ownerId);
      res.json({ ok: true, deleted: true, ownerId, removedDevices: removed.deviceIds, revokedGrants: removed.grantIds });
    });
  }
  app.get('/work/health', (req,res) => res.json({ok:true,service:'backstage-work',version:VERSION,toolContract:TOOL_CONTRACT}));
  app.options('/work/mcp',(req,res)=>{
    if(!req.headers.origin||!auth.registeredOrigin(req.headers.origin))return res.sendStatus(403);
    res.vary('Origin');res.set({'Access-Control-Allow-Origin':req.headers.origin,'Access-Control-Allow-Methods':'POST','Access-Control-Allow-Headers':'Authorization, Content-Type, Accept, MCP-Protocol-Version, MCP-Session-Id','Access-Control-Max-Age':'600'});res.sendStatus(204);
  });

  /** Route one validated tool call to one device, holding it for UI work. */
  async function dispatch(name,args,grantId,clientName) {
    const engine=name.replace('android_','android.');
    const {device:ref,...rest}=args;
    if(!connections)return callTool(engine,rest,grantId,{});
    if(engine==='android.devices'&&rest.detail!=='debug')return {outcome:'succeeded',devices:(await connections.status(grantId)).map(({id,name,status,online,needsReconnect,model,lastSeen,lastError})=>({id,name,status,online,needsReconnect,model:model||null,...(lastSeen?{lastSeen}:{}),...(lastError?{lastError}:{})}))};
    const planInfo=ownerLimits(grantId);
    if(planInfo){
      const {ownerId,limits}=planInfo;
      if(engine!=='android.job_status')consumeDailyAction(ownerId,limits);
      if(engine==='android.task'&&rest.operation==='begin'&&!rest.join&&rest.mode!=='foreground'){
        const activeCount=connections.activeSessionsForOwner?.(ownerId)||0;
        if(activeCount>=limits.maxParallelScreens){
          const noun=limits.maxParallelScreens===1?'1 active backstage screen':`${limits.maxParallelScreens} active backstage screens`;
          throw new ConnectionError('plan_limit',`Your ${limits.name} plan includes ${noun} at a time. Finish the active screen first, or upgrade to Pro for parallel backstage screens.`);
        }
      }
      if(engine==='android.task'&&rest.operation==='record_start'&&!limits.screenRecording){
        throw new ConnectionError('plan_limit','Screen recording is included with Pro and Team plans.');
      }
      if(engine==='android.events'&&['webhook_set','set_rules'].includes(rest.operation)&&!limits.webhooks){
        throw new ConnectionError('plan_limit','Background triggers and webhooks are included with Pro and Team plans.');
      }
      if(engine==='android.artifact'&&rest.operation==='begin'&&rest.size>limits.maxFileBytes){
        throw new ConnectionError('plan_limit',`Files over ${Math.round(limits.maxFileBytes/(1024*1024))} MB require a Pro or Team plan.`);
      }
    }
    if(engine==='android.artifact'){
      if(!artifacts)throw new ConnectionError('tool_failed','File artifacts are unavailable.');
      if(rest.operation==='begin'){const {operation,...input}=rest;const row=artifacts.begin({grantId,...input});return {outcome:'succeeded',...row,uploadUrl:base+'/artifacts/'+row.artifactId,method:'PUT',maxRequestBytes:90*1024*1024};}
      if(rest.operation==='status')return {outcome:'succeeded',...artifacts.status(rest.artifactId,grantId)};
      if(rest.operation==='delete')return {outcome:'succeeded',...artifacts.remove(rest.artifactId,grantId)};
    }
    const device=connections.resolve(grantId,ref);
    if(engine==='android.job_status')return {outcome:'succeeded',...(await connections.job(device,rest.jobId))};
    if(engine==='android.events'&&['list','webhook_set','webhook_clear'].includes(rest.operation)){
      const link=connections.linkConfig(device);
      if(!link)throw new ConnectionError('device_link_revoked','Reconnect this device in Backstage.');
      const relayBase=(connections.transportFor?.(link)||link.relayUrl).replace(/\/$/,'');
      const hdrs={authorization:'Bearer '+link.apiKey,'content-type':'application/json'};
      let url,method='GET',body;
      if(rest.operation==='list'){
        const q=new URLSearchParams({device:device.id});
        if(rest.since!=null)q.set('since',String(rest.since));
        if(rest.limit!=null)q.set('limit',String(rest.limit));
        url=`${relayBase}/v1/events?${q}`;
      }else if(rest.operation==='webhook_set'){
        url=`${relayBase}/v1/events/webhooks`;method='POST';body=JSON.stringify({url:rest.url,secret:rest.secret||''});
      }else{
        url=`${relayBase}/v1/events/webhooks`;method='DELETE';
      }
      const r=await fetch(url,{method,headers:hdrs,body,signal:AbortSignal.timeout(10000)});
      const data=await r.json().catch(()=>({error:'invalid relay reply'}));
      if(!r.ok)throw new ConnectionError('events_failed',data.error||`HTTP ${r.status}`);
      return {outcome:'succeeded',...data,device:{id:device.id,name:device.name}};
    }
    const send=async(tool,input)=>({...await (callTool?callTool(tool,input,grantId,{device}):connections.call(tool,input,grantId,{device})),device:{id:device.id,name:device.name}});
    // Single-call large file pull: automatically assemble multi-chunk files (>1 MiB or download:true) into a verified download URL.
    if(engine==='android.file'&&rest.operation==='pull'&&rest.offset==null&&artifacts){
      const first=await send('android.pull',{path:rest.path,offset:0,length:rest.length||1048576});
      if(first.outcome!=='succeeded'||(first.eof&&!rest.download))return first;
      const tmpDir=artifacts.tempDir||os.tmpdir();
      const tmp=path.join(tmpDir,`backstage-pull-${randomBytes(12).toString('hex')}.bin`);
      let fd=fs.openSync(tmp,'wx',0o600);
      const hash=createHash('sha256');
      let offset=0;
      const maxPullBytes=planInfo?Math.min(artifacts.maxBytes||128*1024*1024,planInfo.limits.maxFileBytes):(artifacts.maxBytes||128*1024*1024);
      const fileId=first.fileId,totalBytes=first.totalBytes;
      let saved;
      try {
        let row=first;
        for(;;){
          if(row.outcome!=='succeeded')throw new ConnectionError('pull_failed',row.error||'File pull failed mid-transfer.');
          if(fileId&&row.fileId&&row.fileId!==fileId)throw new ConnectionError('file_changed','The file changed during transfer. Retry pull.');
          const buf=Buffer.from(typeof row.dataBase64==='string'?row.dataBase64:'','base64');
          if(row.sha256&&createHash('sha256').update(buf).digest('hex')!==row.sha256)throw new ConnectionError('integrity_mismatch','Pulled chunk failed SHA-256 check.');
          if(offset+buf.length>maxPullBytes){
            if(planInfo&&maxPullBytes===planInfo.limits.maxFileBytes&&planInfo.limits.maxFileBytes<(artifacts.maxBytes||128*1024*1024)){
              throw new ConnectionError('plan_limit',`Files over ${Math.round(planInfo.limits.maxFileBytes/(1024*1024))} MB require a Pro or Team plan.`);
            }
            throw new ConnectionError('invalid_request',`File exceeds maximum transfer size (${maxPullBytes} bytes).`);
          }
          if(buf.length){fs.writeSync(fd,buf,0,buf.length,offset);hash.update(buf);offset+=buf.length;}
          if(row.eof||!buf.length)break;
          row=await send('android.pull',{path:rest.path,offset,length:1048576,...(fileId?{fileId}:{})});
        }
        fs.fsyncSync(fd);
        fs.closeSync(fd);fd=-1;
        const sha256=hash.digest('hex');
        let r2Key=null,r2Url=null;
        if(artifacts.r2&&offset>0){
          try {
            const uploaded=await artifacts.r2.uploadFile(tmp,{ttlSec:Math.round((artifacts.ttlMs||300_000)/1000),contentType:'application/octet-stream'});
            r2Key=uploaded.key;r2Url=uploaded.url;
          } catch {}
        }
        saved=artifacts.createFromFile({grantId,name:path.basename(rest.path),tempPath:tmp,size:offset,sha256,r2Key,r2Url});
      } catch(e){
        if(fd>=0){try{fs.closeSync(fd);}catch{}}
        shredFile(tmp,zeroRetention||artifacts.zeroRetention);
        throw e;
      }
      return {outcome:'succeeded',path:rest.path,bytes:offset,totalBytes:totalBytes??offset,sha256:saved.sha256,fileId,artifactId:saved.artifactId,downloadUrl:base+'/artifacts/'+saved.artifactId+'/download',downloadToken:saved.downloadToken,expiresAt:saved.expiresAt,device:{id:device.id,name:device.name}};
    }
    if(engine==='android.install'&&rest.source?.artifactId||engine==='android.file'&&rest.operation==='push'){
      return artifacts.use(rest.source?.artifactId||rest.artifactId,grantId,async artifact=>{
      const link=connections.linkConfig(device);
      if(!link)throw new ConnectionError('device_link_revoked','Reconnect this device in Backstage.');
      let uploaded;
      try {uploaded=await encryptAndUpload({relayUrl:link.relayUrl,apiKey:link.apiKey,transportRelayUrl:connections.transportFor?.(link)||undefined},artifact.path);}
      catch(e){audit({time:new Date().toISOString(),tool:name,phase:'prepare_upload',error:String(e.message||e.name).replace(/https?:\/\/\S+/g,'[address]').slice(0,200)});throw new ConnectionError('transfer_prepare_failed','The file could not be prepared for transfer. No command was sent to the phone. Try the transfer again.');}
      if(uploaded.sha256!==artifact.sha256)throw new ConnectionError('integrity_mismatch','The transferred file does not match the verified artifact.');
      const source={url:uploaded.url,fileKey:uploaded.fileKey,sha256:uploaded.sha256};
      const deliver=(src,requestId)=>engine==='android.file'?send('android.fetch',{...src,remote:rest.remote,requestId}):send('android.install',{source:src,requestId});
      const direct=directFilesUrl&&connections.directUrl?await connections.directUrl(device,link):null;
      if(direct&&overTailnet(direct)){
        const nearby={...source,url:directFilesUrl.replace(/\/$/,'')+new URL(uploaded.url).pathname};
        try {const result=await deliver(nearby,rest.requestId);audit({time:new Date().toISOString(),tool:name,phase:'transfer',route:'direct'});return result;}
        catch(e){
          if(!(e instanceof ConnectionError)||e.code!=='tool_failed'||!/curl: \(\d+\)|download/i.test(e.message))throw e;
          audit({time:new Date().toISOString(),tool:name,phase:'transfer',route:'relay_after_direct',error:String(e.message).slice(0,160)});
          return deliver(source,rest.requestId.slice(0,120)+'-relay');
        }
      }
      return deliver(source,rest.requestId);
      });
    }
    const [wireTool,wireArgs]=toPhoneWire(engine,rest);
    const result=await send(wireTool,wireArgs);
    return result;
  }

  app.all('/work/mcp',auth.requireToken,async (req,res) => {
    if (req.headers.origin && !auth.originAllowed(req.headers.origin,req.backstageClient)) return res.sendStatus(403);
    if(req.headers.origin){res.set('Access-Control-Allow-Origin',req.headers.origin);res.vary('Origin');}
    if (req.method !== 'POST') {res.set('Allow','POST');return res.sendStatus(405);}
    const activeCatalogue = allowShell ? catalogue : catalogue.filter(t=>t.name!=='android_shell');
    const server = new Server({name:'android-backstage-work',version:VERSION},{capabilities:{tools:{}},instructions:SERVER_INSTRUCTIONS});
    server.setRequestHandler(ListToolsRequestSchema,async () => ({tools:activeCatalogue}));
    server.setRequestHandler(CallToolRequestSchema,async request => {
      const normalized = normalizeToolCall(request.params.name, request.params.arguments || {});
      const name = normalized.name;
      if (name === 'android_shell' && !allowShell) return asToolResult({outcome:'failed',code:'tool_disabled',inputDelivered:false,error:'Shell commands are disabled on this server. Use the structured tools instead.'},true);
      const validate = validators.get(name);
      let args = normalized.args || {};
      if (!validate) return asToolResult({outcome:'failed',code:'unknown_tool',inputDelivered:false,error:'Unknown tool. Refresh tools/list and use a listed tool name.'},true);
      if (args && typeof args === 'object' && !Array.isArray(args) && args.requestId === undefined && !validate(args)) {
        const auto = { ...args, requestId: 'auto_' + randomBytes(12).toString('hex') };
        if (validate(auto)) {
          args = auto;
        } else {
          const autoErrors = validate.errors ? [...validate.errors] : [];
          const requestIdForbidden = autoErrors.some(e =>
            (e.keyword === 'additionalProperties' && e.params?.additionalProperty === 'requestId')
            || (e.keyword === 'not' && e.schemaPath?.includes('/not'))
          );
          if (!requestIdForbidden) return asToolResult(invalidArguments(name, auto, autoErrors), true);
        }
      }
      if (!validate(args)) return asToolResult(invalidArguments(name,args,validate.errors),true);
      const start = Date.now();
      try {
        const result = await journal(req.backstageGrant,name,args,()=>dispatch(name,args,req.backstageGrant,req.backstageClientName));
        audit({time:new Date().toISOString(),tool:name,ok:!['failed','unknown'].includes(result?.outcome)&&result?.ok!==false,outcome:result?.outcome,code:result?.code,jobId:result?.jobId,durationMs:Date.now()-start,device:args.device||null,route:result?.device?.id&&connections?.routeOf?connections.routeOf(result.device.id):null});
        const response = asToolResult(result);
        return response;
      } catch (e) {
        audit({time:new Date().toISOString(),tool:name,ok:false,code:e instanceof ConnectionError?e.code:'tool_failed',durationMs:Date.now()-start,device:args.device||null});
        const response=failure(e);
        return asToolResult({...response.structuredContent,inputDelivered:response.structuredContent.jobId?null:false,outcome:response.structuredContent.jobId?'unknown':'failed'},true);
      }
    });
    const transport = new StreamableHTTPServerTransport({sessionIdGenerator:undefined,enableJsonResponse:true});
    res.on('close',()=>{transport.close();server.close();});
    if([].concat(req.body).some(m=>m?.method==='tools/call'))jsonKeepAlive(res,keepAlive);
    try {await server.connect(transport);await transport.handleRequest(req,res,req.body);}
    catch {
      if(!res.headersSent)res.status(500).json({error:'mcp_request_failed'});
      else if(!res.writableEnded)res.end(JSON.stringify({jsonrpc:'2.0',id:Array.isArray(req.body)?null:req.body?.id??null,error:{code:-32603,message:'mcp_request_failed'}}));
    }
  });
  app.use((err,req,res,next)=>{if(!res.headersSent)res.status(err.status===413?413:400).json({error:'invalid_request'});});
  return app;
}
if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  const dir = process.env.BACKSTAGE_WORK_STATE;
  if (!dir || !process.env.BACKSTAGE_RELAY_URL) throw new Error('BACKSTAGE_WORK_STATE and BACKSTAGE_RELAY_URL required');
  fs.mkdirSync(dir,{recursive:true,mode:0o700});
  const cloudMode = process.env.BACKSTAGE_CLOUD_MODE === '1';
  const zeroRetention = process.env.BACKSTAGE_ZERO_RETENTION !== '0';
  const vault = loadVaultFromEnv();
  const directRoutes = (cloudMode || process.env.BACKSTAGE_DIRECT_ROUTES === '0') ? null : createDirectRoutes({log:line=>console.log(line)});
  const connections = createConnections({directory:path.join(dir,'connections'),relayUrl:process.env.BACKSTAGE_RELAY_URL,
    relayInternalUrl:process.env.BACKSTAGE_RELAY_INTERNAL_URL||null,relayInternalToken:process.env.RELAY_INTERNAL_TOKEN||process.env.RELAY_ADMIN_TOKEN||null,directRoutes,vault,log:line=>console.log(line)});
  const r2 = createR2FromEnv({
    ...process.env,
    RELAY_R2_PREFIX: process.env.WORK_R2_PREFIX || process.env.RELAY_R2_PREFIX || 'work-pulls/',
  });
  const artifacts = createArtifacts({directory:process.env.BACKSTAGE_ARTIFACTS_DIR||path.join(dir,'artifacts'),zeroRetention,r2});
  const base=process.env.BACKSTAGE_WORK_BASE || 'https://relay.android-backstage.com/work';
  const owners=createOwners({stateFile:path.join(dir,'owners.json'),origin:new URL(base).origin,androidOrigins,mailer:createMailer()});
  const auditEnabled = process.env.BACKSTAGE_AUDIT_LOG === '1';
  const app = createWorkApp({connections,owners,artifacts,base,zeroRetention,directFilesUrl:process.env.BACKSTAGE_DIRECT_FILES_URL||null,stateFile:path.join(dir,'oauth.json'),approvalFile:path.join(dir,'approval.json'),
    audit:auditEnabled ? (row=>fs.appendFileSync(path.join(dir,'calls.jsonl'),JSON.stringify(row)+'\n',{mode:0o600})) : (()=>{})});
  const bindHost = process.env.HOST || '127.0.0.1';
  const listener = app.listen(Number(process.env.PORT || 18424),bindHost,error=>{
    if(error){console.error('Cannot bind Work listener:',error.code);process.exitCode=1;}
    else console.log(`Backstage Work listening on ${bindHost} (zero-retention=${zeroRetention?'on':'off'}, vault=${vault?'sealed':'plain'}, r2=${r2?'on':'off'})`);
  });
  listener.keepAliveTimeout=65000;listener.headersTimeout=70000;
  listener.on('error',()=>{process.exitCode=1;});
  for(const signal of ['SIGINT','SIGTERM'])process.on(signal,()=>{connections.flushSync?.();r2?.close?.();listener.close(()=>process.exit(0));setTimeout(()=>process.exit(0),3000).unref();});
}
