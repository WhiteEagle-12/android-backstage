import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {ConnectionError} from './connections.mjs';
import {assistantIcon} from './assistants.mjs';

/**
 * Account management from the Backstage app. The phone already holds the link key
 * it shares with this server, so it proves possession of that key (HMAC over a
 * fresh nonce) and receives a short session bound to its owner. Everything the
 * browser account page can do is available here as JSON, so the app is the
 * management surface and the server stays a backend.
 */
export function phoneRoutes(app,{connections,auth,owners,base,appReleases,artifacts=null,nativeSignIn=null,now=Date.now}) {
  const nonces=new Map(), sessions=new Map(), attempts=new Map(), adds=new Map();
  const random=()=>crypto.randomBytes(32).toString('base64url');
  const digest=v=>crypto.createHash('sha256').update(v).digest('hex');
  const evict=(map,max)=>{while(map.size>=max){const k=map.keys().next().value;if(k===undefined)break;map.delete(k);}};
  const prune=()=>{
    const t=now();
    for(const m of [nonces,sessions])for(const [k,v] of m)if(v.expires<=t)m.delete(k);
    for(const [k,v] of attempts)if(v.until<=t)attempts.delete(k);
    for(const [k,v] of adds)if(v.expires<=t){adds.delete(k);v.pairing?.cancel?.().catch(()=>{});}
  };
  const limited=(key,max,windowMs)=>{
    prune();
    let row=attempts.get(key);
    if(!row||row.until<=now())row={count:0,until:now()+windowMs};
    row.count++;
    if(!attempts.has(key))evict(attempts,10000);
    attempts.set(key,row);
    return row.count>max;
  };
  const send=(res,e)=>{if(e instanceof ConnectionError)return res.status({unknown_device:404,unknown_approval:404,access_revoked:404,invalid_request:400,plan_limit:403,busy:429,no_devices:409,assistant_paused:409}[e.code]||400).json({error:e.code,message:e.message});res.status(500).json({error:'server_error',message:'Something went wrong on the server.'});};

  // The phone answers with HMAC-SHA256(linkKey, "backstage owner v1\n" + nonce).
  app.post('/work/phone/challenge',(req,res)=>{
    prune();
    const agentId=String(req.body?.agentId||'');
    if(limited('challenge:'+req.ip,60,60000)||nonces.size>=1000)return res.status(429).json({error:'busy',message:'Try again in a minute.'});
    const found=connections.deviceByAgent(agentId);
    if(!found)return res.status(404).json({error:'unknown_device',message:'This phone is not linked to an account yet.'});
    const nonce=random();nonces.set(nonce,{agentId,expires:now()+120000});
    res.json({nonce,expiresIn:120});
  });
  app.post('/work/phone/session',(req,res)=>{
    prune();
    const {agentId,nonce,mac}=req.body||{};
    if(limited('session:'+req.ip,30,60000))return res.status(429).json({error:'busy',message:'Try again in a minute.'});
    const row=typeof nonce==='string'?nonces.get(nonce):null;
    if(!row||row.agentId!==agentId){return res.status(403).json({error:'challenge_failed',message:'Start again.'});}
    nonces.delete(nonce);
    const found=connections.deviceByAgent(agentId);
    if(!found)return res.status(404).json({error:'unknown_device',message:'This phone is not linked to an account yet.'});
    const expected=crypto.createHmac('sha256',Buffer.from(found.linkKey,'base64url')).update('backstage owner v1\n'+nonce).digest();
    let given;try{given=Buffer.from(String(mac||''),'base64url');}catch{given=Buffer.alloc(0);}
    if(given.length!==expected.length||!crypto.timingSafeEqual(given,expected))return res.status(403).json({error:'challenge_failed',message:'This phone could not prove its link. Reconnect it if the problem continues.'});
    const token=random();evict(sessions,10000);sessions.set(digest(token),{ownerId:found.ownerId,deviceId:found.device.id,agentId,expires:now()+12*3600000});
    res.json({token,deviceId:found.device.id,expiresIn:12*3600});
  });
  function requireSession(req,res,next){
    prune();
    const token=/^Bearer ([A-Za-z0-9_-]+)$/.exec(req.headers.authorization||'')?.[1];
    const row=token?sessions.get(digest(token)):null;
    const linked=row&&connections.deviceByAgent(row.agentId);
    if(!row||!linked||linked.ownerId!==row.ownerId||linked.device.id!==row.deviceId)return res.status(401).json({error:'session_expired',message:'Sign the phone in again.'});
    req.phone=row;next();
  }
  app.post('/work/phone/invitations',requireSession,(req,res)=>{
    try {res.json(auth.invites.create(req.phone.ownerId,req.phone.deviceId));}catch(e){send(res,e);}
  });
  app.post('/work/phone/invitations/status',requireSession,(req,res)=>{
    try {res.json(auth.invites.status(req.body?.code,req.phone.ownerId));}catch(e){send(res,e);}
  });
  app.post('/work/phone/invitations/cancel',requireSession,(req,res)=>res.json(auth.invites.cancel(req.body?.code,req.phone.ownerId)));
  app.post('/work/connect/claim',(req,res)=>{
    if(limited('invite:'+req.ip,30,60000))return res.status(429).json({message:'Wait a minute, then try again.'});
    try {res.json(auth.invites.claim(req.body||{}));}catch(e){send(res,e);}
  });
  app.post('/work/connect/poll',(req,res)=>{
    if(limited('invite-poll:'+req.ip,60,60000))return res.status(429).json({message:'Wait a minute, then try again.'});
    try {res.json(auth.invites.poll(req.body?.ticket,req.body?.verifier));}catch(e){send(res,e);}
  });
  app.get('/work/connect/agent.mjs',(_req,res)=>res.type('text/javascript').sendFile('agent-connector.mjs',{root:fileURLToPath(new URL('./',import.meta.url))}));
  app.get('/work/connect/instructions',(req,res)=>res.json({
    host:['cursor','codex','claude-code','grok','opencode','gemini','antigravity','chatgpt'].includes(req.query.host)?req.query.host:'other',
    requirements:'Local setup needs Node.js 22 or newer. Never reset existing connections or ask for a new approval to retry an install that was already approved.',
    server:base+'/mcp',
    instructions:'Connect Android Backstage with minimal friction. For CLI and desktop coding agents (including Claude Code, Codex, Cursor, Antigravity, Gemini CLI, OpenCode, and Grok), use local (agent.mjs): it works over HTTPS with no localhost OAuth callback server and automatically installs and verifies the MCP server. If the user pasted a one-use invitation from Backstage, connect --code finishes immediately in one step with zero extra phone confirmation. Otherwise, connect --all --email USER_EMAIL pushes straight to their phone (one tap Allow, no browser login). Never ask the user to paste passwords/tokens or manually open Claude Code to run /mcp.',
    local:{script:base+'/connect/agent.mjs',
      command:'node agent.mjs connect --name AGENT_NAME --host HOST [--email USER_EMAIL]',
      all:'node agent.mjs connect --all [--email USER_EMAIL]',
      invitation:'node agent.mjs connect --code INVITATION --name AGENT_NAME --host HOST',
      hosts:['cursor','codex','claude-code','grok','opencode','gemini','antigravity'],
      next:'Download agent.mjs to a private local folder. If the user gave a one-use invitation, run `node agent.mjs connect --code INVITATION --name AGENT_NAME --host HOST`—it approves, installs, and verifies automatically in one shot with no second tap on the phone. Without an invitation, run `node agent.mjs connect --all --email USER_EMAIL` (or omit `--email` if this computer already has a Backstage connection; if you do not know the email, ask for just their email or start in the background and show the user the approval link and six check digits immediately so they can tap Allow on their phone with zero browser login). If installation fails after approval, keep the saved account file and run `node agent.mjs install --host HOST --account FILE`. Then verify MCP automatically yourself (see verify) and report success only after android_devices returns an online phone model. Never print credentials.'},
    verify:{
      description:'Run automatic end-to-end MCP verification yourself right after install so the user never has to open Claude Code or another CLI manually. Replace SERVER_NAME with the exact name printed by agent.mjs (e.g. android-backstage or android-backstage-xxxxxxxx).',
      'claude-code':'claude -p "Call the android_devices MCP tool and return the connected phone name and model." --allowedTools "mcp__SERVER_NAME__*"',
      codex:'codex exec "Call the android_devices MCP tool and return the connected phone name and model."',
      gemini:'gemini -p "Call the android_devices MCP tool and return the connected phone name and model."',
      standalone:'If the host has no headless CLI, run `node agent.mjs verify --account FILE` (which executes a live MCP initialize + android_devices call against the phone) and report that it is verified and ready for the next session.'
    },
    native:{next:'Only use native remote HTTP OAuth when running interactively in the same OS as the browser (not over SSH, WSL, or headless sessions where the localhost callback port is unreachable or closes early). Register the server URL as a remote (Streamable HTTP) MCP server named android-backstage in user scope (`claude mcp add --transport http --scope user android-backstage URL`, `codex mcp add android-backstage --url URL`, or `gemini mcp add --transport http android-backstage URL`), start sign-in, and verify with android_devices.'},
    cloud:{instructions:'Add the Android Backstage connection using the server URL and OAuth. On its connection page, enter your Backstage email to approve on your phone (or paste a one-use code from Backstage for instant approval). Ask the user to press Connect only if your host requires it.'}
  }));
  const describeGrant=(ownerId,g)=>{const info=auth?.clientInfo?.(g.clientId)||{};const origin=info.origin||null;return {id:g.id,name:g.name||info.name||'Assistant',origin,icon:assistantIcon(g.name||info.name||'',origin||''),access:g.access,createdAt:g.createdAt,paused:!!g.paused,lastUsedAt:g.lastUsedAt?new Date(g.lastUsedAt).toISOString():null,sessions:g.sessions||[]};};

  // ---- Agent-started connection requests ---------------------------------
  // An agent asks; the owner approves from a phone notification or a web page.
  app.post('/work/connect/request',(req,res)=>{
    if(limited('request:'+req.ip,30,60000))return res.status(429).json({error:'busy',message:'Wait a minute, then try again.'});
    const bearer=/^Bearer ([A-Za-z0-9_-]+)$/.exec(req.headers.authorization||'')?.[1]||null;
    const names=Array.isArray(req.body?.names)?req.body.names:typeof req.body?.name==='string'?[req.body.name]:[];
    const email=typeof req.body?.email==='string'?req.body.email.trim():'';
    if(email&&limited('request-email:'+req.ip,10,600000))return res.status(429).json({error:'busy',message:'Too many attempts. Wait a few minutes.'});
    try{res.json(auth.requests.create({names,challenge:req.body?.challenge,bearer,email}));}catch(e){send(res,e);}
  });
  app.post('/work/connect/request/poll',(req,res)=>{
    if(limited('request-poll:'+req.ip,120,60000))return res.status(429).json({error:'busy',message:'Wait a minute, then try again.'});
    try{res.json(auth.requests.poll(req.body?.request,req.body?.verifier));}catch(e){send(res,e);}
  });
  /** Requests waiting for this phone's owner. wait=1 holds the reply up to 25 s until something changes. */
  app.get('/work/phone/requests',requireSession,async(req,res)=>{
    const owner=req.phone.ownerId;let list=auth.requests.pending(owner);
    if(!list.length&&req.query.wait){await auth.requests.wait(owner,25000);list=auth.requests.pending(owner);}
    res.json({requests:list});
  });
  app.post('/work/phone/requests/decide',requireSession,(req,res)=>{
    const access=req.body?.allDevices===true?'all':Array.isArray(req.body?.devices)?req.body.devices.filter(x=>typeof x==='string').slice(0,20):[req.phone.deviceId];
    if(Array.isArray(access)&&access.length===0)return res.status(400).json({error:'choose_device',message:'Pick at least one device or allow all.'});
    try{res.json(auth.requests.decide(req.body?.request,req.phone.ownerId,req.body?.allow===true,access));}catch(e){send(res,e);}
  });

  // ---- Sign-in methods, managed from the app -------------------------------
  const ownerError=(res,e)=>res.status(400).json({error:'invalid_request',message:e.message});
  app.get('/work/phone/methods',requireSession,(req,res)=>res.json(owners.methods(req.phone.ownerId)));
  app.post('/work/phone/email/start',requireSession,async(req,res)=>{if(limited('email:'+req.phone.ownerId,10,600000))return res.status(429).json({error:'busy',message:'Too many codes. Wait a few minutes.'});try{res.json(await owners.startEmail(req.body?.email,{linkOwner:req.phone.ownerId}));}catch(e){ownerError(res,e);}});
  app.post('/work/phone/email/verify',requireSession,(req,res)=>{try{owners.verifyEmail(req.body?.email,req.body?.code,{expectedLinkOwner:req.phone.ownerId});res.json(owners.methods(req.phone.ownerId));}catch(e){ownerError(res,e);}});
  app.post('/work/phone/email/remove',requireSession,(req,res)=>{try{owners.removeEmail(req.phone.ownerId);res.json(owners.methods(req.phone.ownerId));}catch(e){ownerError(res,e);}});
  app.post('/work/phone/password',requireSession,async(req,res)=>{try{await owners.setPassword(req.phone.ownerId,req.body?.password,{currentPassword:req.body?.currentPassword,requireCurrent:true});res.json(owners.methods(req.phone.ownerId));}catch(e){ownerError(res,e);}});
  app.post('/work/phone/recovery',requireSession,(req,res)=>{try{res.json({codes:owners.newRecoveryCodes(req.phone.ownerId)});}catch(e){ownerError(res,e);}});
  app.post('/work/phone/passkeys/remove',requireSession,(req,res)=>{try{owners.removePasskey(req.phone.ownerId,String(req.body?.id||''));res.json(owners.methods(req.phone.ownerId));}catch(e){ownerError(res,e);}});
  app.post('/work/phone/account/delete',requireSession,async(req,res)=>{
    if(req.body?.confirm!=='DELETE')return res.status(400).json({error:'invalid_request',message:'Confirmation required.'});
    const ownerId=req.phone.ownerId;
    auth?.purgeOwner?.(ownerId);
    await nativeSignIn?.purgeOwner?.(ownerId);
    const cancels=[];
    for(const [k,v] of adds)if(v.ownerId===ownerId){adds.delete(k);cancels.push(v.pairing?.cancel?.().catch(()=>{}));}
    await Promise.all(cancels);
    const {grantIds}=await connections.removeAllForOwner(ownerId);
    auth?.revokeGrants?.(grantIds);
    artifacts?.removeForGrants?.(grantIds);
    for(const [k,v] of sessions)if(v.ownerId===ownerId)sessions.delete(k);
    owners.deleteOwner(ownerId);
    res.json({ok:true,deleted:true});
  });

  // ---- App updates ---------------------------------------------------------
  // The newest published APK. The app offers it and the phone's daemon installs it.
  app.get('/work/app/latest',(_req,res)=>{const latest=appReleases?.latest();if(!latest)return res.status(404).json({error:'no_release'});res.json(latest);});
  app.get('/work/app/download/:file',(req,res)=>{const file=appReleases?.file(req.params.file);if(!file)return res.sendStatus(404);res.type('application/vnd.android.package-archive').sendFile(file,{dotfiles:'allow'});});

  /** Everything the app shows: this device, all devices with live state, assistants and their device access. */
  app.get('/work/phone/account',requireSession,async(req,res)=>{
    try{
      const {ownerId,deviceId}=req.phone;
      const devices=await Promise.all(connections.devices(ownerId).map(d=>connections.probe(d)));
      const planInfo=owners.methods?.(ownerId);
      res.json({ownerId,thisDevice:deviceId,
        plan:planInfo?.plan||'free',
        limits:planInfo?.limits||null,
        devices:devices.map(d=>({id:d.id,name:d.name,status:d.status,online:!!d.online,needsReconnect:!!d.needsReconnect,model:d.model||null,lastSeen:d.lastSeen||null,thisDevice:d.id===deviceId})),
        assistants:connections.list(ownerId).map(g=>describeGrant(ownerId,g))});
    }catch(e){send(res,e);}
  });
  app.post('/work/phone/devices/rename',requireSession,(req,res)=>{try{res.json({device:connections.rename(req.phone.ownerId,String(req.body?.deviceId||''),String(req.body?.name||''))});}catch(e){send(res,e);}});
  app.post('/work/phone/devices/remove',requireSession,async(req,res)=>{
    const id=String(req.body?.deviceId||'');
    if(id===req.phone.deviceId)return res.status(400).json({error:'invalid_request',message:'Remove this phone from another device, or uninstall Backstage on it.'});
    try{await connections.remove(req.phone.ownerId,id);res.json({removed:id});}catch(e){send(res,e);}
  });
  // Adding a device: this phone shows a code the new device types into its own Backstage app.
  app.post('/work/phone/devices/add',requireSession,async(req,res)=>{
    try{
      prune();
      let ownAdds=0;for(const v of adds.values())if(v.ownerId===req.phone.ownerId)ownAdds++;
      if(ownAdds>=5||adds.size>=512)throw new ConnectionError('busy','Too many pending device setups. Finish or cancel one first.');
      const name=typeof req.body?.name==='string'?req.body.name:'';
      const pairing=await connections.begin({clientName:'Android Backstage',ownerId:req.phone.ownerId,name});
      const ticket=random();adds.set(ticket,{pairing,ownerId:req.phone.ownerId,expires:now()+600000});
      res.json({ticket,code:pairing.code,link:pairing.link,deviceName:pairing.deviceName,expiresAt:pairing.expiresAt});
    }catch(e){send(res,e);}
  });
  app.post('/work/phone/devices/add/poll',requireSession,async(req,res)=>{
    const row=adds.get(String(req.body?.ticket||''));
    if(!row||row.ownerId!==req.phone.ownerId)return res.status(404).json({error:'unknown_request',message:'Start again.'});
    if(row.expires<=now()){adds.delete(req.body.ticket);try{await row.pairing.cancel();}catch{}return res.status(410).json({error:'expired',message:'The code expired. Start again.'});}
    if(req.body?.cancel){adds.delete(req.body.ticket);try{await row.pairing.cancel();}catch{}return res.json({cancelled:true});}
    try{const id=await row.pairing.poll();if(!id)return res.json({pending:true});adds.delete(req.body.ticket);res.json({deviceId:id});}catch(e){adds.delete(req.body.ticket);send(res,e);}
  });
  app.post('/work/phone/assistants/access',requireSession,(req,res)=>{
    try{const access=req.body?.allDevices===true?'all':Array.isArray(req.body?.devices)?req.body.devices.filter(x=>typeof x==='string').slice(0,20):null;
      if(access===null||(Array.isArray(access)&&access.length===0))return res.status(400).json({error:'choose_device',message:'Pick at least one device or allow all.'});
      res.json({access:connections.setAccess(req.phone.ownerId,String(req.body?.grantId||''),access)});}catch(e){send(res,e);}
  });
  app.post('/work/phone/assistants/pause',requireSession,(req,res)=>{try{res.json({paused:connections.setPaused(req.phone.ownerId,String(req.body?.grantId||''),req.body?.paused===true)});}catch(e){send(res,e);}});
  app.post('/work/phone/assistants/stop',requireSession,async(req,res)=>{try{res.json({stopped:await connections.stopWork(req.phone.ownerId,String(req.body?.grantId||''))});}catch(e){send(res,e);}});
  app.post('/work/phone/assistants/remove',requireSession,(req,res)=>{try{const grantId=String(req.body?.grantId||'');connections.revokeOwned(req.phone.ownerId,grantId);auth?.revokeGrants?.([grantId]);artifacts?.removeForGrants?.([grantId]);res.json({removed:true});}catch(e){send(res,e);}});
  // OAuth approvals typed into the app.
  app.post('/work/phone/approvals/preview',requireSession,(req,res)=>{
    if(limited('approval:'+req.phone.ownerId,20,600000))return res.status(429).json({error:'busy',message:'Too many attempts. Wait a few minutes.'});
    try{const row=auth.approvals.preview(req.body?.code);res.json({...row,icon:assistantIcon(row.clientName,row.clientOrigin)});}catch(e){send(res,e);}
  });
  app.post('/work/phone/approvals/approve',requireSession,(req,res)=>{
    if(limited('approval:'+req.phone.ownerId,20,600000))return res.status(429).json({error:'busy',message:'Too many attempts. Wait a few minutes.'});
    const access=req.body?.allDevices===true?'all':Array.isArray(req.body?.devices)?req.body.devices.filter(x=>typeof x==='string').slice(0,20):[req.phone.deviceId];
    if(Array.isArray(access)&&access.length===0)return res.status(400).json({error:'choose_device',message:'Pick at least one device or allow all.'});
    try{res.json(auth.approvals.approve(req.body?.code,req.phone.ownerId,access));}catch(e){send(res,e);}
  });
  app.post('/work/phone/approvals/deny',requireSession,(req,res)=>{
    if(limited('approval:'+req.phone.ownerId,20,600000))return res.status(429).json({error:'busy',message:'Too many attempts. Wait a few minutes.'});
    res.json({denied:auth.approvals.deny(req.body?.code)});
  });
  return {
    async purgeOwner(ownerId){
      const cancels=[];
      for(const [k,v] of adds)if(v.ownerId===ownerId){adds.delete(k);cancels.push(v.pairing?.cancel?.().catch(()=>{}));}
      for(const [k,v] of sessions)if(v.ownerId===ownerId)sessions.delete(k);
      await Promise.all(cancels);
    }
  };
}
