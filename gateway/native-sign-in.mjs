import crypto from 'node:crypto';
import {challengeCookie} from './owners.mjs';
import {validDeviceName} from './connections.mjs';

// Production release APK signing key fingerprint; never accept a client-supplied key.
export const androidFingerprint = 'A1:70:4D:77:64:BE:28:7A:1B:8C:C2:C6:2F:99:57:E1:9A:F2:E3:C2:55:8C:81:05:C0:97:5A:79:20:BD:69:56';
export const androidOrigins = ['android:apk-key-hash:' + Buffer.from(androidFingerprint.replaceAll(':',''),'hex').toString('base64url')];
export const assetLinks = [{relation:['delegate_permission/common.handle_all_urls','delegate_permission/common.get_login_creds'],target:{namespace:'android_app',package_name:'com.androidbackstage.app',sha256_cert_fingerprints:[androidFingerprint]}}];

export function nativeSignInRoutes(app,{owners,connections,now=Date.now}) {
  const pending=new Map(), attempts=new Map(), emailAttempts=new Map(), signIns=new Map();
  const evict=(map,max)=>{while(map.size>=max){const k=map.keys().next().value;if(k===undefined)break;map.delete(k);}};
  const hitLimit=(map,key,max,windowMs)=>{
    const t=now();
    let row=map.get(key);
    if(!row||row.until<=t)row={count:0,until:t+windowMs};
    row.count++;
    if(!map.has(key))evict(map,10000);
    map.set(key,row);
    return row.count>max;
  };
  app.get('/.well-known/assetlinks.json',(_req,res)=>res.json(assetLinks));
  app.use('/work/native',(req,res,next)=>{
    for(const [k,v] of signIns)if(v.expires<=now())signIns.delete(k);
    for(const [k,v] of attempts)if(v.until<=now())attempts.delete(k);
    for(const [k,v] of emailAttempts)if(v.until<=now())emailAttempts.delete(k);
    for(const [k,v] of pending)if(v.expires<=now()){pending.delete(k);v.pairing?.cancel().catch(()=>{});}
    if(hitLimit(attempts,req.ip,60,60000))return res.status(429).json({message:'Wait a minute, then try again.'});
    next();
  });
  app.post('/work/native/options',async(req,res)=>{
    try {
      const installation=req.body?.installation;
      if(!/^[A-Za-z0-9_-]{43}$/.test(installation||''))throw Error('Invalid installation');
      evict(signIns,2000);
      let ticket;
      const options=await owners.options({headers:{}},{append(_k,v){if(v.startsWith(challengeCookie+'='))ticket=v.split(';')[0].slice(challengeCookie.length+1);}},req.body?.mode,true);
      signIns.set(ticket,{installation,expires:now()+300000});
      res.json({ticket,options});
    } catch {res.status(400).json({message:'Could not start sign-in. Try again.'});}
  });
  app.post('/work/native/verify',async(req,res)=>{
    try {
      if(typeof req.body?.ticket!=='string')throw Error('Invalid request');
      const request=signIns.get(req.body.ticket);signIns.delete(req.body.ticket);
      if(!request)throw Error('Sign-in expired');
      const ownerId=await owners.verify({headers:{cookie:challengeCookie+'='+req.body.ticket}},{append(){}},req.body.response,true);
      res.json(await enrol(ownerId,request.installation,req.body?.deviceName));
    } catch {res.status(400).json({message:'Could not finish sign-in. Try again, or sign in with an emailed code.'});}
  });
  /**
   * Any sign-in method adds this device to the account: the same enrolment
   * follows a passkey, an emailed code or a recovery code. The device keeps the
   * name the app suggests (its model) unless the owner renames it.
   */
  async function enrol(ownerId,installation,deviceName){
    // A fresh sign-in replaces only this installation's unfinished request.
    // The app resumes its saved ticket after interruption instead of signing in again.
    for(const [id,row] of pending)if(row.ownerId===ownerId&&row.installation===installation&&!row.deviceId){pending.delete(id);await row.pairing.cancel().catch(()=>{});}
    let ownerPending=0;
    for(const row of pending.values())if(row.ownerId===ownerId)ownerPending++;
    if(ownerPending>=5)throw Error('Too many pending setups for this account.');
    evict(pending,1000);
    const ticket=crypto.randomBytes(32).toString('base64url');
    const name=validDeviceName(deviceName)?String(deviceName).trim():'phone';
    const pairing=await connections.begin({ownerId,clientName:'Android Backstage',name});
    const row={ownerId,installation,pairing,expires:now()+600000};pending.set(ticket,row);
    return {ticket,code:pairing.code,expiresAt:new Date(row.expires).toISOString()};
  }
  const installationOk=v=>/^[A-Za-z0-9_-]{43}$/.test(v||'');
  app.post('/work/native/signup/start',async(req,res)=>{
    if(hitLimit(emailAttempts,'signup:'+req.ip,8,600000))return res.status(429).json({message:'Too many attempts. Wait a few minutes.'});
    try{if(!installationOk(req.body?.installation))throw Error('Update Backstage and try again.');res.json(await owners.startSignUp(req.body?.email,req.body?.password));}
    catch(e){res.status(400).json({message:e.message});}
  });
  app.post('/work/native/signup/verify',async(req,res)=>{
    try{if(!installationOk(req.body?.installation))throw Error('Update Backstage and try again.');const ownerId=owners.verifySignUp(req.body?.email,req.body?.code);res.json(await enrol(ownerId,req.body.installation,req.body?.deviceName));}
    catch(e){res.status(400).json({message:e.message});}
  });
  app.post('/work/native/login',async(req,res)=>{
    if(hitLimit(emailAttempts,'login:'+req.ip,20,600000))return res.status(429).json({message:'Too many attempts. Wait a few minutes.'});
    try{if(!installationOk(req.body?.installation))throw Error('Update Backstage and try again.');const ownerId=await owners.verifyPassword(req.body?.email,req.body?.password);res.json(await enrol(ownerId,req.body.installation,req.body?.deviceName));}
    catch(e){res.status(400).json({message:e.message});}
  });
  app.post('/work/native/password/reset/start',async(req,res)=>{
    if(hitLimit(emailAttempts,'reset:'+req.ip,8,600000))return res.status(429).json({message:'Too many attempts. Wait a few minutes.'});
    try{if(!installationOk(req.body?.installation))throw Error('Update Backstage and try again.');res.json(await owners.startPasswordReset(req.body?.email));}
    catch(e){res.status(400).json({message:e.message});}
  });
  app.post('/work/native/password/reset/verify',async(req,res)=>{
    try{if(!installationOk(req.body?.installation))throw Error('Update Backstage and try again.');const ownerId=await owners.verifyPasswordReset(req.body?.email,req.body?.code,req.body?.password);res.json(await enrol(ownerId,req.body.installation,req.body?.deviceName));}
    catch(e){res.status(400).json({message:e.message});}
  });
  app.post('/work/native/email/start',async(req,res)=>{
    if(hitLimit(emailAttempts,'email:'+req.ip,8,600000))return res.status(429).json({message:'Too many codes were requested. Wait a few minutes.'});
    try{if(!installationOk(req.body?.installation))throw Error('Update Backstage and try again.');res.json(await owners.startEmail(req.body?.email));}
    catch(e){res.status(400).json({message:e.message});}
  });
  app.post('/work/native/email/verify',async(req,res)=>{
    try{if(!installationOk(req.body?.installation))throw Error('Update Backstage and try again.');const ownerId=owners.verifyEmail(req.body?.email,req.body?.code);res.json(await enrol(ownerId,req.body.installation,req.body?.deviceName));}
    catch(e){res.status(400).json({message:e.message});}
  });
  app.post('/work/native/recovery',async(req,res)=>{
    try{if(!installationOk(req.body?.installation))throw Error('Update Backstage and try again.');const ownerId=owners.verifyRecovery(req.body?.code);res.json(await enrol(ownerId,req.body.installation,req.body?.deviceName));}
    catch(e){res.status(400).json({message:e.message});}
  });
  app.get('/work/native/methods',(_req,res)=>res.json({password:true,email:owners.emailAvailable(),passkey:true,recovery:true}));
  app.post('/work/native/enroll',async(req,res)=>{
    const row=pending.get(req.body?.ticket);
    if(!row)return res.status(410).json({message:'Setup expired. Sign in again.'});
    if(!owners.exists(row.ownerId)){pending.delete(req.body?.ticket);await row.pairing?.cancel?.().catch(()=>{});return res.status(410).json({message:'Setup expired. Sign in again.'});}
    if(row.deviceId)return res.json({deviceId:row.deviceId});
    if(row.busy)return res.json({pending:true});
    row.busy=true;
    try {row.deviceId=await row.pairing.poll();res.json(row.deviceId?{deviceId:row.deviceId}:{pending:true});}
    catch {res.status(400).json({message:'Could not connect this phone. Sign in again.'});}
    finally {row.busy=false;}
  });
  return {
    async purgeOwner(ownerId){
      const cancels=[];
      for(const [id,row] of pending){if(row.ownerId===ownerId){pending.delete(id);cancels.push(row.pairing?.cancel?.().catch(()=>{}));}}
      await Promise.all(cancels);
    }
  };
}
