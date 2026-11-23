import crypto from 'node:crypto';
import {challengeCookie} from './owners.mjs';
import {validDeviceName} from './connections.mjs';

// The key used for the APK distributed by Backstage. Add a production signing
// key here before switching APK signatures; never accept a client-supplied key.
export const androidFingerprint = 'CD:65:E2:0E:FB:6E:AF:46:CB:97:01:77:D3:F0:DD:75:1E:A5:92:F2:74:A4:06:E4:31:97:20:27:52:E0:68:4D';
export const androidOrigins = ['android:apk-key-hash:' + Buffer.from(androidFingerprint.replaceAll(':',''),'hex').toString('base64url')];
export const assetLinks = [{relation:['delegate_permission/common.handle_all_urls','delegate_permission/common.get_login_creds'],target:{namespace:'android_app',package_name:'com.androidbackstage.app',sha256_cert_fingerprints:[androidFingerprint]}}];

export function nativeSignInRoutes(app,{owners,connections,now=Date.now}) {
  const pending=new Map(), attempts=new Map(), signIns=new Map();
  app.get('/.well-known/assetlinks.json',(_req,res)=>res.json(assetLinks));
  app.use('/work/native',(req,res,next)=>{
    for(const [k,v] of signIns)if(v.expires<=now())signIns.delete(k);
    for(const [k,v] of attempts)if(v.until<=now())attempts.delete(k);
    for(const [k,v] of pending)if(v.expires<=now()){pending.delete(k);v.pairing?.cancel().catch(()=>{});}
    const row=attempts.get(req.ip)||{count:0,until:now()+60000};row.count++;attempts.set(req.ip,row);
    if(row.count>60||attempts.size>1000)return res.status(429).json({message:'Wait a minute, then try again.'});
    next();
  });
  app.post('/work/native/options',async(req,res)=>{
    try {
      const installation=req.body?.installation;
      if(!/^[A-Za-z0-9_-]{43}$/.test(installation||'')||signIns.size>=1000)throw Error('Invalid installation');
      let ticket;
      const options=await owners.options({headers:{}},{append(_k,v){if(v.startsWith(challengeCookie+'='))ticket=v.split(';')[0].slice(challengeCookie.length+1);}},req.body?.mode,true);
      signIns.set(ticket,{installation,expires:now()+300000});
      res.json({ticket,options});
    } catch {res.status(400).json({message:'Could not start sign-in. Try again.'});}
  });
  app.post('/work/native/verify',async(req,res)=>{
    try {
      if(typeof req.body?.ticket!=='string'||pending.size>=100)throw Error('Invalid request');
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
    const ticket=crypto.randomBytes(32).toString('base64url');
    const name=validDeviceName(deviceName)?String(deviceName).trim():'phone';
    const pairing=await connections.begin({ownerId,clientName:'Android Backstage',name});
    const row={ownerId,installation,pairing,expires:now()+600000};pending.set(ticket,row);
    return {ticket,code:pairing.code,expiresAt:new Date(row.expires).toISOString()};
  }
  const installationOk=v=>/^[A-Za-z0-9_-]{43}$/.test(v||'');
  app.post('/work/native/email/start',async(req,res)=>{
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
  app.get('/work/native/methods',(_req,res)=>res.json({email:owners.emailAvailable(),passkey:true,recovery:true}));
  app.post('/work/native/enroll',async(req,res)=>{
    const row=pending.get(req.body?.ticket);
    if(!row)return res.status(410).json({message:'Setup expired. Sign in again.'});
    if(row.deviceId)return res.json({deviceId:row.deviceId});
    if(row.busy)return res.json({pending:true});
    row.busy=true;
    try {row.deviceId=await row.pairing.poll();res.json(row.deviceId?{deviceId:row.deviceId}:{pending:true});}
    catch {res.status(400).json({message:'Could not connect this phone. Sign in again.'});}
    finally {row.busy=false;}
  });
}
