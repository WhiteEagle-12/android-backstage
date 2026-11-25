import crypto from 'node:crypto';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import QRCode from 'qrcode';
import {assistantIconAsset} from './assistants.mjs';
import {page,escape,scriptJSON} from './pages.mjs';
import {connectionPage} from './connect-page.mjs';
import {validDeviceName,ConnectionError} from './connections.mjs';

export function ownerRoutes(app,{owners,connections,base,auth,artifacts=null,nativeSignIn=null,phoneApi=null,billing=null,now=Date.now}) {
  const origin=new URL(base).origin,pending=new Map();
  const nonce=res=>{const n=crypto.randomBytes(24).toString('base64url');res.set('Content-Security-Policy',`default-src 'none'; script-src 'nonce-${n}' 'self'; style-src 'unsafe-inline'; img-src https://android-backstage.com; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`);return n;};
  const safeReturn=value=>typeof value==='string'&&(/^\/work\/(account|enroll)(\?[A-Za-z0-9_=&%.-]{0,120})?$/.test(value)||/^\/work\/connect\/approve\?request=req_[a-f0-9]{32}$/.test(value)||value.startsWith('/work/authorize?'))?value:'/work/account';
  const signedIn=(req,res)=>{const id=owners.owner(req);if(!id){if(req.method==='GET')res.redirect(303,'/work/sign-in?return_to='+encodeURIComponent(safeReturn(req.originalUrl)));else res.status(401).json({error:'Sign in to Backstage again.'});}return id;};
  const problem=(res,e,status=400)=>res.status(e instanceof ConnectionError?({unknown_device:404,access_revoked:404,plan_limit:403,busy:429}[e.code]||status):status).json({error:e instanceof ConnectionError?e.message:'Could not finish. Sign in and try again.'});
  app.use(['/work/passkeys','/work/enroll','/work/account','/work/email','/work/recovery','/work/signup','/work/login','/work/password'],(req,res,next)=>{if(req.method==='POST'&&req.headers.origin!==origin)return res.status(403).json({error:'Use the Backstage page to continue.'});next();});
  app.get('/work/passkeys.js',(_req,res)=>res.type('js').sendFile(path.resolve(fileURLToPath(new URL('./node_modules/@simplewebauthn/browser/dist/bundle/index.umd.min.js',import.meta.url))),{dotfiles:'allow'}));
  // Per-address and per-IP limits sit in the account store and here; codes expire in 10 minutes.
  const tries=new Map();
  const limited=(key,max)=>{const t=now();let row=tries.get(key);if(!row||row.until<=t)row={count:0,until:t+600000};row.count++;if(!tries.has(key)&&tries.size>=5000){for(const [k,v] of tries)if(v.until<=t)tries.delete(k);if(tries.size>=5000){const oldest=tries.keys().next().value;if(oldest!==undefined)tries.delete(oldest);}}tries.set(key,row);return row.count>max;};
  const fmtDate=ts=>new Date(ts).toLocaleDateString('en-US',{month:'short',day:'numeric',year:'numeric'});
  const fmtDateTime=ts=>new Date(ts).toLocaleString('en-US',{month:'short',day:'numeric',year:'numeric',hour:'numeric',minute:'2-digit'});

  const renderSignIn=(req,res,defaultMode='login')=>{
    const n=nonce(res),target=safeReturn(req.query.return_to),adding=!!owners.owner(req),email=owners.emailAvailable();
    const initialMode=req.query.mode==='signup'||req.query.plan==='pro'||defaultMode==='signup'?'signup':'login';
    const trialBanner=`<div class="promo-banner" id="trial-banner"${initialMode==='signup'?'':' hidden'}><strong>Launch promo · 7-day Pro free trial (no card needed)</strong><p>Try 5 devices, parallel virtual displays, recording &amp; webhooks free for 7 days. Switches to Personal automatically — or subscribe during our first month of launch to lock in 50% off Pro at <strong>$4.50/mo</strong> (or <strong>$42/yr</strong>) for as long as you keep your subscription.</p></div>`;
    const body=adding?`<h1>Add a passkey</h1><p>Save another passkey so you can sign in on this device with your fingerprint or screen lock.</p><button id="register">Save a passkey</button><a class="button secondary" href="/work/account">Back to account</a>`
      :`<h1>${initialMode==='signup'?'Create account':'Sign in'}</h1><p id="subtitle">${initialMode==='signup'?'Start your 7-day Pro free trial — no card required.':'Log in to manage your devices, assistants, and plan.'}</p>
<div class="tabs" id="auth-tabs" role="tablist" aria-label="Account"><button type="button" role="tab" id="tab-login" aria-selected="${initialMode==='login'}">Sign in</button><button type="button" role="tab" id="tab-signup" aria-selected="${initialMode==='signup'}">Create account</button></div>
${trialBanner}
<section id="panel-login"${initialMode==='login'?'':' hidden'}>
<form id="login-form"><label for="login-email">Email</label><input id="login-email" type="email" autocomplete="email" placeholder="you@example.com" required><label for="login-password">Password</label><div class="pw-wrap"><input id="login-password" type="password" autocomplete="current-password" required><button type="button" class="pw-toggle" data-pw="login-password">Show</button></div><button>Sign in</button></form>
${email?'<div class="link-row"><button type="button" id="show-reset" class="link-btn">Forgot password?</button><button type="button" id="show-otp" class="link-btn">Sign in with email code</button></div>':''}
</section>
<section id="panel-signup"${initialMode==='signup'?'':' hidden'}>
${email?`<form id="signup-form"><label for="signup-email">Email</label><input id="signup-email" type="email" autocomplete="email" placeholder="you@example.com" required><label for="signup-password">Password</label><div class="pw-wrap"><input id="signup-password" type="password" autocomplete="new-password" minlength="8" maxlength="128" placeholder="At least 8 characters" required><button type="button" class="pw-toggle" data-pw="signup-password">Show</button></div><label for="signup-confirm">Confirm password</label><div class="pw-wrap"><input id="signup-confirm" type="password" autocomplete="new-password" minlength="8" maxlength="128" required><button type="button" class="pw-toggle" data-pw="signup-confirm">Show</button></div><button>Start 7-day Pro trial</button><p style="margin-top:10px"><small>No card required. We will remind you at 3 days left, 1 day left, and day of expiration.</small></p></form>
<form id="signup-verify-form" hidden><p class="meta">Enter the 6-digit verification code we emailed to <strong id="signup-email-label"></strong>.</p><label for="signup-code">Verification code</label><input id="signup-code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" required><button>Verify &amp; start free trial</button><div class="link-row"><button type="button" id="signup-resend" class="link-btn">Send a new code</button><button type="button" id="signup-back" class="link-btn">Change email</button></div></form>`
:'<p class="meta">Email sign-up is not configured on this server. Create an account with a passkey below.</p><button id="register">Create account with passkey</button>'}
</section>
<section id="panel-mfa" hidden>
<form id="mfa-form"><p class="meta" id="mfa-hint">Enter the 6-digit code from your authenticator app to finish signing in.</p><label for="mfa-code" id="mfa-label">Authenticator code</label><input id="mfa-code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="20" placeholder="000000" required><button>Verify &amp; sign in</button><div class="link-row"><button type="button" id="mfa-toggle-recovery" class="link-btn">Use a recovery code instead</button><button type="button" id="mfa-cancel" class="link-btn">Back to sign in</button></div></form>
</section>
${email?`<section id="panel-reset" hidden>
<form id="reset-start-form"><p class="meta">Enter your email and we will send a 6-digit code to reset your password.</p><label for="reset-email">Email</label><input id="reset-email" type="email" autocomplete="email" required><button>Send reset code</button><button type="button" id="reset-cancel-1" class="secondary">Back to sign in</button></form>
<form id="reset-verify-form" hidden><label for="reset-code">6-digit code</label><input id="reset-code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" required><label for="reset-password">New password</label><div class="pw-wrap"><input id="reset-password" type="password" autocomplete="new-password" minlength="8" maxlength="128" placeholder="At least 8 characters" required><button type="button" class="pw-toggle" data-pw="reset-password">Show</button></div><button>Reset password</button><button type="button" id="reset-cancel-2" class="secondary">Back to sign in</button></form>
</section>
<section id="panel-otp" hidden>
<form id="email-form"><p class="meta">Get a one-time 6-digit sign-in code by email.</p><label for="email">Email</label><input id="email" type="email" autocomplete="email" required><button>Email me a code</button><button type="button" id="otp-cancel" class="secondary">Back to sign in</button></form>
<form id="code-form" hidden><label for="code">Code from the email</label><input id="code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" required><button>Sign in</button><button type="button" id="resend" class="secondary">Send a new code</button></form>
</section>`:''}
<div id="alt-methods"><hr class="divider"><button id="signin" class="secondary">Sign in with a passkey</button><details><summary>Use a recovery code</summary><form id="recovery-form"><label for="recovery">Recovery code</label><input id="recovery" type="text" autocomplete="off" placeholder="XXXX-XXXX-XXXX" required><button>Sign in</button></form></details></div>`;
    res.type('html').send(page(adding?'Add a passkey':initialMode==='signup'?'Create account':'Sign in',`${body}<p id="status" role="status"></p><script nonce="${n}" src="/work/passkeys.js"></script>`, `
const target=${scriptJSON(target)},status=document.getElementById('status'),subtitle=document.getElementById('subtitle'),heading=document.querySelector('h1'),trialBanner=document.getElementById('trial-banner'),authTabs=document.getElementById('auth-tabs'),altMethods=document.getElementById('alt-methods');
const setStatus=(msg,err=false)=>{status.textContent=msg||'';if(err)status.dataset.tone='error';else delete status.dataset.tone;};
const busy=on=>document.querySelectorAll('button,input').forEach(b=>b.disabled=on);
async function post(url,body){const r=await fetch(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const data=await r.json().catch(()=>({}));if(!r.ok)throw Error(data.error||'Something went wrong. Try again.');return data;}
document.querySelectorAll('.pw-toggle').forEach(btn=>btn.addEventListener('click',()=>{const inp=document.getElementById(btn.dataset.pw);if(!inp)return;const show=inp.type==='password';inp.type=show?'text':'password';btn.textContent=show?'Hide':'Show';}));
const panels={login:document.getElementById('panel-login'),signup:document.getElementById('panel-signup'),reset:document.getElementById('panel-reset'),otp:document.getElementById('panel-otp'),mfa:document.getElementById('panel-mfa')};
const tabLogin=document.getElementById('tab-login'),tabSignup=document.getElementById('tab-signup');
function showPanel(name){
  for(const [k,el] of Object.entries(panels))if(el)el.hidden=k!==name;
  if(tabLogin)tabLogin.setAttribute('aria-selected',String(name==='login'));
  if(tabSignup)tabSignup.setAttribute('aria-selected',String(name==='signup'));
  if(trialBanner)trialBanner.hidden=name!=='signup';
  if(authTabs)authTabs.hidden=name==='mfa';
  if(altMethods)altMethods.hidden=name==='mfa';
  if(heading&&subtitle){
    heading.textContent=name==='signup'?'Create account':name==='reset'?'Reset password':name==='otp'?'Email code':name==='mfa'?'Two-factor verification':'Sign in';
    subtitle.textContent=name==='signup'?'Start your 7-day Pro free trial — no card required.':name==='reset'?'We will email a 6-digit reset code.':name==='otp'?'Sign in with a one-time email code.':name==='mfa'?'This account is protected with two-factor authentication.':'Log in to manage your devices, assistants, and plan.';
  }
  setStatus('');
}
function enterMfa(){
  const inp=document.getElementById('mfa-code');if(inp){inp.value='';inp.inputMode='numeric';inp.placeholder='000000';}
  const lbl=document.getElementById('mfa-label');if(lbl)lbl.textContent='Authenticator code';
  const hint=document.getElementById('mfa-hint');if(hint)hint.textContent='Enter the 6-digit code from your authenticator app to finish signing in.';
  const tog=document.getElementById('mfa-toggle-recovery');if(tog)tog.textContent='Use a recovery code instead';
  showPanel('mfa');busy(false);inp?.focus();
}
tabLogin?.addEventListener('click',()=>showPanel('login'));
tabSignup?.addEventListener('click',()=>showPanel('signup'));
document.getElementById('show-reset')?.addEventListener('click',()=>{const le=document.getElementById('login-email')?.value;if(le&&document.getElementById('reset-email'))document.getElementById('reset-email').value=le;document.getElementById('reset-start-form').hidden=false;document.getElementById('reset-verify-form').hidden=true;showPanel('reset');document.getElementById('reset-email')?.focus();});
document.getElementById('show-otp')?.addEventListener('click',()=>{const le=document.getElementById('login-email')?.value;if(le&&document.getElementById('email'))document.getElementById('email').value=le;document.getElementById('email-form').hidden=false;document.getElementById('code-form').hidden=true;showPanel('otp');document.getElementById('email')?.focus();});
document.getElementById('reset-cancel-1')?.addEventListener('click',()=>showPanel('login'));
document.getElementById('reset-cancel-2')?.addEventListener('click',()=>showPanel('login'));
document.getElementById('otp-cancel')?.addEventListener('click',()=>showPanel('login'));
document.getElementById('mfa-cancel')?.addEventListener('click',()=>showPanel('login'));
document.getElementById('mfa-toggle-recovery')?.addEventListener('click',ev=>{
  const inp=document.getElementById('mfa-code'),lbl=document.getElementById('mfa-label'),hint=document.getElementById('mfa-hint');
  const toRec=inp.inputMode==='numeric';
  inp.inputMode=toRec?'text':'numeric';inp.placeholder=toRec?'XXXX-XXXX-XXXX':'000000';inp.value='';
  lbl.textContent=toRec?'Recovery code':'Authenticator code';
  hint.textContent=toRec?'Enter one of your 12-character backup recovery codes.':'Enter the 6-digit code from your authenticator app to finish signing in.';
  ev.currentTarget.textContent=toRec?'Use authenticator app instead':'Use a recovery code instead';
  inp.focus();
});
document.getElementById('mfa-form')?.addEventListener('submit',async e=>{
  e.preventDefault();busy(true);setStatus('');
  try{await post('/work/login/mfa',{code:document.getElementById('mfa-code').value});window.location.assign(target);}
  catch(err){setStatus(err.message,true);busy(false);document.getElementById('mfa-code')?.select();}
});

document.getElementById('login-form')?.addEventListener('submit',async e=>{
  e.preventDefault();busy(true);setStatus('');
  try{const data=await post('/work/login',{email:document.getElementById('login-email').value,password:document.getElementById('login-password').value});if(data.mfaRequired){enterMfa();return;}window.location.assign(target);}
  catch(err){setStatus(err.message,true);busy(false);}
});

const signupForm=document.getElementById('signup-form'),signupVerify=document.getElementById('signup-verify-form');
async function startSignUp(){
  const email=document.getElementById('signup-email').value,pw=document.getElementById('signup-password').value,cf=document.getElementById('signup-confirm').value;
  if(pw.length<8){setStatus('Password must be at least 8 characters.',true);return;}
  if(pw!==cf){setStatus('Passwords do not match.',true);return;}
  busy(true);setStatus('');
  try{await post('/work/signup/start',{email,password:pw});document.getElementById('signup-email-label').textContent=email.trim();signupForm.hidden=true;signupVerify.hidden=false;setStatus('We emailed a 6-digit verification code. It expires in 10 minutes.');busy(false);document.getElementById('signup-code').focus();}
  catch(err){setStatus(err.message,true);busy(false);}
}
signupForm?.addEventListener('submit',e=>{e.preventDefault();startSignUp();});
document.getElementById('signup-resend')?.addEventListener('click',startSignUp);
document.getElementById('signup-back')?.addEventListener('click',()=>{signupVerify.hidden=true;signupForm.hidden=false;setStatus('');});
signupVerify?.addEventListener('submit',async e=>{
  e.preventDefault();busy(true);setStatus('');
  try{await post('/work/signup/verify',{email:document.getElementById('signup-email').value,code:document.getElementById('signup-code').value});window.location.assign(target);}
  catch(err){setStatus(err.message,true);busy(false);}
});

const resetStart=document.getElementById('reset-start-form'),resetVerify=document.getElementById('reset-verify-form');
resetStart?.addEventListener('submit',async e=>{
  e.preventDefault();busy(true);setStatus('');
  try{await post('/work/password/reset/start',{email:document.getElementById('reset-email').value});resetStart.hidden=true;resetVerify.hidden=false;setStatus('If that email has an account, we sent a 6-digit reset code.');busy(false);document.getElementById('reset-code').focus();}
  catch(err){setStatus(err.message,true);busy(false);}
});
resetVerify?.addEventListener('submit',async e=>{
  e.preventDefault();busy(true);setStatus('');
  try{const data=await post('/work/password/reset/verify',{email:document.getElementById('reset-email').value,code:document.getElementById('reset-code').value,password:document.getElementById('reset-password').value});if(data.mfaRequired){enterMfa();return;}window.location.assign(target);}
  catch(err){setStatus(err.message,true);busy(false);}
});

async function signIn(mode){busy(true);try{if(!window.PublicKeyCredential)throw Error('This browser cannot use passkeys.');const options=await post('/work/passkeys/options',{mode});const response=mode==='register'?await SimpleWebAuthnBrowser.startRegistration({optionsJSON:options}):await SimpleWebAuthnBrowser.startAuthentication({optionsJSON:options});await post('/work/passkeys/verify',response);window.location.assign(target);}catch(e){setStatus(e.name==='NotAllowedError'?'Passkey sign-in was cancelled.':e.message,true);busy(false);}}
const emailForm=document.getElementById('email-form'),codeForm=document.getElementById('code-form');
async function sendCode(){busy(true);try{await post('/work/email/start',{email:document.getElementById('email').value});emailForm.hidden=true;codeForm.hidden=false;setStatus('We emailed a 6-digit code. It expires in 10 minutes.');busy(false);document.getElementById('code').focus();}catch(e){setStatus(e.message,true);busy(false);}}
if(emailForm){emailForm.onsubmit=e=>{e.preventDefault();sendCode();};document.getElementById('resend').onclick=sendCode;
codeForm.onsubmit=async e=>{e.preventDefault();busy(true);try{const data=await post('/work/email/verify',{email:document.getElementById('email').value,code:document.getElementById('code').value});if(data.mfaRequired){enterMfa();return;}window.location.assign(target);}catch(err){setStatus(err.message,true);busy(false);}};}
document.getElementById('recovery-form')?.addEventListener('submit',async e=>{e.preventDefault();busy(true);try{await post('/work/recovery/verify',{code:document.getElementById('recovery').value});window.location.assign(target);}catch(err){setStatus(err.message,true);busy(false);}});
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
    try{
      const ownerId=await owners.verifyPassword(req.body?.email,req.body?.password);
      if(owners.mfaEnabled?.(ownerId)){owners.issueMfaChallenge(res,ownerId);return res.json({ok:true,mfaRequired:true});}
      owners.issue(res,ownerId);res.json({ok:true});
    }catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/login/mfa',(req,res)=>{
    if(limited('mfa-ip:'+req.ip,30))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{owners.completeMfaLogin(req,res,req.body?.code);res.json({ok:true});}catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/password/reset/start',async(req,res)=>{
    if(limited('reset-ip:'+req.ip,8))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{res.json(await owners.startPasswordReset(req.body?.email));}catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/password/reset/verify',async(req,res)=>{
    if(limited('verify-ip:'+req.ip,30))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{
      const ownerId=await owners.verifyPasswordReset(req.body?.email,req.body?.code,req.body?.password);
      if(owners.mfaEnabled?.(ownerId)){owners.issueMfaChallenge(res,ownerId);return res.json({ok:true,mfaRequired:true});}
      owners.issue(res,ownerId);res.json({ok:true});
    }catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/email/start',async(req,res)=>{
    if(limited('email-ip:'+req.ip,8))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{res.json(await owners.startEmail(req.body?.email));}catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/email/verify',(req,res)=>{
    if(limited('verify-ip:'+req.ip,30))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});
    try{
      const ownerId=owners.verifyEmail(req.body?.email,req.body?.code);
      if(owners.mfaEnabled?.(ownerId)){owners.issueMfaChallenge(res,ownerId);return res.json({ok:true,mfaRequired:true});}
      owners.issue(res,ownerId);res.json({ok:true});
    }catch(e){res.status(400).json({error:e.message});}
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
      return res.type('html').send(page('Connect '+names,`<h1>Connect ${names}</h1><div class="big-code"><code>${escape(view.check)}</code></div><form id="to-phone"><label for="owner-email">Your Backstage email</label><input id="owner-email" type="email" autocomplete="email" required><button>Send to my phone</button></form><p id="status" role="status"></p><p style="margin-top:14px"><small><a href="${escape(signInUrl)}">Approve in browser instead</a></small></p>`,`
const request=${scriptJSON(view.request)},status=document.getElementById('status'),email=document.getElementById('owner-email');
try{email.value=localStorage.getItem('backstage-email')||'';}catch{}
if(email.value)document.querySelector('#to-phone button').focus();else email.focus();
let polling=false;
async function poll(){if(polling)return;polling=true;while(true){await new Promise(r=>setTimeout(r,1500));try{const r=await fetch('/work/connect/approve/status?request='+encodeURIComponent(request));const d=await r.json();if(d.state==='approved'){status.textContent='Connected. You can close this page.';return;}if(d.state==='denied'){status.textContent='Denied on your phone. You can close this page.';return;}if(d.state==='expired'){status.textContent='This request expired. Ask your agent to connect again.';return;}}catch{}}}
document.getElementById('to-phone').onsubmit=async e=>{e.preventDefault();const b=e.target.querySelector('button');b.disabled=true;try{const r=await fetch('/work/connect/approve/phone',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({request,email:email.value})});const d=await r.json();if(!r.ok)throw Error(d.error||'Could not send.');try{localStorage.setItem('backstage-email',email.value);}catch{}status.textContent='Sent to your phone. Tap Allow in Backstage.';b.textContent='Send again';poll();}catch(err){status.textContent=err.message;}b.disabled=false;};`,n));
    }
    res.type('html').send(page('Allow access',`<h1>Allow ${names}?</h1><div class="big-code"><code>${escape(view.check)}</code></div><p>${view.names.length>1?'These agents':'This agent'} will be able to use apps and run commands on your devices. You can pause or remove access in Backstage anytime.</p><button id="allow">Allow</button><button id="deny" class="secondary">Deny</button><p id="status" role="status"></p>`,`
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
  // Sign-in methods, MFA, sessions & billing for the signed-in owner.
  app.post('/work/account/email/start',async(req,res)=>{const id=signedIn(req,res);if(!id)return;if(limited('email-owner:'+id,10))return res.status(429).json({error:'Too many attempts. Wait a few minutes.'});try{res.json(await owners.startEmail(req.body?.email,{linkOwner:id}));}catch(e){res.status(400).json({error:e.message});}});
  app.post('/work/account/email/verify',(req,res)=>{const id=signedIn(req,res);if(!id)return;try{owners.verifyEmail(req.body?.email,req.body?.code,{expectedLinkOwner:id});res.json(owners.methods(id));}catch(e){res.status(400).json({error:e.message});}});
  app.post('/work/account/email/remove',(req,res)=>{const id=signedIn(req,res);if(!id)return;try{owners.removeEmail(id);res.json(owners.methods(id));}catch(e){res.status(400).json({error:e.message});}});
  app.post('/work/account/password',async(req,res)=>{const id=signedIn(req,res);if(!id)return;try{await owners.setPassword(id,req.body?.password,{currentPassword:req.body?.currentPassword,requireCurrent:true});res.json(owners.methods(id));}catch(e){res.status(400).json({error:e.message});}});
  app.post('/work/account/recovery',(req,res)=>{const id=signedIn(req,res);if(!id)return;res.json({codes:owners.newRecoveryCodes(id)});});
  app.post('/work/account/passkeys/remove',(req,res)=>{const id=signedIn(req,res);if(!id)return;try{owners.removePasskey(id,String(req.body?.id||''));res.json(owners.methods(id));}catch(e){res.status(400).json({error:e.message});}});
  app.post('/work/account/mfa/setup',async(req,res)=>{
    const id=signedIn(req,res);if(!id)return;
    try{
      const setup=owners.startMfaSetup(id);
      const qrSvg=await QRCode.toString(setup.uri,{type:'svg',errorCorrectionLevel:'M',margin:2,color:{dark:'#000000',light:'#ffffff'}});
      res.json({secret:setup.secret,qrSvg});
    }catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/account/mfa/confirm',(req,res)=>{
    const id=signedIn(req,res);if(!id)return;
    try{res.json(owners.confirmMfaSetup(id,req.body?.code));}catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/account/mfa/disable',async(req,res)=>{
    const id=signedIn(req,res);if(!id)return;
    try{res.json(await owners.disableMfa(id,{code:req.body?.code,password:req.body?.password}));}catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/account/sessions/sign-out-others',(req,res)=>{
    const id=signedIn(req,res);if(!id)return;
    const removed=owners.signOutOtherSessions?.(req,id)||0;
    res.json({ok:true,removed});
  });
  app.post('/work/account/billing/checkout',async(req,res)=>{
    const id=signedIn(req,res);if(!id)return;
    if(!billing)return res.status(503).json({error:'Online checkout is not enabled on this server yet.'});
    try{
      const m=owners.methods(id);
      const session=await billing.createCheckoutSession({
        ownerId:id,
        email:m.email,
        customerId:m.billing?.customerId||null,
        offer:String(req.body?.offer||'pro_monthly_promo'),
        successUrl:`${base}/account?billing=success#billing`,
        cancelUrl:`${base}/account?billing=cancelled#billing`,
      });
      res.json({ok:true,url:session.url});
    }catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/account/billing/portal',async(req,res)=>{
    const id=signedIn(req,res);if(!id)return;
    if(!billing)return res.status(503).json({error:'Billing portal is not enabled on this server yet.'});
    try{
      const m=owners.methods(id);
      const session=await billing.createPortalSession({customerId:m.billing?.customerId,returnUrl:`${base}/account#billing`});
      res.json({ok:true,url:session.url});
    }catch(e){res.status(400).json({error:e.message});}
  });
  app.post('/work/passkeys/options',async(req,res)=>{try{res.json(await owners.options(req,res,req.body.mode));}catch{res.status(400).json({error:'Could not start passkey sign-in. Try again.'});}});
  app.post('/work/passkeys/verify',async(req,res)=>{try{await owners.verify(req,res,req.body);res.json({ok:true});}catch{res.status(400).json({error:'The passkey could not be verified. Try again with the passkey you saved for Backstage.'});}});

  const statusInfo=d=>d.status==='online'?{cls:'ok',pill:'Online',hint:''}
    :d.status==='offline'?{cls:'warn',pill:'Offline',hint:'Open Android Backstage on it and tap Start'}
    :d.status==='needs_reconnect'?{cls:'bad',pill:'Needs reconnecting',hint:'This device no longer holds this link'}
    :{cls:'warn',pill:'Unknown',hint:''};

  const billingSection=(m,limits,deviceCount)=>{
    const trial=m.trial||{};
    const bill=m.billing||{};
    const maxDevices=limits.maxDevices??1,maxAssistants=limits.maxAssistants??1,maxParallel=limits.maxParallelScreens??1;
    const planDetails=`${deviceCount} of ${maxDevices} ${maxDevices===1?'device':'devices'} · ${maxAssistants===1?'1 assistant':`up to ${maxAssistants} assistants`} · ${maxParallel===1?'1 virtual display':`up to ${maxParallel} parallel displays`}`;
    const badgeLabel=trial.active?`Pro trial · ${trial.daysLeft} ${trial.daysLeft===1?'day':'days'} left`:`${limits.name||'Personal'} plan`;
    const subNote=trial.active
      ?`Free 7-day Pro trial ends ${fmtDate(trial.endsAt)}. No card on file — switches to Personal automatically unless you subscribe.`
      :bill.promoLocked
        ?`50% off launch rate ($4.50/mo) locked in while your subscription stays active.${bill.currentPeriodEnd?` Renews ${fmtDate(bill.currentPeriodEnd)}.`:''}`
        :bill.status==='past_due'
          ?'Payment past due — update your payment method in billing to keep Pro active.'
          :trial.expired
            ?'Your 7-day Pro trial ended and switched to Personal (free). You can still lock in 50% off Pro Monthly anytime.'
            :'';
    const promoBox=(m.basePlan==='free')?`<div class="promo-banner" style="margin:14px 0 0"><div class="promo-head"><span class="promo-price">$4.50<small>/mo</small></span><span class="strike">$9/mo</span><span class="plan-badge">First-month launch promo · 50% off</span></div><p>Subscribe during our first month of launch to lock in 50% off Pro — $4.50/mo monthly or $42/yr ($3.50/mo, 50% + 22% off) — for as long as you keep your subscription active.</p><div class="actions"><button type="button" class="checkout-btn" data-offer="pro_monthly_promo">Lock in Pro ($4.50/mo)</button><button type="button" class="secondary checkout-btn" data-offer="pro_yearly">Pro Yearly ($42/yr · $3.50/mo)</button><button type="button" class="secondary checkout-btn" data-offer="team_monthly">Team ($39/mo)</button></div></div>`
      :(bill.hasCustomer?`<div class="actions" style="margin-top:12px"><button type="button" class="secondary" id="billing-portal">Manage subscription &amp; invoices</button></div>`:'');
    return `<section id="billing"><div class="card"><div class="plan-bar" style="margin:0;padding:0;background:transparent;border:0"><span class="plan-badge">${escape(badgeLabel)}</span><p class="plan-stats">${escape(planDetails)}</p></div>${subNote?`<p class="meta" style="margin:10px 0 0">${escape(subNote)}</p>`:''}${promoBox}</div></section>`;
  };

  const methodsSection=m=>`<div class="grid">
<div class="card"><div class="card-head"><strong>Two-factor auth (MFA)</strong><span class="pill ${m.mfaEnabled?'ok':'warn'}"><span class="dot"></span>${m.mfaEnabled?'Enabled':'Off'}</span></div>
${m.mfaEnabled
  ?`<p class="meta">Protected with an authenticator app${m.mfaEnabledAt?` since ${escape(fmtDate(m.mfaEnabledAt))}`:''}. Required on email &amp; password sign-ins.</p><form id="mfa-disable-form"><label for="mfa-disable-code">Authenticator or recovery code</label><input id="mfa-disable-code" type="text" autocomplete="off" placeholder="000000" required><button class="danger">Turn off MFA</button></form>`
  :`<p class="meta">Require a 6-digit code from an authenticator app (Google Authenticator, 1Password, Authy, Bitwarden) when signing in.</p><button type="button" class="secondary" id="mfa-start">Enable authenticator app</button><div id="mfa-setup-box" hidden><div class="qr-frame sm" id="mfa-qr"></div><p class="meta" style="text-align:center;margin:4px 0">Or enter this setup key manually:</p><code class="secret-key" id="mfa-secret"></code><form id="mfa-confirm-form"><label for="mfa-setup-code">6-digit code from app</label><input id="mfa-setup-code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" placeholder="000000" required><button>Verify &amp; enable MFA</button></form></div>`}
</div>
<div class="card"><strong>Email</strong>${m.email?`<p class="meta"><!--email_off-->${escape(m.email)}<!--/email_off--> · used for sign-in and verification codes</p>`:`<p class="meta">${m.emailAvailable?'Add an email to sign in on any device.':'Email sign-in is not set up on this server.'}</p>`}${m.emailAvailable?`<form id="email-add"><label for="new-email">${m.email?'Change email':'Email'}</label><input id="new-email" type="email" autocomplete="email" placeholder="you@example.com" required><div class="actions"><button class="secondary">Send code</button>${m.email?'<button type="button" class="secondary" id="email-remove">Remove email</button>':''}</div></form><form id="email-confirm" hidden><label for="email-code">6-digit code</label><input id="email-code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="7" required><button>Confirm email</button></form>`:(m.email?'<div class="actions"><button type="button" class="secondary" id="email-remove">Remove email</button></div>':'')}</div>
${m.email?`<div class="card"><strong>Password</strong><p class="meta">${m.hasPassword?'Password is set. You can log in with email and password.':'No password set yet. Add one to log in with email and password.'}</p><form id="password-form">${m.hasPassword?'<label for="cur-password">Current password</label><div class="pw-wrap"><input id="cur-password" type="password" autocomplete="current-password" required><button type="button" class="pw-toggle" data-pw="cur-password">Show</button></div>':''}<label for="new-password">${m.hasPassword?'New password':'Password'}</label><div class="pw-wrap"><input id="new-password" type="password" autocomplete="new-password" minlength="8" maxlength="128" placeholder="At least 8 characters" required><button type="button" class="pw-toggle" data-pw="new-password">Show</button></div><button class="secondary">${m.hasPassword?'Change password':'Set password'}</button></form></div>`:''}
<div class="card"><strong>Passkeys</strong>${m.passkeys.length?`<div style="margin:6px 0 12px">${m.passkeys.map(k=>`<div class="pk-row" data-passkey="${escape(k.id)}"><div class="pk-info">${escape(k.label)}${k.createdAt?`<small>Added ${escape(fmtDate(k.createdAt))}</small>`:''}</div><button type="button" class="secondary btn-sm passkey-remove">Remove</button></div>`).join('')}</div>`:'<p class="meta">No passkey yet. A passkey signs you in with your fingerprint or screen lock.</p>'}<a class="button secondary" href="/work/sign-in?return_to=/work/account">Add a passkey</a></div>
<div class="card"><strong>Recovery codes</strong><p class="meta">${m.recoveryCodesLeft?`${m.recoveryCodesLeft} unused. Each signs you in once if you lose your authenticator app or other methods.`:'Save recovery codes in case you lose access to your authenticator app, email, or passkeys.'}</p><div class="actions"><button type="button" class="secondary" id="recovery-new">${m.recoveryCodesLeft?'Replace recovery codes':'Create recovery codes'}</button><button type="button" class="secondary" id="recovery-copy" hidden>Copy codes</button></div><pre id="recovery-codes" hidden></pre></div>
<div class="card"><strong>Browser sessions</strong><p class="meta">${m.activeSessions||1} active ${(m.activeSessions||1)===1?'session':'sessions'} signed in on web browsers.</p><div class="actions"><button type="button" class="secondary" id="signout-others">Sign out other browsers</button></div></div>
</div>`;

  const methodsScript=`
document.querySelectorAll('.pw-toggle').forEach(btn=>btn.addEventListener('click',()=>{const inp=document.getElementById(btn.dataset.pw);if(!inp)return;const show=inp.type==='password';inp.type=show?'text':'password';btn.textContent=show?'Hide':'Show';}));
const emailAdd=document.getElementById('email-add'),emailConfirm=document.getElementById('email-confirm');
if(emailAdd){emailAdd.onsubmit=async e=>{e.preventDefault();try{await post('/work/account/email/start',{email:document.getElementById('new-email').value});emailConfirm.hidden=false;showStatus('We emailed a 6-digit code to confirm the address.');document.getElementById('email-code')?.focus();}catch(err){showStatus(err.message,true);}};
emailConfirm.onsubmit=async e=>{e.preventDefault();try{await post('/work/account/email/verify',{email:document.getElementById('new-email').value,code:document.getElementById('email-code').value});window.location.reload();}catch(err){showStatus(err.message,true);}};}
document.getElementById('password-form')?.addEventListener('submit',async e=>{e.preventDefault();try{const cur=document.getElementById('cur-password')?.value;await post('/work/account/password',{currentPassword:cur,password:document.getElementById('new-password').value});window.location.reload();}catch(err){showStatus(err.message,true);}});
document.getElementById('email-remove')?.addEventListener('click',async()=>{if(!confirm('Remove this email? You will sign in with a passkey or recovery code.'))return;try{await post('/work/account/email/remove',{});window.location.reload();}catch(e){showStatus(e.message,true);}});
document.querySelectorAll('.passkey-remove').forEach(b=>b.onclick=async()=>{if(!confirm('Remove this passkey?'))return;try{await post('/work/account/passkeys/remove',{id:b.closest('[data-passkey]').dataset.passkey});window.location.reload();}catch(e){showStatus(e.message,true);}});
document.getElementById('recovery-new').onclick=async()=>{if(!confirm('Create new recovery codes? Old codes stop working.'))return;try{const data=await post('/work/account/recovery',{});const out=document.getElementById('recovery-codes'),copyBtn=document.getElementById('recovery-copy');out.textContent=data.codes.join('\\n');out.hidden=false;if(copyBtn)copyBtn.hidden=false;showStatus('Save these codes somewhere safe. They are shown only once.');}catch(e){showStatus(e.message,true);}};
document.getElementById('recovery-copy')?.addEventListener('click',async()=>{try{await navigator.clipboard.writeText(document.getElementById('recovery-codes').textContent);showStatus('Recovery codes copied.');}catch{showStatus('Select and copy the codes above.',true);}});
document.getElementById('mfa-start')?.addEventListener('click',async ev=>{
  const btn=ev.currentTarget;btn.disabled=true;
  try{
    const data=await post('/work/account/mfa/setup',{});
    document.getElementById('mfa-qr').innerHTML=data.qrSvg;
    document.getElementById('mfa-secret').textContent=data.secret;
    document.getElementById('mfa-setup-box').hidden=false;
    btn.hidden=true;
    document.getElementById('mfa-setup-code')?.focus();
  }catch(e){showStatus(e.message,true);btn.disabled=false;}
});
document.getElementById('mfa-confirm-form')?.addEventListener('submit',async e=>{
  e.preventDefault();const btn=e.target.querySelector('button');btn.disabled=true;
  try{
    const data=await post('/work/account/mfa/confirm',{code:document.getElementById('mfa-setup-code').value});
    if(data.recoveryCodes&&data.recoveryCodes.length){
      const out=document.getElementById('recovery-codes'),copyBtn=document.getElementById('recovery-copy');
      out.textContent=data.recoveryCodes.join('\\n');out.hidden=false;if(copyBtn)copyBtn.hidden=false;
      showStatus('MFA enabled! Save your backup recovery codes below before leaving.');
      document.getElementById('mfa-setup-box').hidden=true;
      setTimeout(()=>out.scrollIntoView({behavior:'smooth',block:'center'}),150);
    }else{window.location.reload();}
  }catch(err){showStatus(err.message,true);btn.disabled=false;}
});
document.getElementById('mfa-disable-form')?.addEventListener('submit',async e=>{
  e.preventDefault();if(!confirm('Turn off two-factor authentication on this account?'))return;
  const btn=e.target.querySelector('button');btn.disabled=true;
  try{await post('/work/account/mfa/disable',{code:document.getElementById('mfa-disable-code').value});window.location.reload();}
  catch(err){showStatus(err.message,true);btn.disabled=false;}
});
document.getElementById('signout-others')?.addEventListener('click',async ev=>{
  const btn=ev.currentTarget;btn.disabled=true;
  try{const d=await post('/work/account/sessions/sign-out-others',{});showStatus(d.removed?('Signed out '+d.removed+' other '+(d.removed===1?'browser':'browsers')+'.'):'No other active browser sessions.');}
  catch(e){showStatus(e.message,true);}finally{btn.disabled=false;}
});
document.querySelectorAll('.checkout-btn').forEach(btn=>btn.addEventListener('click',async()=>{
  btn.disabled=true;
  try{const data=await post('/work/account/billing/checkout',{offer:btn.dataset.offer});if(data.url)window.location.assign(data.url);}
  catch(e){showStatus(e.message,true);btn.disabled=false;}
}));
document.getElementById('billing-portal')?.addEventListener('click',async ev=>{
  const btn=ev.currentTarget;btn.disabled=true;
  try{const data=await post('/work/account/billing/portal',{});if(data.url)window.location.assign(data.url);}
  catch(e){showStatus(e.message,true);btn.disabled=false;}
});`;

  app.get('/work/account',async(req,res)=>{
    const ownerId=signedIn(req,res);if(!ownerId)return;const n=nonce(res),grants=connections.list(ownerId);
    const devices=await Promise.all(connections.devices(ownerId).map(d=>connections.probe(d)));
    const accountMethods=owners.methods(ownerId);
    const limits=accountMethods.limits||{name:'Personal',maxDevices:1,maxAssistants:1,maxParallelScreens:1};

    const deviceCards=devices.map(d=>{
      const st=statusInfo(d);
      return `<div class="card" data-device="${escape(d.id)}"><div class="card-head"><div><strong class="device-name">${escape(d.name)}</strong>${d.model?`<p class="meta" style="margin:2px 0 0">${escape(d.model)}</p>`:''}</div><span class="pill ${st.cls}"><span class="dot"></span>${escape(st.pill)}</span></div>${st.hint?`<p class="meta" style="margin-top:8px">${escape(st.hint)}</p>`:''}<details><summary><small>Details</small></summary><p class="meta" style="margin:6px 0 0"><small>ID <code>${escape(d.id)}</code>${d.lastSeen?` · last seen ${escape(fmtDateTime(d.lastSeen))}`:''}</small></p></details><div class="actions"><button class="secondary rename" type="button">Rename</button><a class="button secondary" href="/work/enroll?device=${escape(d.id)}">${d.status==='needs_reconnect'?'Reconnect':'Replace link'}</a><button class="secondary remove" type="button">Remove</button></div></div>`;
    }).join('');

    const accessEditor=row=>{const all=row.access==='all',allowed=new Set(Array.isArray(row.access)?row.access:[]);return devices.map(d=>`<label class="row"><input type="checkbox" name="device" value="${escape(d.id)}" data-name="${escape(d.name)}" ${all||allowed.has(d.id)?'checked':''}> ${escape(d.name)}</label>`).join('')+`<label class="row"><input type="checkbox" name="future" ${all?'checked':''}> Devices I add later</label>`;};
    const usesText=row=>row.access==='all'?(devices.length>1?'all your devices, including new ones':'your phone'):devices.filter(d=>row.access.includes(d.id)).map(d=>d.name).join(', ')||'no device yet';
    const iconFor=row=>{
      const info=auth?.clientInfo?.(row.clientId)||{};
      const asset=assistantIconAsset(row.name||info.name||'',info.origin||'');
      return `<span class="asst-icon"><img src="${escape(asset.url)}" alt="" width="24" height="24"${asset.invertOnDark?' class="invert"':''}></span>`;
    };
    const grantCards=grants.map(row=>{
      const paused=Boolean(row.paused);
      return `<div class="card" data-grant="${escape(row.id)}" data-paused="${paused?'1':'0'}"><div class="card-head"><div class="asst-head">${iconFor(row)}<div><strong>${escape(row.name||'Assistant')}</strong><p class="meta" style="margin:2px 0 0"><span class="asst-uses">Can use ${escape(usesText(row))}</span> · connected ${escape(fmtDate(row.createdAt))}</p></div></div><span class="pill warn pause-pill"${paused?'':' hidden'}><span class="dot"></span>Paused</span></div><fieldset><legend>Allowed devices</legend>${accessEditor(row)}</fieldset><div class="actions"><button class="save" type="button">Save access</button><button class="secondary toggle-pause" type="button">${paused?'Resume':'Pause'}</button><button class="danger revoke" type="button">Remove access</button></div></div>`;
    }).join('');

    const headerRight=`<a class="top-link" href="https://android-backstage.com/support/">Support</a><button id="logout" class="top-btn" type="button">Sign out</button>`;
    const body=`<h1>Account</h1><p>Manage your plan, linked Android devices, connected assistants, and security.</p>
${billingSection(accountMethods,limits,devices.length)}
<h2>Devices</h2>${deviceCards?`<div class="grid">${deviceCards}</div>`:'<div class="card"><p class="meta" style="margin:0">No device is connected yet.</p></div>'}${devices.length?`<form id="add" class="inline"><label>Name for the next device<input type="text" name="name" maxlength="32" placeholder="tablet" autocomplete="off"></label><button type="submit" class="secondary">Add a device</button></form>`:`<form id="add" class="inline"><button type="submit">Connect your phone</button></form>`}<p><small>Adding, renaming or removing a device does not disturb the others. Reconnect a device after reinstalling or resetting Android Backstage on it.</small></p>
<h2>Assistants</h2>${grantCards||'<div class="card"><p class="meta" style="margin:0">No assistant is connected yet. Connect one from your assistant using the Backstage MCP URL.</p></div>'}
<p><small>Removing one assistant leaves your devices and other assistants untouched. Pausing access inside the phone app stops every assistant on that phone.</small></p>
<h2>Signing in &amp; security</h2>${methodsSection(accountMethods)}
<h2>Danger zone</h2>
<div class="card"><div class="card-head"><div><strong>Delete account</strong><p class="meta" style="margin:4px 0 0">Permanently remove your email, passkeys, MFA, device links, and all assistant access from this server.</p></div></div><div class="actions"><button type="button" class="danger" id="delete-account">Delete account</button></div></div>
<p class="foot-note">The Backstage server holds the link for each device and relays every command in memory with zero retention for call history. The relay component and Cloudflare cannot read encrypted phone envelopes. <a href="https://android-backstage.com/privacy/">Privacy</a> · <a href="https://android-backstage.com/terms/">Terms</a></p>
<div id="status" class="toast" role="status" aria-live="polite"></div>`;

    res.type('html').send(page('Account',body,`
const status=document.getElementById('status');
let statusTimer=0;
function showStatus(msg,isError=false){
  clearTimeout(statusTimer);
  status.textContent=msg||'';
  if(isError)status.dataset.tone='error';else delete status.dataset.tone;
  if(msg)statusTimer=setTimeout(()=>{status.textContent='';delete status.dataset.tone;},6000);
}
const bState=new URLSearchParams(window.location.search).get('billing');
if(bState==='success')showStatus('Subscription active — thank you for supporting Android Backstage!');
else if(bState==='cancelled')showStatus('Checkout cancelled — your current plan is unchanged.');
async function post(route,body){const r=await fetch(route,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});const data=await r.json().catch(()=>({}));if(!r.ok)throw Error(data.error||'Could not finish. Sign in and try again.');return data;}
${methodsScript}
document.querySelectorAll('.card[data-device]').forEach(card=>{const id=card.dataset.device,name=card.querySelector('.device-name');
 card.querySelector('.rename').onclick=async()=>{const next=prompt('New name for '+name.textContent,name.textContent);if(!next||next===name.textContent)return;try{const data=await post('/work/account/devices/rename',{deviceId:id,name:next});name.textContent=data.name;showStatus('Device renamed.');}catch(e){showStatus(e.message,true);}};
 card.querySelector('.remove').onclick=async()=>{if(!confirm('Remove '+name.textContent+'? Assistants lose access to it until you add it again.'))return;try{await post('/work/account/devices/remove',{deviceId:id});window.location.reload();}catch(e){showStatus(e.message,true);}};});
document.getElementById('add').onsubmit=e=>{e.preventDefault();const name=(e.target.elements.name?.value||'').trim();window.location.assign('/work/enroll'+(name?'?name='+encodeURIComponent(name):''));};
document.querySelectorAll('.card[data-grant]').forEach(card=>{const id=card.dataset.grant;
 card.querySelector('.save').onclick=async ev=>{
  const btn=ev.currentTarget,boxes=[...card.querySelectorAll('input[name=device]')],future=card.querySelector('input[name=future]');
  const all=future.checked&&boxes.every(b=>b.checked);const checked=boxes.filter(b=>b.checked);const devices=checked.map(b=>b.value);
  if(!all&&!devices.length){showStatus('Tick at least one device, or remove access.',true);return;}
  btn.disabled=true;
  try{
   await post('/work/account/access',{grantId:id,...(all?{allDevices:true}:{devices})});
   const uses=card.querySelector('.asst-uses');
   if(uses)uses.textContent='Can use '+(all?(boxes.length>1?'all your devices, including new ones':'your phone'):checked.map(b=>b.dataset.name).join(', '));
   btn.textContent='Saved';setTimeout(()=>{btn.textContent='Save access';},1600);
   showStatus('Access saved.');
  }catch(e){showStatus(e.message,true);}finally{btn.disabled=false;}
 };
 card.querySelector('.toggle-pause')?.addEventListener('click',async ev=>{
  const btn=ev.currentTarget,nextPaused=card.dataset.paused!=='1';btn.disabled=true;
  try{
   const res=await post('/work/account/pause',{grantId:id,paused:nextPaused});
   const paused=Boolean(res.paused);card.dataset.paused=paused?'1':'0';
   btn.textContent=paused?'Resume':'Pause';
   const pill=card.querySelector('.pause-pill');if(pill)pill.hidden=!paused;
   showStatus(paused?'Assistant paused.':'Assistant resumed.');
  }catch(e){showStatus(e.message,true);}finally{btn.disabled=false;}
 });
 card.querySelector('.revoke').onclick=async ev=>{if(!confirm('Remove access for this assistant?'))return;ev.target.disabled=true;try{await post('/work/account/revoke',{grantId:id});card.remove();showStatus('Assistant removed.');}catch(e){showStatus(e.message,true);ev.target.disabled=false;}};});
document.getElementById('logout').onclick=async()=>{await fetch('/work/account/sign-out',{method:'POST'});window.location.assign('/work/sign-in');};
document.getElementById('delete-account').onclick=async()=>{if(prompt('Type DELETE to permanently delete your Android Backstage account, all device links, and all assistant access.')!=='DELETE')return;try{await post('/work/account/delete',{confirm:'DELETE'});window.location.assign('/work/sign-in');}catch(e){showStatus(e.message,true);}};`,n,{wide:true,headerRight}));
  });
  app.post('/work/account/revoke',(req,res)=>{const id=signedIn(req,res);if(!id)return;try{connections.revokeOwned(id,req.body.grantId);auth?.revokeGrants?.([req.body.grantId]);artifacts?.removeForGrants?.([req.body.grantId]);res.json({ok:true});}catch(e){problem(res,e,404);}});
  app.post('/work/account/pause',(req,res)=>{const id=signedIn(req,res);if(!id)return;try{const paused=connections.setPaused(id,String(req.body?.grantId||''),req.body?.paused===true);res.json({ok:true,paused});}catch(e){problem(res,e,404);}});
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
    await phoneApi?.purgeOwner?.(id);
    owners.deleteOwner(id,req,res);
    res.json({ok:true,deleted:true});
  });

  // Add a device (?name=) or reconnect an existing one (?device=ID) under the same ID.
  app.get('/work/enroll',async(req,res)=>{
    const ownerId=signedIn(req,res);if(!ownerId)return;
    let deviceId=typeof req.query.device==='string'?req.query.device:null,name=typeof req.query.name==='string'&&validDeviceName(req.query.name)?req.query.name.trim():null;
    // "Connect with your account" from a phone that already has a slot with this name and is not online: reconnect that slot instead of adding "name 2".
    if(!deviceId&&name){const same=connections.devices(ownerId).find(d=>d.name.toLowerCase()===name.toLowerCase());if(same){const live=await connections.probe(same);if(!live.online){deviceId=same.id;name=null;}}}
    if(deviceId&&!connections.devices(ownerId).some(d=>d.id===deviceId))return res.status(404).type('html').send(page('Device not found','<h1>Device not found</h1><p>It may have been removed. Add it again from your account.</p><a class="button" href="/work/account">Back to account</a>'));
    for(const [id,row] of pending)if(row.expires<=now()){pending.delete(id);row.connection.cancel().catch(()=>{});}
    try{
      let entry=[...pending].find(([,row])=>row.ownerId===ownerId&&row.deviceId===deviceId&&row.name===name);
      if(!entry){if(pending.size>=100)throw Error('Busy');const ticket=crypto.randomBytes(32).toString('base64url');const connection=await connections.begin({ownerId,clientName:'Android Backstage',deviceId,name});const row={ownerId,deviceId,name,connection,expires:now()+600000};pending.set(ticket,row);entry=[ticket,row];}
      res.type('html').send(await connectionPage({base,ticket:entry[0],nonce:nonce(res),connection:entry[1].connection,pollPath:'/enroll',heading:deviceId?`Reconnect ${entry[1].connection.deviceName}`:`Connect ${entry[1].connection.deviceName}`}));
    }catch(e){
      if(e instanceof ConnectionError){
        const code=({plan_limit:403,busy:429,unknown_device:404}[e.code]||400);
        return res.status(code).type('html').send(page('Cannot add device',`<h1>Cannot add device</h1><p>${escape(e.message)}</p><a class="button" href="/work/account#billing">Manage plan &amp; billing</a>`));
      }
      res.status(503).type('html').send(page('Could not connect','<h1>Could not connect</h1><p>Return to your account and try again.</p><a class="button" href="/work/account">Back to account</a>'));
    }
  });
  app.post('/work/enroll',async(req,res)=>{
    const ownerId=signedIn(req,res);if(!ownerId)return;const row=pending.get(req.body.ticket);
    if(!row||row.ownerId!==ownerId||row.expires<=now())return res.status(403).json({error:'Request expired.'});
    if(req.body.cancel){pending.delete(req.body.ticket);await row.connection.cancel().catch(()=>{});return res.json({cancelled:true});}
    if(row.busy)return res.json({pending:true});row.busy=true;
    try{const id=await row.connection.poll();if(!pending.has(req.body.ticket)||row.expires<=now())return res.status(403).json({error:'Request expired.'});if(!id)return res.json({pending:true});pending.delete(req.body.ticket);res.json({redirect:base+'/account'});}catch{pending.delete(req.body.ticket);res.status(400).json({error:'Could not connect. Try again.'});}finally{row.busy=false;}
  });
}
