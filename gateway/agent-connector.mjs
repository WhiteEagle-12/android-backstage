#!/usr/bin/env node
// Standalone account connector. No packages or phone link keys are required.
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import readline from 'node:readline';
import {execFileSync} from 'node:child_process';
import {fileURLToPath, pathToFileURL} from 'node:url';

const base='https://relay.android-backstage.com/work';
// The connection never opened, so the server never saw the request.
const NOT_SENT=new Set(['UND_ERR_CONNECT_TIMEOUT','ECONNREFUSED','ENOTFOUND','EAI_AGAIN','ENETUNREACH','EHOSTUNREACH','ENETDOWN']);
/** POST with up to three tries over brief network drops. Only requests that are safe to send twice are resent after a lost reply. */
export async function request(url,body,token,{fetchImpl=fetch,wait=ms=>new Promise(r=>setTimeout(r,ms))}={}) {
  const deadline=AbortSignal.timeout(body?.method==='tools/call'?660000:20000);
  // A tool write names a requestId, so the server returns its first outcome instead of running it again. Token refreshes are never resent.
  const replayable=body?.jsonrpc==='2.0'&&(body.method!=='tools/call'||typeof body.params?.arguments?.requestId==='string');
  for(let attempt=1;;attempt++){
    let response,code=null;
    try{
      response=await fetchImpl(url,{method:'POST',redirect:'error',headers:{'Content-Type':'application/json',Accept:'application/json, text/event-stream',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body),signal:deadline});
      if(response.ok)return response.status===202||response.status===204?null:await response.json();
    }catch(e){
      if(deadline.aborted||!(e instanceof TypeError))throw e;
      code=e.cause?.code||null;
      if(attempt>=3||!(replayable||NOT_SENT.has(code)))throw Object.assign(Error('Backstage could not be reached ('+(code||e.cause?.message||e.message)+').'),{code:code||'network'});
      response=null;
    }
    if(response&&!(replayable&&[502,503,504].includes(response.status)&&attempt<3))throw Object.assign(Error(response.status===401?'Connection expired. Connect this agent again.':'Backstage could not finish the request (HTTP '+response.status+').'),{status:response.status});
    await wait(attempt*1000);
  }
}
function save(file,data){
  fs.mkdirSync(path.dirname(file),{recursive:true,mode:0o700});
  const temp=file+'.'+crypto.randomBytes(8).toString('hex');
  fs.writeFileSync(temp,JSON.stringify(data),{mode:0o600,flag:'wx'});fs.renameSync(temp,file);
}
async function withLock(file,action){
  const lock=file+'.lock';
  for(let tries=0;tries<100;tries++){
    let acquired=false;
    try{fs.mkdirSync(lock,{mode:0o700});acquired=true;}
    catch(e){if(e.code!=='EEXIST')throw e;}
    if(acquired){
      fs.writeFileSync(path.join(lock,'pid'),String(process.pid),{mode:0o600});
      try{return await action();}finally{fs.rmSync(lock,{recursive:true,force:true});}
    }
    // Recover a lock left by a dead connector. Never remove a live owner's lock.
    try{const pid=Number(fs.readFileSync(path.join(lock,'pid'),'utf8'));if(Number.isInteger(pid)&&pid>0){try{process.kill(pid,0);}catch(e){if(e.code==='ESRCH')fs.rmSync(lock,{recursive:true,force:true});}}}catch{}
    await new Promise(r=>setTimeout(r,100));
  }
  throw Error('Another Backstage connection is signing in. Try again shortly.');
}
export async function connect({code,name,folder,endpoint=base,post=request,output=console.error}) {
  const pendingFile=path.join(folder,'pending.json');
  const pending=fs.existsSync(pendingFile)?JSON.parse(fs.readFileSync(pendingFile,'utf8')):{code,name,endpoint,verifier:crypto.randomBytes(32).toString('base64url')};
  if(pending.code!==code||pending.name!==name||pending.endpoint!==endpoint)throw Error('This saved request belongs to a different connection.');
  const verifier=pending.verifier;save(pendingFile,pending);
  const claim=await post(endpoint+'/connect/claim',{code,clientName:name,challenge:crypto.createHash('sha256').update(verifier).digest('base64url')});
  if(!claim.approved)output('Check digits: '+claim.check+'. In Backstage on your phone, check these digits and tap Allow.');
  const until=Date.now()+Math.min(claim.expiresIn,600)*1000;
  while(Date.now()<until){
    const result=await post(endpoint+'/connect/poll',{ticket:claim.ticket,verifier});
    if(result.denied)throw Error('The phone denied this connection.');
    if(result.access_token){
      const file=path.join(folder,'account.json');save(file,{...result,base:endpoint,expires:Date.now()+result.expires_in*1000});
      fs.rmSync(pendingFile,{force:true});
      return file;
    }
    await new Promise(r=>setTimeout(r,2000));
  }
  throw Error('The invitation expired. Copy a new setup message in Backstage.');
}
export const HOSTS=['cursor','codex','claude-code','grok','opencode','gemini','antigravity'];
/** Add a stdio entry under `name` to a JSON settings file without disturbing other entries. */
function addJsonServer(file,key,name,entry){
  let config={};
  if(fs.existsSync(file)){
    try{config=JSON.parse(fs.readFileSync(file,'utf8'));}catch{throw Error('The settings in '+file+' could not be read. Fix that file, then run install again with the saved account.');}
    if(!config||typeof config!=='object'||Array.isArray(config)||(config[key]&&(typeof config[key]!=='object'||Array.isArray(config[key]))))throw Error('The settings in '+file+' have an invalid format.');
    fs.copyFileSync(file,file+'.backstage-backup');
  }
  config[key]={...config[key],[name]:entry};save(file,config);
}
/** OpenCode allows comments, so edit the text: replace our entry or add it under "mcp". */
function addOpenCodeServer(file,name,command){
  const entry=JSON.stringify({type:'local',command,enabled:true,timeout:660000});
  if(!fs.existsSync(file)){save(file,{$schema:'https://opencode.ai/config.json',mcp:{[name]:JSON.parse(entry)}});return;}
  let text=fs.readFileSync(file,'utf8');fs.copyFileSync(file,file+'.backstage-backup');
  const span=(source,open)=>{let depth=0,str=false;for(let i=open;i<source.length;i++){const c=source[i];if(str){if(c==='\\'){i++;continue;}if(c==='"')str=false;continue;}if(c==='"')str=true;else if(c==='{')depth++;else if(c==='}'&&--depth===0)return i;}return -1;};
  const keyAt=text.indexOf(JSON.stringify(name)+':')>=0?text.indexOf(JSON.stringify(name)+':'):text.indexOf(JSON.stringify(name)+' :');
  if(keyAt>=0){const open=text.indexOf('{',keyAt),close=span(text,open);if(close<0)throw Error('OpenCode settings could not be read.');text=text.slice(0,open)+entry+text.slice(close+1);}
  else{const mcp=/"mcp"\s*:\s*\{/.exec(text);if(mcp)text=text.slice(0,mcp.index+mcp[0].length)+'\n    '+JSON.stringify(name)+': '+entry+','+text.slice(mcp.index+mcp[0].length);
    else{const first=text.indexOf('{');if(first<0)throw Error('OpenCode settings could not be read.');text=text.slice(0,first+1)+'\n  "mcp": {'+JSON.stringify(name)+': '+entry+'},'+text.slice(first+1);}}
  fs.writeFileSync(file,text,{mode:0o600});
}
export function install(host,account,{home=os.homedir(),exec=execFileSync,node=process.execPath,script=path.join(path.dirname(account),'agent.mjs'),name:fixedName=null,replace=false}={}) {
  if(!HOSTS.includes(host))throw Error('Choose '+HOSTS.join(', ')+'.');
  const name=fixedName||'android-backstage-'+path.basename(path.dirname(account));
  const args=[script,'serve','--account',account];
  const tryExec=(program,command)=>{try{exec(program,command,{stdio:['ignore','pipe','pipe'],timeout:30000});}catch{}};
  if(host==='cursor')addJsonServer(path.join(home,'.cursor','mcp.json'),'mcpServers',name,{command:node,args});
  else if(host==='gemini')addJsonServer(path.join(home,'.gemini','settings.json'),'mcpServers',name,{command:node,args});
  else if(host==='antigravity'){
    // The Antigravity app reads ~/.gemini/antigravity; T3 Code keeps a private profile per provider instance.
    const files=[path.join(home,'.gemini','antigravity','mcp_config.json')];
    const t3=path.join(home,'.t3','userdata','providers','antigravity');
    if(fs.existsSync(t3))for(const id of fs.readdirSync(t3))if(fs.existsSync(path.join(t3,id,'config')))files.push(path.join(t3,id,'config','mcp_config.json'));
    for(const file of files)addJsonServer(file,'mcpServers',name,{command:node,args});
  }
  else if(host==='opencode'){const dir=path.join(home,'.config','opencode');const file=['opencode.jsonc','opencode.json'].map(f=>path.join(dir,f)).find(f=>fs.existsSync(f))||path.join(dir,'opencode.json');addOpenCodeServer(file,name,[node,...args]);}
  else if(host==='grok'){
    const grok=[path.join(home,'.grok','bin','grok'),'grok'].find(g=>g==='grok'||fs.existsSync(g));
    if(replace)tryExec(grok,['mcp','remove',name]);
    exec(grok,['mcp','add','-s','user',name,node,'--',...args],{stdio:['ignore','pipe','pipe'],timeout:30000});
  }
  else {
    const program=host==='codex'?'codex':'claude';
    if(replace)tryExec(program,program==='codex'?['mcp','remove',name]:['mcp','remove',name,'--scope','user']);
    // These CLIs preserve the host's other servers and settings. Never use a shell.
    const command=host==='codex'?['mcp','add',name,'--',node,...args]:['mcp','add','--scope','user','--transport','stdio',name,'--',node,...args];
    exec(program,command,{stdio:['ignore','pipe','pipe'],timeout:30000});
  }
  return name;
}
/** Agents installed on this computer, by host id and the name shown in Backstage. */
export function detectHosts({home=os.homedir(),has=command=>{try{if(process.platform==='win32')execFileSync('where',[command],{stdio:'ignore'});else execFileSync('sh',['-c','command -v "$1"','sh',command],{stdio:'ignore'});return true;}catch{return false;}}}={}) {
  const exists=p=>fs.existsSync(path.join(home,p));
  return [
    ['claude-code','Claude Code',has('claude')],
    ['codex','Codex',has('codex')],
    ['cursor','Cursor',exists('.cursor')],
    ['grok','Grok',exists('.grok/bin/grok')||has('grok')],
    ['opencode','OpenCode',has('opencode')||exists('.config/opencode')],
    ['gemini','Gemini CLI',has('gemini')],
    ['antigravity','Antigravity',exists('.gemini/antigravity')||exists('.t3/userdata/providers/antigravity')],
  ].filter(([, , found])=>found).map(([host,name])=>({host,name}));
}
/** The saved account with a current access token, refreshing it under the account lock when needed. */
async function freshState(file,post=request){
  return withLock(file,async()=>{
    let current=JSON.parse(fs.readFileSync(file,'utf8'));
    if(current.expires&&Date.now()>=current.expires-60000&&current.refresh_token){
      const fresh=await post(current.base+'/token',{grant_type:'refresh_token',client_id:current.client_id,refresh_token:current.refresh_token,resource:current.base+'/mcp'});
      current={...current,...fresh,expires:Date.now()+fresh.expires_in*1000};save(file,current);
    }
    return current;
  });
}
export async function verify(account,{post=request}={}) {
  const state=await freshState(account,post);
  await post(state.base+'/mcp',{jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:'2024-11-05',capabilities:{},clientInfo:{name:'Backstage setup',version:'1'}}},state.access_token);
  await post(state.base+'/mcp',{jsonrpc:'2.0',method:'notifications/initialized'},state.access_token);
  let requestId=2;
  const call=async arguments_=>{
    const reply=await post(state.base+'/mcp',{jsonrpc:'2.0',id:requestId++,method:'tools/call',params:{name:'android_devices',arguments:arguments_}},state.access_token);
    if(reply?.error)throw Error('Phone check failed. Open Backstage on the phone and resume access, then run verify again.');
    const result=reply?.result;
    let body=result?.structuredContent;
    if(!body)try{body=JSON.parse(result?.content?.find(c=>c.type==='text')?.text);}catch{}
    if(result?.isError||body?.outcome!=='succeeded')throw Error('The agent is linked, but the phone did not answer. Open Backstage and resume access, then run verify again.');
    return body;
  };
  const listed=await call({});
  const online=Array.isArray(listed.devices)?listed.devices.filter(d=>d.online===true&&!d.needsReconnect&&typeof d.id==='string'&&d.id):[];
  if(!online.length)throw Error('The agent is linked, but no phone is online. Open Backstage and resume access, then run verify again.');
  const models=[];
  for(const device of online){
    const identity=await call({device:device.id,detail:'debug'});
    if(identity.connected!==true||typeof identity.model!=='string'||!identity.model.trim())throw Error('The agent is linked, but the phone did not answer. Open Backstage and resume access, then run verify again.');
    models.push(identity.model.trim());
  }
  return models.join(', ');
}
export async function serve(file,{post=request}={}){
  let state=JSON.parse(fs.readFileSync(file,'utf8'));
  const endpoint=new URL(state.base);
  if(endpoint.protocol!=='https:'||endpoint.username||endpoint.password)throw Error('Invalid saved server.');
  const input=readline.createInterface({input:process.stdin});
  // Serialize messages so token rotation cannot race itself within this process.
  for await(const line of input){
    let message;
    try{
      message=JSON.parse(line);
      state=await withLock(file,async()=>{
        let current=JSON.parse(fs.readFileSync(file,'utf8'));
        if(current.base!==endpoint.href.replace(/\/$/,''))throw Error('The saved server changed. Restart the connection.');
        if(Date.now()>=current.expires-60000){
          const fresh=await post(current.base+'/token',{grant_type:'refresh_token',client_id:current.client_id,refresh_token:current.refresh_token,resource:current.base+'/mcp'});
          current={...current,...fresh,expires:Date.now()+fresh.expires_in*1000};save(file,current);
        }
        return current;
      });
      const result=await post(state.base+'/mcp',message,state.access_token);
      if(result&&message.id!==undefined)process.stdout.write(JSON.stringify(result)+'\n');
    }catch(error){
      if(message?.id!==undefined)process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:message.id,error:{code:-32000,message:error.message}})+'\n');
      else console.error('Backstage: '+error.message);
    }
  }
}
/** A working access token from any connection this computer already has, so new requests reach the owner directly. */
export async function existingToken({home=os.homedir(),post=request}={}) {
  const root=path.join(home,'.config','android-backstage','agents');
  if(!fs.existsSync(root))return null;
  for(const id of fs.readdirSync(root)){
    const file=path.join(root,id,'account.json');if(!fs.existsSync(file))continue;
    try{
      return await withLock(file,async()=>{
        let current=JSON.parse(fs.readFileSync(file,'utf8'));
        if(Date.now()>=current.expires-60000){const fresh=await post(current.base+'/token',{grant_type:'refresh_token',client_id:current.client_id,refresh_token:current.refresh_token,resource:current.base+'/mcp'});current={...current,...fresh,expires:Date.now()+fresh.expires_in*1000};save(file,current);}
        return current.access_token;
      });
    }catch{}
  }
  return null;
}
/** A copy at a fixed path, so plugins can start `serve --name` without knowing account folders. */
function keepStableCopy(home=os.homedir()){try{const target=path.join(home,'.config','android-backstage','agent.mjs');fs.mkdirSync(path.dirname(target),{recursive:true,mode:0o700});fs.copyFileSync(fileURLToPath(import.meta.url),target);fs.chmodSync(target,0o600);}catch{}}
/** The saved account for an agent name, e.g. "Claude Code". */
export function accountNamed(name,{home=os.homedir()}={}){
  const root=path.join(home,'.config','android-backstage','agents');if(!fs.existsSync(root))return null;
  for(const id of fs.readdirSync(root).sort()){const file=path.join(root,id,'account.json');try{if(JSON.parse(fs.readFileSync(file,'utf8')).name===name)return file;}catch{}}
  return null;
}
function openBrowser(url){for(const opener of [['xdg-open'],['open'],['cmd','/c','start','']])try{execFileSync(opener[0],[...opener.slice(1),url],{stdio:'ignore',timeout:5000});return true;}catch{}return false;}
/**
 * Ask the owner to approve one or more agents. With an existing connection the
 * request appears on the owner's phones; otherwise the owner approves on a web
 * page after signing in. Saves one account per agent and returns their files.
 */
