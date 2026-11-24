import crypto from 'node:crypto';
import {connectionPage} from './connect-page.mjs';
import fs from 'node:fs';
import { writePrivateJson } from '../cli/state.mjs';
import {validRedirect,fetchClientMetadata,redirectMatches,callbackOriginMatches} from './oauth-clients.mjs';
import {consentPage,phoneApprovalPage} from './pages.mjs';
import {ConnectionError} from './connections.mjs';
const random = () => crypto.randomBytes(32).toString('base64url');
const APPROVAL_ALPHABET='ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const approvalCode=()=>{const b=crypto.randomBytes(8);let out='';for(let i=0;i<8;i++){out+=APPROVAL_ALPHABET[b[i]%32];if(i===3)out+='-';}return out;};
export const digest = value => crypto.createHash('sha256').update(value).digest('hex');
const equals = (a, b) => typeof a === 'string' && typeof b === 'string' && a.length === b.length && crypto.timingSafeEqual(Buffer.from(a), Buffer.from(b));
const escape = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export function createAuth({ base, stateFile, approvalFile, connections, owners, metadataReader=fetchClientMetadata, allowTestRedirect = false, now = Date.now }) {
  const resource = base + '/mcp';
  const policy = (redirect, nonce) => `default-src 'none'; style-src 'unsafe-inline';${nonce ? " script-src 'nonce-"+nonce+"'; connect-src 'self';" : ''} form-action 'self'${redirect ? ' '+redirect : ''}; frame-ancestors 'none'`;
  let state = { clients: {}, codes: {}, access: {}, refresh: {} };
  if (fs.existsSync(stateFile)) state = JSON.parse(fs.readFileSync(stateFile, 'utf8'));
  const pending = new Map(), rates = new Map(), invitations = new Map();
  const save = () => writePrivateJson(stateFile, state);
  const fail = (res, error, status = 400) => res.status(status).json({error});
  const checkClient = req => {
    let id = req.body.client_id, secret = req.body.client_secret;
    if (req.headers.authorization?.startsWith('Basic ')) {
      try {
        const raw = Buffer.from(req.headers.authorization.slice(6), 'base64').toString();
        const split = raw.indexOf(':');
        if (split <= 0) return null;
        id = decodeURIComponent(raw.slice(0, split));
        secret = decodeURIComponent(raw.slice(split+1));
      } catch { return null; }
    }
    const client = Object.hasOwn(state.clients,id) ? state.clients[id] : null;
    if (!client || (client.secret && !equals(client.secret, digest(secret || '')))) return null;
    client.lastUsedAt = now();
    return id;
  };
  const issue = row => {
    const token = random(), refresh = random();
    state.access[digest(token)] = {...row, audience:resource, scope:'phone:control', expires: now()+3600000};
    state.refresh[digest(refresh)] = {...row, audience:resource, scope:'phone:control', expires: now()+30*86400000};
    if (row.clientId && Object.hasOwn(state.clients, row.clientId)) state.clients[row.clientId].lastUsedAt = now();
    save();
    return { access_token: token, token_type: 'Bearer', expires_in: 3600, refresh_token: refresh, scope: 'phone:control' };
  };
  const pruneClients = (force = false) => {
    const t = now();
    const referenced = new Set();
    for (const key of ['codes','access','refresh']) for (const row of Object.values(state[key])) if (row?.clientId) referenced.add(row.clientId);
    for (const row of pending.values()) if (row?.clientId) referenced.add(row.clientId);
    let changed = false;
    for (const [id, client] of Object.entries(state.clients)) {
      if (referenced.has(id)) continue;
      const expiredMeta = client.expires && client.expires <= t;
      const age = t - (client.lastUsedAt || client.createdAt || 0);
      if (expiredMeta || (force ? age >= 0 : (client.createdAt && age > 35 * 86400000))) {
        delete state.clients[id];
        changed = true;
      }
    }
    if (changed) save();
  };
  const prune = () => {
    for (const key of ['codes','access','refresh']) for (const [id,row] of Object.entries(state[key])) if (row.expires <= now()) delete state[key][id];
    for (const [id,row] of pending) if (row.expires <= now()) pending.delete(id);
    for (const [id,row] of invitations) if (row.expires <= now()) invitations.delete(id);
    for (const [id,row] of rates) if (row.until <= now()) rates.delete(id);
    pruneClients(false);
  };
  function routes(app) {
    app.use((req,res,next) => {
      res.set({'Cache-Control':'no-store', 'X-Content-Type-Options':'nosniff', 'Referrer-Policy':'no-referrer',
        'Content-Security-Policy':policy()});
      prune(); next();
    });
    const metadata = (req,res) => res.json({issuer:base, authorization_endpoint:base+'/authorize', token_endpoint:base+'/token',
      registration_endpoint:base+'/register', revocation_endpoint:base+'/revoke', response_types_supported:['code'], grant_types_supported:['authorization_code','refresh_token'],
      token_endpoint_auth_methods_supported:['none','client_secret_post','client_secret_basic'], code_challenge_methods_supported:['S256'], scopes_supported:['phone:control'], authorization_response_iss_parameter_supported:true,client_id_metadata_document_supported:true});
    app.get(['/work/.well-known/oauth-authorization-server','/.well-known/oauth-authorization-server/work'],metadata);
    app.get(['/work/.well-known/oauth-protected-resource','/.well-known/oauth-protected-resource/work/mcp'], (req,res) => res.json({resource, authorization_servers:[base], scopes_supported:['phone:control']}));
    const hitRate = (key, max, windowMs = 60000) => {
      const t = now();
      let row = rates.get(key);
      if (!row || row.until <= t) row = { count: 0, until: t + windowMs };
      row.count++;
      if (!rates.has(key) && rates.size >= 10000) {
        for (const [k, v] of rates) if (v.until <= t) rates.delete(k);
        if (rates.size >= 10000) { const oldest = rates.keys().next().value; if (oldest !== undefined) rates.delete(oldest); }
      }
      rates.set(key, row);
      return row.count > max;
    };
    app.use(['/work/register','/work/authorize','/work/token','/work/revoke','/work/passkeys','/work/enroll','/work/account'], (req,res,next) => {
      if (hitRate(req.baseUrl + ':' + req.ip, 60, 60000)) return fail(res,'rate_limit_exceeded',429);
      next();
    });
    app.post('/work/register', (req,res) => {
      if (hitRate('register-hr:' + req.ip, 15, 3600000)) return fail(res,'rate_limit_exceeded',429);
      const b = req.body, method = b.token_endpoint_auth_method || 'none';
      if (!['none','client_secret_post','client_secret_basic'].includes(method) || !Array.isArray(b.redirect_uris) || b.redirect_uris.length < 1 || b.redirect_uris.length > 4 || !b.redirect_uris.every(validRedirect)) return fail(res,'invalid_client_metadata');
      if (Object.keys(state.clients).length >= 10000) pruneClients(true);
      if (Object.keys(state.clients).length >= 10000) return fail(res,'registration_limit',429);
      const id = random(), secret = method === 'none' ? null : random();
      state.clients[id] = {name:typeof b.client_name === 'string' ? b.client_name.replace(/[\x00-\x1f\x7f]/g,'').slice(0,60) : 'Assistant',redirects:b.redirect_uris,secret:secret ? digest(secret) : null, method, createdAt: now(), lastUsedAt: now()}; save();
      res.status(201).json({client_id:id,...(secret ? {client_secret:secret,client_secret_expires_at:0} : {}),redirect_uris:b.redirect_uris,token_endpoint_auth_method:method,grant_types:['authorization_code','refresh_token'],response_types:['code']});
    });
    app.get('/work/authorize', async (req,res) => {
      const q = req.query;
      if(typeof q.client_id!=='string')return fail(res,'invalid_client');
      if(q.client_id.startsWith('https://')&&(!Object.hasOwn(state.clients,q.client_id)||state.clients[q.client_id].expires<=now())){
        try{if(Object.keys(state.clients).length>=10000)pruneClients(true);if(Object.keys(state.clients).length>=10000)return fail(res,'registration_limit',429);state.clients[q.client_id]={...await metadataReader(q.client_id),createdAt:now(),lastUsedAt:now()};save();}catch{return fail(res,'invalid_client_metadata');}
      }
      const client = Object.hasOwn(state.clients,q.client_id)?state.clients[q.client_id]:null;
      if (!client || typeof q.redirect_uri!=='string' || !client.redirects.some(uri=>redirectMatches(uri,q.redirect_uri)) || q.response_type !== 'code' || q.resource !== resource || q.code_challenge_method !== 'S256' || !/^[A-Za-z0-9_-]{43}$/.test(q.code_challenge || '') || (q.scope && q.scope !== 'phone:control') || typeof q.state !== 'string' || q.state.length > 2048) return fail(res,'invalid_request');
      const ownerId=owners?.owner(req);
      prune();
      let mine=0;for(const row of pending.values())if(row.ip===req.ip)mine++;
      if (pending.size >= 512 || mine >= 8) return fail(res,'too_many_pending_requests',429);
      if(owners&&connections&&q.passkey!=='1'){
        // Approve from the Backstage app on the phone: the page shows a short code the owner types into the app.
        const nonce=random(),ticket=random(),code=approvalCode();
        res.set('Content-Security-Policy',policy(q.redirect_uri,nonce));
        const clientOrigin=new URL(q.redirect_uri).origin;
        pending.set(ticket,{ip:req.ip,clientId:q.client_id,clientName:clientOrigin==='https://chatgpt.com'?'ChatGPT':client.name,clientOrigin,ownerId:null,phone:true,code,redirect:q.redirect_uri,challenge:q.code_challenge,state:q.state,expires:now()+600000});
        const passkeyUrl='/work/sign-in?return_to='+encodeURIComponent(req.originalUrl+(req.originalUrl.includes('?')?'&':'?')+'passkey=1');
        return res.type('html').send(phoneApprovalPage({base,ticket,nonce,code,clientName:pending.get(ticket).clientName,clientOrigin,passkeyUrl}));
      }
      if(owners&&!ownerId)return res.redirect(303,'/work/sign-in?return_to='+encodeURIComponent(req.originalUrl));
      // Chromium applies form-action to the redirect after a form POST too.
      // Permit only this client's already validated, registered callback.
      const nonce = random();
      res.set('Content-Security-Policy',policy(q.redirect_uri,nonce));
      const ticket = random(); pending.set(ticket, {ip:req.ip,clientId:q.client_id,clientName:client.name,ownerId,redirect:q.redirect_uri,challenge:q.code_challenge,state:q.state,expires:now()+600000});
      if(ownerId&&connections.hasPhone(ownerId)){
        const current=connections.list(ownerId).find(row=>row.clientId===q.client_id)?.access||'all';
        return res.type('html').send(consentPage({clientName:client.name,redirect:q.redirect_uri,ticket,base,nonce,devices:connections.devices(ownerId),current}));
      }
      if (connections) {
        try {
          const row=pending.get(ticket);
          const clientName=new URL(q.redirect_uri).origin === 'https://chatgpt.com' ? 'ChatGPT' : client.name || 'Assistant';
          row.connection=await connections.begin({clientName,ownerId,clientId:q.client_id});
          row.connection.clientOrigin=new URL(q.redirect_uri).origin;
          return res.type('html').send(await connectionPage({base,ticket,nonce,connection:row.connection}));
        } catch {pending.delete(ticket); return fail(res,'connection_unavailable',503);}
      }
      res.type('html').send(`<!doctype html><html lang="en"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Connect Android Backstage</title><style>body{font:17px system-ui;background:#f5f7f2;color:#14232d;margin:0;padding:32px}main{max-width:460px;margin:8vh auto;background:white;padding:32px;border:1px solid #d5ded5;border-radius:24px}h1{font-size:30px}p{line-height:1.55;color:#52616a}label{display:block;font-weight:600}input,button{box-sizing:border-box;width:100%;padding:16px;margin-top:12px;border-radius:12px;border:1px solid #52616a;font:inherit}button{background:#9bef47;font-weight:700;cursor:pointer}.small{font-size:14px}</style><main><h1>Connect your phone to ChatGPT</h1><p>ChatGPT Work will use the <strong>ChatGPT Work</strong> connection in Backstage. Its chats share this access. You can pause or remove it on your phone.</p><p>This allows phone commands and app control. Tool results go to ChatGPT.</p><form id="approve" method="post" action="${escape(base)}/authorize"><input type="hidden" name="ticket" value="${escape(ticket)}"><label for="code">One-time connection approval code</label><input id="code" name="code" type="password" autocomplete="off" required><button type="submit">Connect ChatGPT Work</button><p id="result" role="status"></p></form><p class="small">Get a code on the computer hosting Backstage: <code>backstage-work-approve</code>. Codes expire in 10 minutes and work once.</p></main><script nonce="${nonce}">
const form=document.getElementById('approve'),result=document.getElementById('result');
form.addEventListener('submit',async event=>{
  event.preventDefault();const button=form.querySelector('button');button.disabled=true;result.textContent='Connecting…';
  try {
    const response=await fetch(form.action,{method:'POST',headers:{'Content-Type':'application/json','Accept':'application/json'},body:JSON.stringify(Object.fromEntries(new FormData(form)))});
    const data=await response.json();form.elements.code.value='';
    if(!response.ok)throw new Error('This approval expired or was already used. Run backstage-work-approve for a new code, then try again.');
    window.location.assign(data.redirect);
  } catch(error) {result.textContent=error.message || 'Could not connect. Check your internet and try again.';button.disabled=false;}
});
</script></html>`);
    });
    // The approval page's "send to my phone": the owner names their account by email.
    app.post('/work/authorize/phone', (req,res) => {
      if (req.headers.origin !== new URL(base).origin) return fail(res,'invalid_origin',403);
      if(hitRate('phone-send:'+req.ip,10,600000))return fail(res,'rate_limit_exceeded',429);
      const ownerId=owners?.ownerByEmail?.(req.body?.email);
      // Anti-enumeration: always return a 6-digit check value whether or not the address has an account.
      if(!ownerId)return res.json({sent:true,check:String(crypto.randomInt(100000,1000000))});
      try{res.json({sent:true,...connectionRequests.forOAuth(req.body?.ticket,ownerId)});}catch(e){fail(res,e.message);}
    });
    app.post('/work/authorize', async (req,res) => {
      if (req.headers.origin !== new URL(base).origin) return fail(res,'invalid_origin',403);
      const ticket = req.body.ticket, row = pending.get(ticket);
      if(row?.phone){
        if(req.body.cancel){pending.delete(ticket);return res.json({cancelled:true});}
        if(row.expires<=now()){pending.delete(ticket);return fail(res,'expired_approval',403);}
        if(!row.grantId)return res.json({pending:true,denied:!!row.denied});
        pending.delete(ticket);const code=random();state.codes[digest(code)]={clientId:row.clientId,redirect:row.redirect,challenge:row.challenge,grantId:row.grantId,expires:now()+120000};save();
        const redirect=new URL(row.redirect);redirect.searchParams.set('code',code);redirect.searchParams.set('state',row.state);redirect.searchParams.set('iss',base);return res.json({redirect:redirect.href});
      }
      if(owners&&(!row||row.ownerId!==owners.owner(req)))return fail(res,'sign_in_required',403);
      if (connections) {
        if(row&&!row.connection&&owners){
          if(req.body.cancel){pending.delete(ticket);return res.json({cancelled:true});}
          if(req.body.approve!==true||row.expires<=now())return fail(res,'approval_required',403);
          const access=req.body.allDevices===true?'all':Array.isArray(req.body.devices)?req.body.devices.filter(id=>typeof id==='string').slice(0,20):'all';
          if(Array.isArray(access)&&access.length===0)return fail(res,'choose_device',400);
          try{
            const grantId=connections.authorize(row.ownerId,row.clientId,row.clientName,access);
            pending.delete(ticket);const code=random();state.codes[digest(code)]={clientId:row.clientId,redirect:row.redirect,challenge:row.challenge,grantId,expires:now()+120000};save();
            const redirect=new URL(row.redirect);redirect.searchParams.set('code',code);redirect.searchParams.set('state',row.state);redirect.searchParams.set('iss',base);return res.json({redirect:redirect.href});
          }catch{return fail(res,'phone_unavailable',403);}
        }
        if (row?.connection && req.body.cancel) {
          pending.delete(ticket);
          try {await row.connection.cancel();} catch { /* Approval may have already completed; the polling request discards it. */ }
          return res.json({cancelled:true});
        }
        if (!row || !row.connection || row.busy) return fail(res,'invalid_or_expired_approval',403);
        row.busy=true;
        try {
          const grantId=await row.connection.poll();
          if (!grantId) return res.json({pending:true});
          if (row.expires <= now() || !pending.has(ticket)) {connections.revoke(grantId);return fail(res,'expired_approval',403);}
          pending.delete(ticket);
          const code=random();
          state.codes[digest(code)]={clientId:row.clientId,redirect:row.redirect,challenge:row.challenge,grantId,expires:now()+120000};save();
          const redirect=new URL(row.redirect);redirect.searchParams.set('code',code);redirect.searchParams.set('state',row.state);redirect.searchParams.set('iss',base);
          return res.json({redirect:redirect.href});
        } catch {pending.delete(ticket);return fail(res,'connection_failed',403);}
        finally {row.busy=false;}
      }
      let approval; try { approval = JSON.parse(fs.readFileSync(approvalFile,'utf8')); } catch { }
      if (!row || !approval || approval.expires <= now() || !equals(approval.hash, digest(String(req.body.code || '')))) return fail(res,'invalid_or_expired_approval',403);
      // Consume approval before issuing a single-use authorization code.
      fs.unlinkSync(approvalFile); pending.delete(ticket);
      const code = random(); state.codes[digest(code)] = {...row,expires:now()+120000}; save();
      const redirect = new URL(row.redirect); redirect.searchParams.set('code',code); redirect.searchParams.set('state',row.state); redirect.searchParams.set('iss',base);
      res.set('Content-Security-Policy',policy(row.redirect));
      if (req.get('Accept') === 'application/json') return res.json({redirect:redirect.href});
      res.redirect(303,redirect.href);
    });
    app.post('/work/token', (req,res) => {
      const clientId = checkClient(req); if (!clientId) return fail(res,'invalid_client',401);
      const b = req.body; if (b.resource !== resource) return fail(res,'invalid_target');
      if (b.grant_type === 'authorization_code') {
        const hash = digest(String(b.code || '')), row = state.codes[hash];
        if (!row || row.expires <= now() || row.clientId !== clientId || row.redirect !== b.redirect_uri || !/^[A-Za-z0-9._~-]{43,128}$/.test(b.code_verifier || '') || !equals(row.challenge,crypto.createHash('sha256').update(b.code_verifier).digest('base64url'))) return fail(res,'invalid_grant');
        if (connections && !connections.active(row.grantId)) return fail(res,'invalid_grant');
        delete state.codes[hash]; return res.json(issue({clientId,...(row.grantId ? {grantId:row.grantId} : {})}));
      }
      if (b.grant_type === 'refresh_token') {
        const hash = digest(String(b.refresh_token || '')), row = state.refresh[hash];
        if (!row || row.expires <= now() || row.clientId !== clientId || row.audience !== resource || (b.scope && b.scope !== 'phone:control')) return fail(res,'invalid_grant');
        if (connections && !connections.active(row.grantId)) return fail(res,'invalid_grant');
        delete state.refresh[hash]; return res.json(issue({clientId,...(row.grantId ? {grantId:row.grantId} : {})}));
      }
      fail(res,'unsupported_grant_type');
    });
    app.post('/work/revoke', (req,res) => {
      const clientId = checkClient(req); if (!clientId) return fail(res,'invalid_client',401);
      const hash = digest(String(req.body.token || ''));
      const grantId=[state.access[hash],state.refresh[hash]].find(row=>row?.clientId===clientId)?.grantId;
      if (grantId) {
        for (const key of ['codes','access','refresh']) for (const [id,row] of Object.entries(state[key])) if (row.grantId===grantId) delete state[key][id];
        connections?.revoke(grantId);
      } else for (const key of ['access','refresh']) if (state[key][hash]?.clientId === clientId) delete state[key][hash];
      save(); res.sendStatus(200);
    });
  }
  const liveAccess=token=>{
    const row = token ? state.access[digest(token)] : null;
    if (!row || row.expires <= now() || row.audience !== resource || row.scope !== 'phone:control' || (connections && !connections.active(row.grantId))) return null;
    return row;
  };
  function requireToken(req,res,next) {
    const token = /^Bearer ([A-Za-z0-9_-]+)$/.exec(req.headers.authorization || '')?.[1];
    const row = liveAccess(token);
    if (!row) {
      res.set('WWW-Authenticate', `Bearer resource_metadata="${base}/.well-known/oauth-protected-resource", scope="phone:control"`);
      return fail(res,'unauthorized',401);
    }
    req.backstageGrant = row.grantId;
    req.backstageClient = row.clientId;
    req.backstageClientName = (Object.hasOwn(state.clients,row.clientId) && state.clients[row.clientId].name) || 'An assistant'; next();
  }
  /** Grant behind a live access token, or null. Used by non-MCP routes such as artifact uploads. */
  const grantForToken=token=>liveAccess(token)?.grantId||null;
  const findApproval=code=>{prune();const clean=String(code||'').toUpperCase().replace(/[^A-Z0-9]/g,'');for(const [ticket,row] of pending)if(row.phone&&!row.denied&&!row.grantId&&row.code.replace('-','')===clean)return [ticket,row];return [null,null];};
  /** Approvals the phone app completes: preview by code, then approve with a device choice or deny. */
  const approvals={
    preview(code){const [,row]=findApproval(code);if(!row)throw new ConnectionError('unknown_approval','That code is not waiting for approval. Ask the assistant to connect again.');return {clientName:row.clientName,clientOrigin:row.clientOrigin,expiresAt:new Date(row.expires).toISOString()};},
    approve(code,ownerId,access){const [,row]=findApproval(code);if(!row)throw new ConnectionError('unknown_approval','That code is not waiting for approval. Ask the assistant to connect again.');
      if(row.inviteOwner && row.inviteOwner!==ownerId)throw new ConnectionError('unknown_approval','This request belongs to another account.');
      row.grantId=connections.authorize(ownerId,row.clientId,row.clientName,access||"all");row.ownerId=ownerId;return {clientName:row.clientName,grantId:row.grantId};},
    deny(code){const [ticket,row]=findApproval(code);if(row){row.denied=true;row.expires=now()+15000;}return !!row;}
  };
  const invites={
    create(ownerId,deviceId){
      prune();
      if(invitations.size>=512)throw new ConnectionError('busy','Try again in a minute.');
      // Several invitations may wait at once; each expires on its own.
      const code=crypto.randomBytes(18).toString('base64url');
      invitations.set(digest(code),{ownerId,deviceId,expires:now()+600000});
      return {code,expiresIn:600};
    },
    status(code,ownerId){
      prune();const row=invitations.get(digest(String(code||'')));
      if(!row||row.ownerId!==ownerId)throw new ConnectionError('unknown_approval','This invitation expired. Copy a new one.');
      const request=row.request;
      return {state:request?.grantId?'approved':request?.denied?'denied':request&&pending.has(row.ticket)?'waiting':row.ticket?'cancelled':'unclaimed',
        ...(request&&pending.has(row.ticket)&&!request.grantId&&!request.denied?{approvalCode:request.code,clientName:request.clientName,clientOrigin:request.clientOrigin,check:request.check}:{}),expiresAt:new Date(row.expires).toISOString()};
    },
    cancel(code,ownerId){const row=invitations.get(digest(String(code||'')));if(row?.ownerId===ownerId){if(row.ticket)pending.delete(row.ticket);invitations.delete(digest(code));}return {cancelled:true};},
    claim({code,ticket,clientName,challenge}){
      prune();const row=invitations.get(digest(String(code||'')));
      if(!row)throw new ConnectionError('unknown_approval','This invitation expired. Copy a new one in Backstage.');
      if(row.ticket){
        const previous=pending.get(row.ticket);
        const same=previous&&!previous.denied&&(ticket ? ticket===row.ticket&&!previous.local : previous.local&&typeof challenge==='string'&&equals(challenge,previous.challenge)&&clientName?.replace(/[\x00-\x1f\x7f]/g,'')===previous.clientName);
        if(!same)throw new ConnectionError('unknown_approval','This invitation was already used. Copy a new one in Backstage.');
        return {ticket:row.ticket,check:previous.check,clientName:previous.clientName,approved:!!previous.grantId,expiresIn:Math.max(0,Math.floor((row.expires-now())/1000))};
      }
      let request;
      if(ticket){request=pending.get(ticket);if(!request?.phone||request.denied||request.grantId||request.inviteOwner)throw new ConnectionError('invalid_request','Start the agent connection again.');}
      else {
        if(!/^[A-Za-z0-9_-]{43}$/.test(challenge||'')||typeof clientName!=='string'||!clientName.trim()||clientName.length>60)throw new ConnectionError('invalid_request','An agent name and connection proof are required.');
        if(pending.size>=512)throw new ConnectionError('busy','Try again later.');
        if(Object.keys(state.clients).length>=10000)pruneClients(true);
        if(Object.keys(state.clients).length>=10000)throw new ConnectionError('busy','Try again later.');
        const clientId=random();state.clients[clientId]={name:clientName.replace(/[\x00-\x1f\x7f]/g,''),redirects:[],secret:null,method:'none',createdAt:now(),lastUsedAt:now()};save();
        ticket=random();request={phone:true,local:true,clientId,clientName:state.clients[clientId].name,clientOrigin:'',code:approvalCode(),challenge,expires:row.expires};pending.set(ticket,request);
      }
      // The owner already authenticated on their phone to mint this 144-bit single-use invitation;
      // claiming it with PKCE grants access scoped to the originating device in one step.
      request.inviteOwner=row.ownerId;request.inviteDevice=row.deviceId;
      request.ownerId=row.ownerId;
      const inviteAccess=row.deviceId?[row.deviceId]:'all';
      request.grantId=connections.authorize(row.ownerId,request.clientId,request.clientName,inviteAccess);
      request.check=String(crypto.randomInt(100000,1000000));row.ticket=ticket;row.request=request;
      return {ticket,check:request.check,clientName:request.clientName,approved:true,expiresIn:Math.max(0,Math.floor((row.expires-now())/1000))};
    },
    poll(ticket,verifier){
      prune();const row=pending.get(ticket);
      if(!row?.local||typeof verifier!=='string'||!equals(crypto.createHash('sha256').update(verifier).digest('base64url'),row.challenge))throw new ConnectionError('unknown_approval','This connection expired. Start again.');
      if(row.denied)return {denied:true};
      if(!row.grantId)return {pending:true};
      row.result ||= {...issue({clientId:row.clientId,grantId:row.grantId}),client_id:row.clientId};
      return row.result;
    }
  };
  /**
   * Connection requests started by an agent. The agent names one or more
   * connections and proves a secret it keeps (PKCE-style). If it already holds
   * a Backstage connection, that connection identifies the owner and the
   * request goes straight to the owner's phones as a notification; otherwise
   * the owner signs in on the approval page. Several requests may wait at once.
   */
  const requests=new Map(),watchers=new Map();
  const wakeOwner=ownerId=>{const set=watchers.get(ownerId);if(!set)return;watchers.delete(ownerId);for(const resolve of set)resolve();};
  const requestView=row=>({request:row.id,names:row.clients.map(c=>c.name),check:row.check,createdAt:new Date(row.createdAt).toISOString(),expiresAt:new Date(row.expires).toISOString()});
  const liveRequest=id=>{const row=requests.get(String(id||''));if(!row)return null;if(row.expires<=now()){requests.delete(row.id);return null;}return row;};
  const connectionRequests={
    create({names,challenge,bearer,email}){
      for(const [id,row] of requests)if(row.expires<=now())requests.delete(id);
      const list=(Array.isArray(names)?names:[]).map(n=>String(n||'').replace(/[\x00-\x1f\x7f]/g,'').trim()).filter(Boolean);
      if(!list.length||list.length>8||list.some(n=>n.length>60))throw new ConnectionError('invalid_request','Name one to eight agents, each up to 60 characters.');
      if(!/^[A-Za-z0-9_-]{43}$/.test(challenge||''))throw new ConnectionError('invalid_request','A connection proof is required.');
      if(requests.size>=512)throw new ConnectionError('busy','Try again later.');
      const access=bearer?liveAccess(bearer):null;
      const emailSupplied=typeof email==='string'&&email.trim().length>0;
      const ownerId=(access&&connections?.ownerOf?connections.ownerOf(access.grantId):null)
        ||(emailSupplied?owners?.ownerByEmail?.(email):null);
      if(ownerId&&[...requests.values()].filter(r=>r.ownerId===ownerId&&!r.decision&&r.expires>now()).length>=5)throw new ConnectionError('busy','Too many pending requests for this account. Approve or deny one on your phone first.');
      // Do not persist OAuth clients until the owner approves the request (prevents unauthenticated client-table exhaustion).
      const clients=list.map(name=>({clientId:random(),name}));
      const id='req_'+crypto.randomBytes(16).toString('hex');
      const row={id,ownerId,clients,challenge,check:String(crypto.randomInt(100000,1000000)),createdAt:now(),expires:now()+600000,decision:null,grants:null,results:null};
      requests.set(id,row);if(ownerId)wakeOwner(ownerId);
      // Anti-enumeration: when email is supplied, report ownerKnown:true regardless of whether an account exists.
      return {request:id,check:row.check,expiresIn:600,ownerKnown:!!(ownerId||emailSupplied),approveUrl:base+'/connect/approve?request='+id};
    },
    attachOwner(id,ownerId){
      const row=liveRequest(id);
      if(!row||row.decision)throw new ConnectionError('unknown_approval','This request already finished or expired.');
      if(row.ownerId&&row.ownerId!==ownerId)return {check:row.check};
      if(!row.ownerId&&[...requests.values()].filter(r=>r.ownerId===ownerId&&!r.decision&&r.expires>now()).length>=5)throw new ConnectionError('busy','Too many pending requests for this account. Approve or deny one on your phone first.');
      row.ownerId=ownerId;wakeOwner(ownerId);
      return {check:row.check};
    },
    statusPublic(id){
      const row=liveRequest(id);
      if(!row)return {state:'expired'};
      return {state:row.decision==='allow'?'approved':row.decision==='deny'?'denied':'pending',check:row.check};
    },
    pending(ownerId){return [...requests.values()].filter(r=>r.ownerId===ownerId&&!r.decision&&r.expires>now()).map(requestView);},
    view(id){const row=liveRequest(id);return row&&!row.decision?requestView(row):null;},
    /** Resolve after a change for this owner, or after ms. */
    wait(ownerId,ms){return new Promise(resolve=>{const set=watchers.get(ownerId)||new Set();const done=()=>{clearTimeout(timer);resolve();};const timer=setTimeout(()=>{set.delete(done);resolve();},ms);set.add(done);watchers.set(ownerId,set);});},
    /** Send a browser OAuth approval to the owner's phones instead of typing a code. */
    forOAuth(ticket,ownerId){
      for(const [id,row] of requests)if(row.expires<=now())requests.delete(id);
      const oauth=pending.get(ticket);
      if(!oauth?.phone||oauth.grantId||oauth.denied||oauth.expires<=now())throw new ConnectionError('unknown_approval','This connection request expired. Start again from your assistant.');
      for(const r of requests.values())if(r.oauthTicket===ticket&&!r.decision)return {check:r.check};
      if(requests.size>=512)throw new ConnectionError('busy','Try again later.');
      if([...requests.values()].filter(r=>r.ownerId===ownerId&&!r.decision&&r.expires>now()).length>=5)throw new ConnectionError('busy','Too many pending requests for this account. Approve or deny one on your phone first.');
      const id='req_'+crypto.randomBytes(16).toString('hex');
      const row={id,ownerId,clients:[{clientId:oauth.clientId,name:oauth.clientName}],oauthTicket:ticket,check:String(crypto.randomInt(100000,1000000)),createdAt:now(),expires:Math.min(oauth.expires,now()+600000),decision:null};
      requests.set(id,row);wakeOwner(ownerId);return {check:row.check};
    },
    decide(id,ownerId,allow,access='all'){
      const row=liveRequest(id);
      if(!row||row.decision)throw new ConnectionError('unknown_approval','This request already finished or expired.');
      if(row.ownerId&&row.ownerId!==ownerId)throw new ConnectionError('unknown_approval','This request belongs to another account.');
      row.ownerId=ownerId;
      if(allow&&row.oauthTicket){
        const oauth=pending.get(row.oauthTicket);
        if(!oauth||oauth.expires<=now())throw new ConnectionError('unknown_approval','This connection request expired. Start again from your assistant.');
        oauth.grantId=connections.authorize(ownerId,oauth.clientId,oauth.clientName,access||'all');oauth.ownerId=ownerId;row.decision='allow';
      }
      else if(allow){
        if(Object.keys(state.clients).length+row.clients.length>10000)pruneClients(true);
        if(Object.keys(state.clients).length+row.clients.length>10000)throw new ConnectionError('busy','Try again later.');
        for(const c of row.clients){state.clients[c.clientId]={name:c.name,redirects:[],secret:null,method:'none',createdAt:now(),lastUsedAt:now()};}
        save();
        connections.checkAssistantAllowance?.(ownerId,row.clients.map(c=>c.clientId));
        row.grants=row.clients.map(c=>connections.authorize(ownerId,c.clientId,c.name,access||'all'));row.decision='allow';
      }
      else {row.decision='deny';row.expires=Math.min(row.expires,now()+60000);}
      wakeOwner(ownerId);return {names:row.clients.map(c=>c.name),decision:row.decision};
    },
    poll(id,verifier){
      const row=liveRequest(id);
      if(!row||typeof verifier!=='string'||!equals(crypto.createHash('sha256').update(verifier).digest('base64url'),row.challenge))throw new ConnectionError('unknown_approval','This request expired. Start again.');
      if(row.decision==='deny')return {denied:true};
      if(row.decision!=='allow')return {pending:true,check:row.check};
      row.results||=row.clients.map((c,i)=>({name:c.name,client_id:c.clientId,...issue({clientId:c.clientId,grantId:row.grants[i]})}));
      return {connections:row.results};
    }
  };
  const originAllowed=(origin,clientId)=>origin===new URL(base).origin||(Object.hasOwn(state.clients,clientId)&&state.clients[clientId].redirects.some(uri=>callbackOriginMatches(uri,origin)));
  const registeredOrigin=origin=>origin===new URL(base).origin||Object.values(state.clients).some(client=>client.redirects.some(uri=>callbackOriginMatches(uri,origin)));
    /** Registered name and first callback origin of a client, for display. */
  const revokeGrants = grantIds => {
    const set = new Set(grantIds || []);
    if (!set.size) return 0;
    let removed = 0;
    for (const key of ['codes','access','refresh']) {
      for (const [id, row] of Object.entries(state[key])) {
        if (set.has(row.grantId)) { delete state[key][id]; removed++; }
      }
    }
    if (removed) save();
    pruneClients(true);
    return removed;
  };
  const purgeOwner = ownerId => {
    for (const [id, row] of pending) if (row.ownerId === ownerId || row.inviteOwner === ownerId) pending.delete(id);
    for (const [id, row] of invitations) if (row.ownerId === ownerId) invitations.delete(id);
    for (const [id, row] of requests) if (row.ownerId === ownerId) requests.delete(id);
    wakeOwner(ownerId);
    pruneClients(true);
  };
  const clientInfo=id=>{const c=Object.hasOwn(state.clients,id)?state.clients[id]:null;let origin=null;try{origin=c?new URL(c.redirects[0]).origin:null;}catch{}return {name:c?.name||null,origin};};
  return {routes,requireToken,originAllowed,registeredOrigin,grantForToken,revokeGrants,purgeOwner,approvals,invites,clientInfo,requests:connectionRequests};
}
