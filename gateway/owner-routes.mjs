import crypto from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {assistantIcon} from './assistants.mjs';
import {page,escape,scriptJSON} from './pages.mjs';
import {connectionPage} from './connect-page.mjs';
import {validDeviceName,ConnectionError} from './connections.mjs';

export function ownerRoutes(app,{owners,connections,base,auth,artifacts=null,nativeSignIn=null,now=Date.now}) {
  const origin=new URL(base).origin,pending=new Map();
  const nonce=res=>{const n=crypto.randomBytes(24).toString('base64url');res.set('Content-Security-Policy',`default-src 'none'; script-src 'nonce-${n}' 'self'; style-src 'unsafe-inline'; img-src https://android-backstage.com; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`);return n;};
  const safeReturn=value=>typeof value==='string'&&(/^\/work\/(account|enroll)(\?[A-Za-z0-9_=&%.-]{0,120})?$/.test(value)||/^\/work\/connect\/approve\?request=req_[a-f0-9]{32}$/.test(value)||value.startsWith('/work/authorize?'))?value:'/work/account';
  const signedIn=(req,res)=>{const id=owners.owner(req);if(!id){if(req.method==='GET')res.redirect(303,'/work/sign-in?return_to='+encodeURIComponent(safeReturn(req.originalUrl)));else res.status(401).json({error:'Sign in to Backstage again.'});}return id;};
  const problem=(res,e,status=400)=>res.status(e instanceof ConnectionError?({unknown_device:404,access_revoked:404,busy:429}[e.code]||status):status).json({error:e instanceof ConnectionError?e.message:'Could not finish. Sign in and try again.'});
  app.use(['/work/passkeys','/work/enroll','/work/account','/work/email','/work/recovery','/work/signup','/work/login','/work/password'],(req,res,next)=>{if(req.method==='POST'&&req.headers.origin!==origin)return res.status(403).json({error:'Use the Backstage page to continue.'});next();});
  app.get('/work/passkeys.js',(_req,res)=>res.type('js').sendFile(path.resolve(fileURLToPath(new URL('./node_modules/@simplewebauthn/browser/dist/bundle/index.umd.min.js',import.meta.url))),{dotfiles:'allow'}));
  // Per-address and per-IP limits sit in the account store and here; codes expire in 10 minutes.
  const tries=new Map();
  const limited=(key,max)=>{const t=now();let row=tries.get(key);if(!row||row.until<=t)row={count:0,until:t+600000};row.count++;if(!tries.has(key)&&tries.size>=5000){for(const [k,v] of tries)if(v.until<=t)tries.delete(k);if(tries.size>=5000){const oldest=tries.keys().next().value;if(oldest!==undefined)tries.delete(oldest);}}tries.set(key,row);return row.count>max;};
  const renderSignIn=(req,res,defaultMode='login')=>{
    const n=nonce(res),target=safeReturn(req.query.return_to),adding=!!owners.owner(req),email=owners.emailAvailable();
    const initialMode=req.query.mode==='signup'||defaultMode==='signup'?'signup':'login';
    const body=adding?`<h1>Add a passkey</h1><p>Save another passkey so you can sign in on this device with your fingerprint or screen lock.</p><button id="register">Save a passkey</button>`
      :`<h1>Android Backstage</h1><p id="subtitle">${initialMode==='signup'?'Create your account to link your phones and assistants.':'Log in to manage your devices and assistants.'}</p>
<div class="tabs" role="tablist" aria-label="Account"><button type="button" role="tab" id="tab-login" aria-selected="${initialMode==='login'}">Log in</button><button type="button" role="tab" id="tab-signup" aria-selected="${initialMode==='signup'}">Create account</button></div>
<section id="panel-login"${initialMode==='login'?'':' hidden'}>
<form id="login-form"><label for="login-email">Email</label><input id="login-email" type="email" autocomplete="email" required><label for="login-password">Password</label><input id="login-password" type="password" autocomplete="current-password" required><button>Log in</button></form>
${email?'<div class="link-row"><button type="button" id="show-reset" class="link-btn">Forgot password?</button><button type="button" id="show-otp" class="link-btn">Sign in with email code</button></div>':''}
</section>
<section id="panel-signup"${initialMode==='signup'?'':' hidden'}>
${email?`<form id="signup-form"><label for="signup-email">Email</label><input id="signup-email" type="email" autocomplete="email" required><label for="signup-password">Password</label><input id="signup-password" type="password" autocomplete="new-password" minlength="8" maxlength="128" placeholder="At least 8 characters" required><label for="signup-confirm">Confirm password</label><input id="signup-confirm" type="password" autocomplete="new-password" minlength="8" maxlength="128" required><button>Create account</button></form>
<form id="signup-verify-form" hidden><p class="meta">Enter the 6-digit verification code we emailed to <strong id="signup-email-label"></strong>.</p><label for="signup-code">Verification code</label><input id="signup-code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" required><button>Verify &amp; create account</button><div class="link-row"><button type="button" id="signup-resend" class="link-btn">Send a new code</button><button type="button" id="signup-back" class="link-btn">Change email</button></div></form>`
:'<p class="meta">Email sign-up is not configured on this server. Create an account with a passkey below.</p><button id="register">Create account with passkey</button>'}
</section>
${email?`<section id="panel-reset" hidden>
<form id="reset-start-form"><p class="meta">Enter your email and we will send a 6-digit code to reset your password.</p><label for="reset-email">Email</label><input id="reset-email" type="email" autocomplete="email" required><button>Send reset code</button><button type="button" id="reset-cancel-1" class="secondary">Back to log in</button></form>
<form id="reset-verify-form" hidden><label for="reset-code">6-digit code</label><input id="reset-code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" required><label for="reset-password">New password</label><input id="reset-password" type="password" autocomplete="new-password" minlength="8" maxlength="128" placeholder="At least 8 characters" required><button>Reset password</button><button type="button" id="reset-cancel-2" class="secondary">Back to log in</button></form>
</section>
<section id="panel-otp" hidden>
<form id="email-form"><p class="meta">Get a one-time 6-digit sign-in code by email.</p><label for="email">Email</label><input id="email" type="email" autocomplete="email" required><button>Email me a code</button><button type="button" id="otp-cancel" class="secondary">Back to log in</button></form>
<form id="code-form" hidden><label for="code">Code from the email</label><input id="code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" required><button>Sign in</button><button type="button" id="resend" class="secondary">Send a new code</button></form>
</section>`:''}
<hr class="divider">
<button id="signin" class="secondary">Sign in with a passkey</button>
<details><summary>Use a recovery code</summary><form id="recovery-form"><label for="recovery">Recovery code</label><input id="recovery" type="text" autocomplete="off" required><button>Sign in</button></form></details>`;
    res.type('html').send(page(initialMode==='signup'?'Create account':'Log in',`${body}<p id="status" role="status"></p><script nonce="${n}" src="/work/passkeys.js"></script>`, `
const target=${scriptJSON(target)},status=document.getElementById('status'),subtitle=document.getElementById('subtitle');
const busy=on=>document.querySelectorAll('button,input').forEach(b=>b.disabled=on);
async function post(url,body){const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const data=await r.json().catch(()=>({}));if(!r.ok)throw Error(data.error||'Something went wrong. Try again.');return data;}
const panels={login:document.getElementById('panel-login'),signup:document.getElementById('panel-signup'),reset:document.getElementById('panel-reset'),otp:document.getElementById('panel-otp')};
const tabLogin=document.getElementById('tab-login'),tabSignup=document.getElementById('tab-signup');
function showPanel(name){
  for(const [k,el] of Object.entries(panels))if(el)el.hidden=k!==name;
  if(tabLogin)tabLogin.setAttribute('aria-selected',String(name==='login'));
  if(tabSignup)tabSignup.setAttribute('aria-selected',String(name==='signup'));
  if(subtitle)subtitle.textContent=name==='signup'?'Create your account to link your phones and assistants.':name==='reset'?'Reset your password.':name==='otp'?'Sign in with a one-time email code.':'Log in to manage your devices and assistants.';
  status.textContent='';
}
tabLogin?.addEventListener('click',()=>showPanel('login'));
tabSignup?.addEventListener('click',()=>showPanel('signup'));
document.getElementById('show-reset')?.addEventListener('click',()=>{const le=document.getElementById('login-email')?.value;if(le&&document.getElementById('reset-email'))document.getElementById('reset-email').value=le;document.getElementById('reset-start-form').hidden=false;document.getElementById('reset-verify-form').hidden=true;showPanel('reset');document.getElementById('reset-email')?.focus();});
document.getElementById('show-otp')?.addEventListener('click',()=>{const le=document.getElementById('login-email')?.value;if(le&&document.getElementById('email'))document.getElementById('email').value=le;document.getElementById('email-form').hidden=false;document.getElementById('code-form').hidden=true;showPanel('otp');document.getElementById('email')?.focus();});
document.getElementById('reset-cancel-1')?.addEventListener('click',()=>showPanel('login'));
document.getElementById('reset-cancel-2')?.addEventListener('click',()=>showPanel('login'));
document.getElementById('otp-cancel')?.addEventListener('click',()=>showPanel('login'));

document.getElementById('login-form')?.addEventListener('submit',async e=>{
  e.preventDefault();busy(true);status.textContent='';
  try{await post('/work/login',{email:document.getElementById('login-email').value,password:document.getElementById('login-password').value});window.location.assign(target);}
  catch(err){status.textContent=err.message;busy(false);}
});

const signupForm=document.getElementById('signup-form'),signupVerify=document.getElementById('signup-verify-form');
async function startSignUp(){
  const email=document.getElementById('signup-email').value,pw=document.getElementById('signup-password').value,cf=document.getElementById('signup-confirm').value;
  if(pw.length<8){status.textContent='Password must be at least 8 characters.';return;}
  if(pw!==cf){status.textContent='Passwords do not match.';return;}
  busy(true);status.textContent='';
  try{await post('/work/signup/start',{email,password:pw});document.getElementById('signup-email-label').textContent=email.trim();signupForm.hidden=true;signupVerify.hidden=false;status.textContent='We emailed a 6-digit verification code. It expires in 10 minutes.';busy(false);document.getElementById('signup-code').focus();}
  catch(err){status.textContent=err.message;busy(false);}
}
signupForm?.addEventListener('submit',e=>{e.preventDefault();startSignUp();});
document.getElementById('signup-resend')?.addEventListener('click',startSignUp);
document.getElementById('signup-back')?.addEventListener('click',()=>{signupVerify.hidden=true;signupForm.hidden=false;status.textContent='';});
signupVerify?.addEventListener('submit',async e=>{
  e.preventDefault();busy(true);status.textContent='';
  try{await post('/work/signup/verify',{email:document.getElementById('signup-email').value,code:document.getElementById('signup-code').value});window.location.assign(target);}
  catch(err){status.textContent=err.message;busy(false);}
});

const resetStart=document.getElementById('reset-start-form'),resetVerify=document.getElementById('reset-verify-form');
resetStart?.addEventListener('submit',async e=>{
  e.preventDefault();busy(true);status.textContent='';
  try{await post('/work/password/reset/start',{email:document.getElementById('reset-email').value});resetStart.hidden=true;resetVerify.hidden=false;status.textContent='If that email has an account, we sent a 6-digit reset code.';busy(false);document.getElementById('reset-code').focus();}
  catch(err){status.textContent=err.message;busy(false);}
});
resetVerify?.addEventListener('submit',async e=>{
  e.preventDefault();busy(true);status.textContent='';
  try{await post('/work/password/reset/verify',{email:document.getElementById('reset-email').value,code:document.getElementById('reset-code').value,password:document.getElementById('reset-password').value});window.location.assign(target);}
  catch(err){status.textContent=err.message;busy(false);}
});

async function signIn(mode){busy(true);try{if(!window.PublicKeyCredential)throw Error('This browser cannot use passkeys.');const options=await post('/work/passkeys/options',{mode});const response=mode==='register'?await SimpleWebAuthnBrowser.startRegistration({optionsJSON:options}):await SimpleWebAuthnBrowser.startAuthentication({optionsJSON:options});await post('/work/passkeys/verify',response);window.location.assign(target);}catch(e){status.textContent=e.name==='NotAllowedError'?'Passkey sign-in was cancelled.':e.message;busy(false);}}
const emailForm=document.getElementById('email-form'),codeForm=document.getElementById('code-form');
async function sendCode(){busy(true);try{await post('/work/email/start',{email:document.getElementById('email').value});emailForm.hidden=true;codeForm.hidden=false;status.textContent='We emailed a 6-digit code. It expires in 10 minutes.';busy(false);document.getElementById('code').focus();}catch(e){status.textContent=e.message;busy(false);}}
if(emailForm){emailForm.onsubmit=e=>{e.preventDefault();sendCode();};document.getElementById('resend').onclick=sendCode;
codeForm.onsubmit=async e=>{e.preventDefault();busy(true);try{await post('/work/email/verify',{email:document.getElementById('email').value,code:document.getElementById('code').value});window.location.assign(target);}catch(err){status.textContent=err.message;busy(false);}};}
document.getElementById('recovery-form')?.addEventListener('submit',async e=>{e.preventDefault();busy(true);try{await post('/work/recovery/verify',{code:document.getElementById('recovery').value});window.location.assign(target);}catch(err){status.textContent=err.message;busy(false);}});
document.getElementById('signin')?.addEventListener('click',()=>signIn('authenticate'));document.getElementById('register')?.addEventListener('click',()=>signIn('register'));`,n));
  };
  app.get('/work/sign-in',(req,res)=>renderSignIn(req,res,'login'));
  app.get('/work/sign-up',(req,res)=>renderSignIn(req,res,'signup'));
  app.post('/work/signup/start',async(req,res)=>{
    if(limited('signup-ip:'+req.ip,8))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{res.json(await owners.startSignUp(req.body?.email,req.body?.password));}catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/signup/verify',(req,res)=>{
    if(limited('verify-ip:'+req.ip,30))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{owners.issue(res,owners.verifySignUp(req.body?.email,req.body?.code));res.json({ok:true});}catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/login',async(req,res)=>{
    if(limited('login-ip:'+req.ip,20))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{owners.issue(res,await owners.verifyPassword(req.body?.email,req.body?.password));res.json({ok:true});}catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/password/reset/start',async(req,res)=>{
    if(limited('reset-ip:'+req.ip,8))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{res.json(await owners.startPasswordReset(req.body?.email));}catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/password/reset/verify',async(req,res)=>{
    if(limited('verify-ip:'+req.ip,30))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{owners.issue(res,await owners.verifyPasswordReset(req.body?.email,req.body?.code,req.body?.password));res.json({ok:true});}catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/email/start',async(req,res)=>{
    if(limited('email-ip:'+req.ip,8))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{res.json(await owners.startEmail(req.body?.email));}catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/email/verify',(req,res)=>{
    if(limited('verify-ip:'+req.ip,30))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{owners.issue(res,owners.verifyEmail(req.body?.email,req.body?.code));res.json({ok:true});}catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/recovery/verify',(req,res)=>{
    if(limited('recovery-ip:'+req.ip,10))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{owners.issue(res,owners.verifyRecovery(req.body?.code));res.json({ok:true});}catch(e){res.status(400).json({error:e.message});}
  });
  // An agent's connection request: push directly to the owner's phone (no browser login needed), or 1-tap Allow if already signed in.
  app.get('/work/connect/approve',(req,res)=>{
    const n=nonce(res);
    const view=auth.requests.view(req.query.request);
    if(!view)return res.type('html').send(page('Connection request','<h1>This request ended</h1><p>It was approved, denied or expired. Ask your agent to connect again if it still needs access.</p>'));
    const names=view.names.map(escape).join(', ');
    const ownerId=owners.owner(req);
    if(!ownerId){
      const signInUrl='/work/sign-in?return_to='+encodeURIComponent('/work/connect/approve?request='+view.request);
      return res.type('html').send(page('Connect '+names,`<h1>Connect ${names}</h1><p class="meta">Check digits: <strong>${escape(view.check)}</strong></p><form id="to-phone"><label for="owner-email">Your Backstage email</label><input id="owner-email" type="email" autocomplete="email" required><button>Send to my phone</button></form><p id="status" role="status"></p><p><small><a href="${escape(signInUrl)}">Approve in browser instead</a></small></p>`,`
const request=${scriptJSON(view.request)},status=document.getElementById('status'),email=document.getElementById('owner-email');
try{email.value=localStorage.getItem('backstage-email')||'';}catch{}
if(email.value)document.querySelector('#to-phone button').focus();else email.focus();
let polling=false;
async function poll(){if(polling)return;polling=true;while(true){await new Promise(r=>setTimeout(r,1500));try{const r=await fetch('/work/connect/approve/status?request='+encodeURIComponent(request));const d=await r.json();if(d.state==='approved'){status.textContent='Connected. You can close this page.';return;}if(d.state==='denied'){status.textContent='Denied on your phone. You can close this page.';return;}if(d.state==='expired'){status.textContent='This request expired. Ask your agent to connect again.';return;}}catch{}}}
document.getElementById('to-phone').onsubmit=async e=>{e.preventDefault();const b=e.target.querySelector('button');b.disabled=true;try{const r=await fetch('/work/connect/approve/phone',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({request,email:email.value})});const d=await r.json();if(!r.ok)throw Error(d.error||'Could not send.');try{localStorage.setItem('backstage-email',email.value);}catch{}status.textContent='Sent to your phone. Tap Allow in Backstage.';b.textContent='Send again';poll();}catch(err){status.textContent=err.message;}b.disabled=false;};`,n));
    }
    res.type('html').send(page('Allow access',`<h1>Allow ${names}?</h1><p class="meta">Check digits: <strong>${escape(view.check)}</strong></p><p>${view.names.length>1?'These agents':'This agent'} will be able to use apps and run commands on your devices. You can pause or remove access in Backstage anytime.</p><button id="allow">Allow</button><button id="deny" class="secondary">Deny</button><p id="status" role="status"></p>`,`
const status=document.getElementById('status'),request=${scriptJSON(view.request)};
async function decide(allow){document.querySelectorAll('button').forEach(b=>b.disabled=true);try{const r=await fetch('/work/connect/approve',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({request,allow})});const data=await r.json();if(!r.ok)throw Error(data.error||'Could not finish.');status.textContent=allow?'Connected. You can close this page.':'Denied. You can close this page.';}catch(e){status.textContent=e.message;document.querySelectorAll('button').forEach(b=>b.disabled=false);}}
document.getElementById('allow').onclick=()=>decide(true);document.getElementById('deny').onclick=()=>decide(false);`,n));
  });
  app.post('/work/connect/approve/phone',(req,res)=>{
    if(req.headers.origin!==origin)return res.status(403).json({error:'Use the Backstage page to continue.'});
    if(limited('approve-phone:'+req.ip,10))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    const ownerId=owners.ownerByEmail?.(req.body?.email);
    if(!ownerId)return res.json({sent:true});
    try{auth.requests.attachOwner(req.body?.request,ownerId);res.json({sent:true});}catch(e){res.status(400).json({error:e.message});}
  });
  app.get('/work/connect/approve/status',(req,res)=>res.json(auth.requests.statusPublic(req.query.request)));
  app.post('/work/connect/approve',(req,res)=>{
    if(req.headers.origin!==origin)return res.status(403).json({error:'Use the Backstage page to continue.'});
    const ownerId=signedIn(req,res);if(!ownerId)return;
    try{res.json(auth.requests.decide(req.body?.request,ownerId,req.body?.allow===true,'all'));}catch(e){res.status(400).json({error:e.message});}
  });
  // Sign-in methods for the signed-in owner.
  app.post('/work/account/email/start',async(req,res)=>{const id=signedIn(req,res);if(!id)return;if(limited('email-owner:'+id,10))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});try{res.json(await owners.startEmail(req.body?.email,{linkOwner:id}));}catch(e){res.status(400).json({error:e.message});}});
  app.post('/work/account/email/verify',(req,res)=>{const id=signedIn(req,res);if(!id)return;try{owners.verifyEmail(req.body?.email,req.body?.code,{expectedLinkOwner:id});res.json(owners.methods(id));}catch(e){res.status(400).json({error:e.message});}});
  app.post('/work/account/email/remove',(req,res)=>{const id=signedIn(req,res);if(!id)return;try{owners.removeEmail(id);res.json(owners.methods(id));}catch(e){res.status(400).json({error:e.message});}});
  app.post('/work/account/password',async(req,res)=>{const id=signedIn(req,res);if(!id)return;try{await owners.setPassword(id,req.body?.password,{currentPassword:req.body?.currentPassword,requireCurrent:true});res.json(owners.methods(id));}catch(e){res.status(400).json({error:e.message});}});
  app.post('/work/account/recovery',(req,res)=>{const id=signedIn(req,res);if(!id)return;res.json({codes:owners.newRecoveryCodes(id)});});
  app.post('/work/account/passkeys/remove',(req,res)=>{const id=signedIn(req,res);if(!id)return;try{owners.removePasskey(id,String(req.body?.id||''));res.json(owners.methods(id));}catch(e){res.status(400).json({error:e.message});}});
  app.post('/work/passkeys/options',async(req,res)=>{try{res.json(await owners.options(req,res,req.body.mode));}catch{res.status(400).json({error:'Could not start passkey sign-in. Try again.'});}});
  app.post('/work/passkeys/verify',async(req,res)=>{try{await owners.verify(req,res,req.body);res.json({ok:true});}catch{res.status(400).json({error:'The passkey could not be verified. Try again with the passkey you saved for Backstage.'});}});

  const statusLabel=d=>d.status==='online'?['ok','Online']:d.status==='offline'?['warn','Offline — open Android Backstage on it and tap Start']:d.status==='needs_reconnect'?['bad','Needs reconnecting — the phone no longer holds this link']:['warn','Status unknown'];
  const methodsSection=m=>`<div class="card"><strong>Email</strong>${m.email?`<p class="meta"><!--email_off-->${escape(m.email)}<!--/email_off--> · used for log in and verification codes</p><button type="button" class="secondary" id="email-remove">Remove email</button>`:`<p class="meta">${m.emailAvailable?'Add an email to sign in on any device.':'Email sign-in is not set up on this server.'}</p>`}${m.emailAvailable?`<form id="email-add"><label for="new-email">${m.email?'Change email':'Email'}</label><input id="new-email" type="email" autocomplete="email" required><button class="secondary">Send a code</button></form><form id="email-confirm" hidden><label for="email-code">Code from the email</label><input id="email-code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" required><button>Confirm email</button></form>`:''}</div>
${m.email?`<div class="card"><strong>Password</strong><p class="meta">${m.hasPassword?'Password is set. You can log in with your email and password.':'No password set yet. Add one to log in with email and password.'}</p><form id="password-form">${m.hasPassword?'<label for="cur-password">Current password</label><input id="cur-password" type="password" autocomplete="current-password" required>':''}<label for="new-password">${m.hasPassword?'New password':'Password'}</label><input id="new-password" type="password" autocomplete="new-password" minlength="8" maxlength="128" placeholder="At least 8 characters" required><button class="secondary">${m.hasPassword?'Change password':'Set password'}</button></form></div>`:''}
<div class="card"><strong>Passkeys</strong>${m.passkeys.length?m.passkeys.map(k=>`<p class="meta" data-passkey="${escape(k.id)}">${escape(k.label)}${k.createdAt?` · added ${escape(new Date(k.createdAt).toLocaleDateString('en-GB'))}`:''} <button type="button" class="secondary passkey-remove">Remove</button></p>`).join(''):'<p class="meta">No passkey yet. A passkey signs you in with your fingerprint or screen lock.</p>'}<a class="button secondary" href="/work/sign-in?return_to=/work/account">Add a passkey</a></div>
<div class="card"><strong>Recovery codes</strong><p class="meta">${m.recoveryCodesLeft?`${m.recoveryCodesLeft} unused. Each signs you in once if you lose your other methods.`:'Save recovery codes in case you lose your email and passkeys.'}</p><button type="button" class="secondary" id="recovery-new">${m.recoveryCodesLeft?'Replace recovery codes':'Create recovery codes'}</button><pre id="recovery-codes" hidden></pre></div>`;
  const methodsScript=`
const emailAdd=document.getElementById('email-add'),emailConfirm=document.getElementById('email-confirm');
if(emailAdd){emailAdd.onsubmit=async e=>{e.preventDefault();try{await post('/work/account/email/start',{email:document.getElementById('new-email').value});emailConfirm.hidden=false;status.textContent='We emailed a code to confirm the address.';}catch(err){status.textContent=err.message;}};
emailConfirm.onsubmit=async e=>{e.preventDefault();try{await post('/work/account/email/verify',{email:document.getElementById('new-email').value,code:document.getElementById('email-code').value});window.location.reload();}catch(err){status.textContent=err.message;}};}
document.getElementById('password-form')?.addEventListener('submit',async e=>{e.preventDefault();try{const cur=document.getElementById('cur-password')?.value;await post('/work/account/password',{currentPassword:cur,password:document.getElementById('new-password').value});window.location.reload();}catch(err){status.textContent=err.message;}});
document.getElementById('email-remove')?.addEventListener('click',async()=>{if(!confirm('Remove this email? You will sign in with a passkey or recovery code.'))return;try{await post('/work/account/email/remove',{});window.location.reload();}catch(e){status.textContent=e.message;}});
document.querySelectorAll('.passkey-remove').forEach(b=>b.onclick=async()=>{if(!confirm('Remove this passkey?'))return;try{await post('/work/account/passkeys/remove',{id:b.parentElement.dataset.passkey});window.location.reload();}catch(e){status.textContent=e.message;}});
document.getElementById('recovery-new').onclick=async()=>{if(!confirm('Create new recovery codes? Old codes stop working.'))return;try{const data=await post('/work/account/recovery',{});const out=document.getElementById('recovery-codes');out.textContent=data.codes.join('\\n');out.hidden=false;status.textContent='Save these codes somewhere safe. They are shown only once.';}catch(e){status.textContent=e.message;}};`;
  app.get('/work/account',async(req,res)=>{
    const ownerId=signedIn(req,res);if(!ownerId)return;const n=nonce(res),grants=connections.list(ownerId);
    const devices=await Promise.all(connections.devices(ownerId).map(d=>connections.probe(d)));
    const deviceCards=devices.map(d=>{const [cls,label]=statusLabel(d);return `<div class="card" data-device="${escape(d.id)}"><strong class="device-name">${escape(d.name)}</strong><p class="meta"><span class="${cls}">${label}</span>${d.model?` · ${escape(d.model)}`:''}</p><details><summary><small>Details</small></summary><p class="meta"><small>ID ${escape(d.id)}${d.lastSeen?` · last seen ${escape(new Date(d.lastSeen).toLocaleString('en-GB'))}`:''}</small></p></details><div class="actions"><button class="secondary rename" type="button">Rename</button><a class="button secondary" href="/work/enroll?device=${escape(d.id)}">${d.status==='needs_reconnect'?'Reconnect':'Replace link'}</a><button class="secondary remove" type="button">Remove</button></div></div>`;}).join('');
    const accessEditor=row=>{const all=row.access==='all',allowed=new Set(Array.isArray(row.access)?row.access:[]);return devices.map(d=>`<label class="row"><input type="checkbox" name="device" value="${escape(d.id)}" ${all||allowed.has(d.id)?'checked':''}> ${escape(d.name)}</label>`).join('')+`<label class="row"><input type="checkbox" name="future" ${all?'checked':''}> Devices I add later</label>`;};
    const usesText=row=>row.access==='all'?(devices.length>1?'all your devices, including new ones':'your phone'):devices.filter(d=>row.access.includes(d.id)).map(d=>d.name).join(', ')||'no device yet';
    const iconFor=row=>{const info=auth?.clientInfo?.(row.clientId)||{};const id=assistantIcon(row.name||info.name||'',info.origin||'');return `<img src="https://android-backstage.com/assets/assistants/${id}.${id==='codex'||id==='gemini'?'png':'svg'}" alt="" width="36" height="36" style="vertical-align:middle;margin-right:10px;border-radius:8px">`;};
    const grantCards=grants.map(row=>`<div class="card" data-grant="${escape(row.id)}"><strong>${iconFor(row)}${escape(row.name||'Assistant')}</strong><p class="meta">Can use ${escape(usesText(row))} · connected ${escape(new Date(row.createdAt).toLocaleDateString('en-GB',{day:'numeric',month:'short',year:'numeric'}))}</p><fieldset><legend>May use</legend>${accessEditor(row)}</fieldset><div class="actions"><button class="save" type="button">Save access</button><button class="secondary revoke" type="button">Remove access</button></div></div>`).join('');
    res.type('html').send(page('Your devices and assistants',`<h1>Android Backstage</h1><p>One account. Your devices join it; your assistants get permission to it. You can do all of this in the Backstage app on your phone too.</p>
<h2>Devices</h2>${deviceCards||'<p>No device is connected yet.</p>'}${devices.length?`<form id="add" class="inline"><label>Name for the next device<input type="text" name="name" maxlength="32" placeholder="tablet" autocomplete="off"></label><button type="submit" class="secondary">Add a device</button></form>`:`<form id="add" class="inline"><button type="submit">Connect your phone</button></form>`}<p><small>Adding, renaming or removing a device does not disturb the others. Reconnect a device after reinstalling or resetting Android Backstage on it.</small></p>
<h2>Assistants</h2>${grantCards||'<p>No assistant is connected yet. Connect one from that app’s Android Backstage plugin.</p>'}
<p><small>Removing one assistant leaves your devices and other assistants untouched. Pausing access inside the phone app stops every assistant on that phone.</small></p>
<p><small>The Backstage server holds the link for each device and relays every command, so it sees what your assistants send and what your devices return. The relay component and Cloudflare cannot read the encrypted phone envelopes. <a href="https://android-backstage.com/privacy/">Privacy</a> · <a href="https://android-backstage.com/terms/">Terms</a></small></p>
<h2>Signing in</h2>${methodsSection(owners.methods(ownerId))}<button id="logout" class="secondary">Sign out</button>
<div class="card"><strong>Delete account</strong><p class="meta">Permanently remove your email, passkeys, device links, and all assistant access from this server.</p><button type="button" class="secondary bad" id="delete-account">Delete account</button></div><p id="status" role="status"></p>`, `
const status=document.getElementById('status');
async function post(route,body){const r=await fetch(route,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const data=await r.json().catch(()=>({}));if(!r.ok)throw Error(data.error||'Could not finish. Sign in and try again.');return data;}
${methodsScript}
document.querySelectorAll('.card[data-device]').forEach(card=>{const id=card.dataset.device,name=card.querySelector('.device-name');
 card.querySelector('.rename').onclick=async()=>{const next=prompt('New name for '+name.textContent,name.textContent);if(!next||next===name.textContent)return;try{const data=await post('/work/account/devices/rename',{deviceId:id,name:next});name.textContent=data.name;status.textContent='';}catch(e){status.textContent=e.message;}};
 card.querySelector('.remove').onclick=async()=>{if(!confirm('Remove '+name.textContent+'? Assistants lose access to it until you add it again.'))return;try{await post('/work/account/devices/remove',{deviceId:id});window.location.reload();}catch(e){status.textContent=e.message;}};});
document.getElementById('add').onsubmit=e=>{e.preventDefault();const name=(e.target.elements.name?.value||'').trim();window.location.assign('/work/enroll'+(name?'?name='+encodeURIComponent(name):''));};
document.querySelectorAll('.card[data-grant]').forEach(card=>{const id=card.dataset.grant;
 card.querySelector('.save').onclick=async()=>{const boxes=[...card.querySelectorAll('input[name=device]')],future=card.querySelector('input[name=future]');const all=future.checked&&boxes.every(b=>b.checked);const devices=boxes.filter(b=>b.checked).map(b=>b.value);if(!all&&!devices.length){status.textContent='Tick at least one device, or remove access.';return;}try{await post('/work/account/access',{grantId:id,...(all?{allDevices:true}:{devices})});status.textContent='Saved.';}catch(e){status.textContent=e.message;}};
 card.querySelector('.revoke').onclick=async ev=>{ev.target.disabled=true;try{await post('/work/account/revoke',{grantId:id});card.remove();}catch(e){status.textContent=e.message;ev.target.disabled=false;}};});
document.getElementById('logout').onclick=async()=>{await fetch('/work/account/sign-out',{method:'POST'});window.location.assign('/work/sign-in');};
document.getElementById('delete-account').onclick=async()=>{if(prompt('Type DELETE to permanently delete your Android Backstage account, all device links, and all assistant access.')!=='DELETE')return;try{await post('/work/account/delete',{confirm:'DELETE'});window.location.assign('/work/sign-in');}catch(e){status.textContent=e.message;}};`,n));
  });
  app.post('/work/account/revoke',(req,res)=>{const id=signedIn(req,res);if(!id)return;try{connections.revokeOwned(id,req.body.grantId);auth?.revokeGrants?.([req.body.grantId]);artifacts?.removeForGrants?.([req.body.grantId]);res.json({ok:true});}catch(e){problem(res,e,404);}});
  app.post('/work/account/access',(req,res)=>{const id=signedIn(req,res);if(!id)return;
    const access=req.body.allDevices===true?'all':Array.isArray(req.body.devices)?req.body.devices.filter(x=>typeof x==='string').slice(0,20):null;
    if(access===null||(Array.isArray(access)&&!access.length))return res.status(400).json({error:'Tick at least one device.'});
    try{res.json({ok:true,access:connections.setAccess(id,req.body.grantId,access)});}catch(e){problem(res,e);}});
  app.post('/work/account/devices/rename',(req,res)=>{const id=signedIn(req,res);if(!id)return;try{res.json({ok:true,...connections.rename(id,req.body.deviceId,String(req.body.name||'').trim())});}catch(e){problem(res,e);}});
  app.post('/work/account/devices/remove',async(req,res)=>{const id=signedIn(req,res);if(!id)return;try{await connections.remove(id,req.body.deviceId);res.json({ok:true});}catch(e){problem(res,e);}});
  app.post('/work/account/sign-out',(req,res)=>{owners.signOut(req,res);res.json({ok:true});});
  app.post('/work/account/delete',async(req,res)=>{
    const id=signedIn(req,res);if(!id)return;
    if(req.body?.confirm!=='DELETE')return res.status(400).json({error:'Confirmation required.'});
    auth?.purgeOwner?.(id);
    await nativeSignIn?.purgeOwner?.(id);
    const cancels=[];
    for(const [ticket,row] of pending)if(row.ownerId===id){pending.delete(ticket);cancels.push(row.connection?.cancel?.().catch(()=>{}));}
    await Promise.all(cancels);
    const {grantIds}=await connections.removeAllForOwner(id);
    auth?.revokeGrants?.(grantIds);
    artifacts?.removeForGrants?.(grantIds);
    owners.deleteOwner(id,req,res);
    res.json({ok:true,deleted:true});
  });

  // Add a device (?name=) or reconnect an existing one (?device=ID) under the same ID.
  app.get('/work/enroll',async(req,res)=>{
    const ownerId=signedIn(req,res);if(!ownerId)return;
    let deviceId=typeof req.query.device==='string'?req.query.device:null,name=typeof req.query.name==='string'&&validDeviceName(req.query.name)?req.query.name.trim():null;
    // "Connect with your account" from a phone that already has a slot with this name and is not online: reconnect that slot instead of adding "name 2".
    if(!deviceId&&name){const same=connections.devices(ownerId).find(d=>d.name.toLowerCase()===name.toLowerCase());if(same){const live=await connections.probe(same);if(!live.online){deviceId=same.id;name=null;}}}
    if(deviceId&&!connections.devices(ownerId).some(d=>d.id===deviceId))return res.status(404).type('html').send(page('Device not found','<h1>Device not found</h1><p>It may have been removed. Add it again from your account.</p><a class="button" href="/work/account">Your devices</a>'));
    for(const [id,row] of pending)if(row.expires<=now()){pending.delete(id);row.connection.cancel().catch(()=>{});}
    try{
      let entry=[...pending].find(([,row])=>row.ownerId===ownerId&&row.deviceId===deviceId&&row.name===name);
      if(!entry){if(pending.size>=100)throw Error('Busy');const ticket=crypto.randomBytes(32).toString('base64url');const connection=await connections.begin({ownerId,clientName:'Android Backstage',deviceId,name});const row={ownerId,deviceId,name,connection,expires:now()+600000};pending.set(ticket,row);entry=[ticket,row];}
      res.type('html').send(await connectionPage({base,ticket:entry[0],nonce:nonce(res),connection:entry[1].connection,pollPath:'/enroll',heading:deviceId?`Reconnect ${entry[1].connection.deviceName}`:`Connect ${entry[1].connection.deviceName}`}));
    }catch{res.status(503).type('html').send(page('Could not connect','<h1>Could not connect</h1><p>Return to your account and try again.</p><a href="/work/account">Your devices</a>'));}
  });
  app.post('/work/enroll',async(req,res)=>{
    const ownerId=signedIn(req,res);if(!ownerId)return;const row=pending.get(req.body.ticket);
    if(!row||row.ownerId!==ownerId||row.expires<=now())return res.status(403).json({error:'Request expired.'});
    if(req.body.cancel){pending.delete(req.body.ticket);await row.connection.cancel().catch(()=>{});return res.json({cancelled:true});}
    if(row.busy)return res.json({pending:true});row.busy=true;
    try{const id=await row.connection.poll();if(!pending.has(req.body.ticket)||row.expires<=now())return res.status(403).json({error:'Request expired.'});if(!id)return res.json({pending:true});pending.delete(req.body.ticket);res.json({redirect:base+'/account'});}catch{pending.delete(req.body.ticket);res.status(400).json({error:'Could not connect. Try again.'});}finally{row.busy=false;}
  });
}