export async function requestAccess({names,endpoint=base,home=os.homedir(),output=console.error,post=request,token,email}={}) {
  const verifier=crypto.randomBytes(32).toString('base64url');
  const bearer=token===undefined?await existingToken({home,post}):token;
  const response=await fetch(endpoint+'/connect/request',{method:'POST',headers:{'Content-Type':'application/json',...(bearer?{Authorization:'Bearer '+bearer}:{})},body:JSON.stringify({names,challenge:crypto.createHash('sha256').update(verifier).digest('base64url'),...(email?{email}:{})}),signal:AbortSignal.timeout(20000)});
  const made=await response.json().catch(()=>({}));
  if(!response.ok)throw Error(made.message||'Backstage could not start the request (HTTP '+response.status+').');
  output('Check digits: '+made.check+'.');
  if(made.ownerKnown)output('Tap Allow in the Backstage notification on your phone.');
  else{output('Send to your phone here (no browser sign-in needed): '+made.approveUrl);openBrowser(made.approveUrl);}
  const until=Date.now()+600000;
  while(Date.now()<until){
    const result=await post(endpoint+'/connect/request/poll',{request:made.request,verifier});
    if(result.denied)throw Error('The request was denied.');
    if(result.connections){
      return result.connections.map(c=>{const folder=path.join(home,'.config','android-backstage','agents',crypto.randomBytes(8).toString('hex'));
        const file=path.join(folder,'account.json');save(file,{name:c.name,access_token:c.access_token,token_type:c.token_type,expires_in:c.expires_in,refresh_token:c.refresh_token,scope:c.scope,client_id:c.client_id,base:endpoint,expires:Date.now()+c.expires_in*1000});
        const script=path.join(folder,'agent.mjs');fs.copyFileSync(fileURLToPath(import.meta.url),script);fs.chmodSync(script,0o600);
        keepStableCopy(home);
        return {name:c.name,account:file};});
    }
    await new Promise(r=>setTimeout(r,2000));
  }
  throw Error('The request expired. Run connect again.');
}
function verifyHint(host,name){
  if(host==='claude-code')return `Verify MCP automatically: claude -p "Call the android_devices MCP tool and return the connected phone name and model." --allowedTools "mcp__${name}__*"`;
  if(host==='codex')return 'Verify MCP automatically: codex exec "Call the android_devices MCP tool and return the connected phone name and model."';
  if(host==='gemini')return 'Verify MCP automatically: gemini -p "Call the android_devices MCP tool and return the connected phone name and model."';
  return 'MCP server verified via agent.mjs; future sessions of this host will load it automatically.';
}
async function main(){
  const [command,...args]=process.argv.slice(2);
  const option=name=>{const i=args.indexOf('--'+name);return i<0?null:args[i+1];};
  if(command==='serve'){
    const account=option('account')||(option('name')&&accountNamed(option('name')));
    if(!account)throw Error('No Backstage connection named "'+option('name')+'" on this computer. Run: node agent.mjs connect --name "'+option('name')+'" --host HOST');
    return serve(account);
  }
  if(command==='install') {console.log('Installed '+install(option('host'),option('account'))+'. Reload the agent’s tools, then ask it to check the phone.');return;}
  if(command==='verify') {console.log('Phone responded: '+await verify(option('account')));return;}
  const resumed=option('resume')?JSON.parse(fs.readFileSync(option('resume'),'utf8')):null;
  const host=option('host');
  if(host&&!HOSTS.includes(host))throw Error('Choose '+HOSTS.join(', ')+'.');
  // No invitation: ask the owner directly. --all connects every agent found on this computer.
  if(command==='connect'&&!resumed&&!option('code')&&(args.includes('--all')||option('name'))){
    const targets=args.includes('--all')?detectHosts():[{host,name:option('name')}];
    if(!targets.length)throw Error('No supported agent was found on this computer.');
    console.error('Connecting: '+targets.map(t=>t.name).join(', '));
    const accounts=await requestAccess({names:targets.map(t=>t.name),email:option('email')||undefined});
    for(const [i,target] of targets.entries()){
      if(!target.host){console.log(JSON.stringify({mcpServers:{'android-backstage':{command:process.execPath,args:[path.join(path.dirname(accounts[i].account),'agent.mjs'),'serve','--account',accounts[i].account]}}},null,2));continue;}
      try{install(target.host,accounts[i].account,{name:'android-backstage',replace:true});console.log('Connected '+target.name+'.');}
      catch(e){console.log(target.name+' was approved but could not be configured: '+e.message+' Its account file is '+accounts[i].account);}
    }
    try{console.log('Phone responded: '+await verify(accounts[0].account));}catch(e){console.log(e.message);}
    for(const target of targets)if(target.host)console.log(verifyHint(target.host,'android-backstage'));
    return;
  }
  if(command!=='connect'||(!resumed&&(!option('code')||!option('name'))))throw Error('Use connect --all, connect --name AGENT_NAME --host HOST, connect --code INVITATION --name AGENT_NAME --host HOST, connect --resume FILE --host HOST, or serve --account FILE.');
  if(host==='codex'||host==='claude-code')execFileSync(host==='codex'?'codex':'claude',['--version'],{stdio:'pipe',timeout:10000});
  const folder=resumed?path.dirname(path.resolve(option('resume'))):path.join(os.homedir(),'.config','android-backstage','agents',crypto.randomBytes(8).toString('hex'));
  console.error('If setup is interrupted, resume this request with: connect --resume '+path.join(folder,'pending.json')+(host?' --host '+host:''));
  const account=await connect({code:resumed?.code||option('code'),name:resumed?.name||option('name'),endpoint:resumed?.endpoint||base,folder});
  const script=path.join(folder,'agent.mjs');fs.copyFileSync(fileURLToPath(import.meta.url),script);fs.chmodSync(script,0o600);
  console.error('Connection saved. For a retry, use this account file: '+account);
  if(host) {
    const name=install(host,account);
    console.log('Installed '+name+'.');
    console.log('Phone responded: '+await verify(account));
    console.log(verifyHint(host,name));
    return;
  }
  console.log(JSON.stringify({mcpServers:{'android-backstage':{command:process.execPath,args:[script,'serve','--account',account]}}},null,2));
}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href)main().catch(e=>{console.error(e.message);process.exitCode=1;});
