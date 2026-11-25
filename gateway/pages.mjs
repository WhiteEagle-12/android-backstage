export const escape = value => String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const scriptJSON = value => JSON.stringify(value).replaceAll('<','\\u003c');

const BASE_CSS = `:root{color-scheme:dark;--bg:#000;--surface:#0d0d0d;--surface-2:#141414;--fg:#f5f5f5;--muted:#a3a3a3;--dim:#737373;--border:rgba(255,255,255,.10);--border-hi:rgba(255,255,255,.20);--accent:#8fdc4e;--accent-hi:#a6ec68;--ink:#0c1a04;--ok:#8fdc4e;--warn:#f5b95b;--bad:#f27c7c;font:15.5px/1.5 -apple-system,BlinkMacSystemFont,"Inter Variable",Inter,"Segoe UI",system-ui,sans-serif;-webkit-font-smoothing:antialiased}
*{box-sizing:border-box}
body{margin:0;min-height:100vh;padding:20px 16px 48px;color:var(--fg);background:#000 radial-gradient(75% 42% at 50% 0%,rgba(143,220,78,.10),transparent 70%)}
main{max-width:460px;margin:5vh auto 0;background:var(--surface);padding:28px;border:1px solid var(--border);border-radius:18px}
main.wide{max-width:780px;margin:20px auto 0;padding:28px 30px}
.top{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:24px;padding-bottom:18px;border-bottom:1px solid var(--border)}
.brand{display:inline-flex;align-items:center;gap:10px;color:var(--fg);text-decoration:none;font-weight:600;font-size:15px;letter-spacing:-.02em;white-space:nowrap}
.brand img{width:26px;height:26px;border-radius:7px;display:block}
.top-right{display:inline-flex;align-items:center;gap:10px;flex-wrap:nowrap;flex-shrink:0}
.top-link{color:var(--muted);text-decoration:none;font-size:13.5px;transition:color .15s}
.top-link:hover{color:var(--fg)}
.top-btn{width:auto;margin:0;padding:7px 12px;border-radius:8px;background:var(--surface-2);border:1px solid var(--border);color:var(--fg);font-size:13px;font-weight:500;cursor:pointer}
.top-btn:hover{border-color:var(--border-hi);background:#1b1b1b}
h1{font-size:26px;font-weight:650;line-height:1.15;letter-spacing:-.03em;margin:0 0 8px}
h2{font-size:18px;font-weight:600;letter-spacing:-.02em;margin:30px 0 12px}
p{margin:8px 0;color:var(--muted);overflow-wrap:anywhere}
a{color:var(--fg);text-underline-offset:3px}
a:hover{color:var(--accent)}
small{font-size:13.5px;color:var(--dim)}
label{display:block;font-size:13.5px;font-weight:500;color:var(--muted);margin-top:12px}
label:first-child{margin-top:0}
input[type=text],input[type=email],input[type=password]{display:block;width:100%;padding:11px 13px;border:1px solid var(--border-hi);border-radius:10px;background:#070707;color:var(--fg);font:inherit;margin-top:6px;margin-bottom:4px;transition:border-color .15s}
input[type=text]:focus,input[type=email]:focus,input[type=password]:focus{outline:none;border-color:var(--accent)}
input::placeholder{color:var(--dim)}
button,.button{display:inline-flex;align-items:center;justify-content:center;gap:8px;width:100%;padding:12px 16px;margin-top:14px;border:1px solid transparent;border-radius:10px;background:var(--accent);color:var(--ink);font:inherit;font-weight:650;text-align:center;text-decoration:none;cursor:pointer;transition:background .15s,border-color .15s,opacity .15s}
button:hover,.button:hover{background:var(--accent-hi);color:var(--ink)}
button:disabled{opacity:.55;cursor:wait}
.secondary{background:var(--surface-2);border-color:var(--border);color:var(--fg);font-weight:550}
.secondary:hover{background:#1c1c1c;border-color:var(--border-hi);color:var(--fg)}
.danger{background:rgba(242,124,124,.08);border-color:rgba(242,124,124,.28);color:var(--bad);font-weight:550}
.danger:hover{background:rgba(242,124,124,.16);border-color:rgba(242,124,124,.45);color:#ff9999}
.btn-sm{width:auto;margin:0;padding:8px 13px;font-size:13.5px;border-radius:8px;white-space:nowrap}
button:focus-visible,a:focus-visible,summary:focus-visible{outline:2px solid var(--accent);outline-offset:3px}
[hidden]{display:none!important}
.tabs{display:grid;grid-template-columns:1fr 1fr;gap:4px;background:#070707;border:1px solid var(--border);padding:4px;border-radius:11px;margin:16px 0 18px}
.tabs button{margin:0;padding:9px 10px;border-radius:8px;background:transparent;border:0;color:var(--muted);font-size:14px;font-weight:550;white-space:nowrap}
.tabs button[aria-selected=true]{background:var(--surface-2);color:var(--fg);box-shadow:0 1px 2px rgba(0,0,0,.4)}
.link-row{display:flex;flex-wrap:wrap;justify-content:space-between;align-items:center;gap:6px 16px;margin-top:12px}
.link-btn{background:transparent;border:0;color:var(--muted);padding:4px 0;margin:0;font-size:13.5px;font-weight:500;text-align:left;width:auto;white-space:nowrap}
.link-btn:hover{background:transparent;color:var(--accent)}
.divider{border:0;border-top:1px solid var(--border);margin:20px 0 16px}
details{margin-top:14px}
summary{cursor:pointer;font-size:14px;color:var(--muted);user-select:none}
summary:hover{color:var(--fg)}
.plan-bar{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:10px 16px;padding:14px 16px;margin:16px 0 6px;border-radius:12px;background:var(--surface-2);border:1px solid var(--border)}
.plan-badge{display:inline-flex;align-items:center;gap:6px;padding:3px 9px;border-radius:999px;background:rgba(143,220,78,.14);border:1px solid rgba(143,220,78,.32);color:var(--accent);font-size:12px;font-weight:650;letter-spacing:.02em;text-transform:uppercase}
.plan-stats{font-size:13.5px;color:var(--muted);margin:0}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(290px,1fr));gap:12px}
.card{border:1px solid var(--border);border-radius:14px;padding:16px 18px;margin:12px 0;background:#0a0a0a}
.grid .card{margin:0}
.card-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}
.card strong{font-size:16.5px;font-weight:600;color:var(--fg)}
.meta{color:var(--muted);font-size:13.5px;margin:4px 0 10px}
.pill{display:inline-flex;align-items:center;gap:6px;font-size:12.5px;font-weight:550;padding:3px 9px;border-radius:999px;background:var(--surface-2);border:1px solid var(--border);white-space:nowrap}
.pill.ok{color:var(--ok);border-color:rgba(143,220,78,.28);background:rgba(143,220,78,.08)}
.pill.warn{color:var(--warn);border-color:rgba(245,185,91,.30);background:rgba(245,185,91,.09)}
.pill.bad{color:var(--bad);border-color:rgba(242,124,124,.30);background:rgba(242,124,124,.09)}
.dot{width:7px;height:7px;border-radius:50%;background:currentColor;flex:none}
.ok{color:var(--ok)}.warn{color:var(--warn)}.bad{color:var(--bad)}
.asst-head{display:flex;align-items:center;gap:12px;min-width:0}
.asst-icon{width:38px;height:38px;border-radius:10px;background:#161616;border:1px solid var(--border);display:inline-flex;align-items:center;justify-content:center;flex:0 0 38px;overflow:hidden}
.asst-icon img{width:24px;height:24px;object-fit:contain;display:block}
.asst-icon img.invert{filter:invert(1) brightness(1.1)}
.actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}
.actions button,.actions .button{width:auto;flex:0 1 auto;margin:0;padding:8px 13px;font-size:13.5px;border-radius:8px}
fieldset{border:1px solid var(--border);border-radius:10px;padding:10px 14px;margin:12px 0;background:#060606}
legend{font-size:12.5px;font-weight:600;color:var(--muted);padding:0 6px;letter-spacing:.02em;text-transform:uppercase}
.row{display:flex;align-items:center;gap:10px;padding:7px 0;margin:0;color:var(--fg);font-size:14.5px;font-weight:450;cursor:pointer}
.row input[type=checkbox]{width:18px;height:18px;margin:0;accent-color:var(--accent);flex:none}
.inline{display:flex;flex-wrap:wrap;gap:10px;align-items:flex-end;margin:14px 0 8px}
.inline label{flex:1 1 220px;min-width:0;margin:0}
.inline input{margin:6px 0 0}
.inline button{width:auto;flex:0 0 auto;margin:0;padding:11px 18px;white-space:nowrap}
.pk-row{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 0;border-bottom:1px solid var(--border)}
.pk-row:last-of-type{border-bottom:0}
.pk-info{min-width:0;font-size:14px;color:var(--fg)}
.pk-info small{display:block;font-size:12.5px;color:var(--dim)}
pre{background:#060606;border:1px solid var(--border);border-radius:10px;padding:14px;font:13.5px/1.6 ui-monospace,"SF Mono",Menlo,Consolas,monospace;color:var(--fg);overflow:auto;margin:12px 0 0}
code{font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace}
.qr-frame{background:#fff;padding:16px;border-radius:16px;width:fit-content;margin:18px auto}
.qr-frame svg{display:block;width:220px;max-width:100%;height:auto}
.qr-frame.sm{padding:12px;margin:12px auto}
.qr-frame.sm svg{width:176px}
.pw-wrap{position:relative;display:flex;align-items:center}
.pw-wrap input{padding-right:64px}
.pw-toggle{position:absolute;right:8px;top:50%;transform:translateY(-46%);width:auto;margin:0;padding:4px 8px;border:0;border-radius:6px;background:transparent;color:var(--muted);font-size:12.5px;font-weight:550;cursor:pointer}
.pw-toggle:hover{background:var(--surface-2);color:var(--fg)}
.promo-banner{padding:14px 16px;margin:14px 0 18px;border-radius:12px;background:linear-gradient(135deg,rgba(143,220,78,.11),rgba(143,220,78,.03));border:1px solid rgba(143,220,78,.32)}
.promo-banner>strong{display:block;font-size:14.5px;color:var(--fg);margin-bottom:3px}
.promo-banner p strong{color:var(--fg);font-weight:600}
.promo-banner p{margin:0;font-size:13.5px;color:var(--muted)}
.promo-head{display:flex;flex-wrap:wrap;align-items:baseline;gap:8px 10px;margin-bottom:6px}
.promo-price{font-size:24px;font-weight:700;color:var(--fg);letter-spacing:-.02em}
.promo-price small{font-size:14px;font-weight:500;color:var(--muted)}
.strike{text-decoration:line-through;color:var(--dim);font-size:15px;font-weight:500}
.secret-key{display:block;padding:10px 12px;margin:10px 0;border-radius:8px;background:#060606;border:1px solid var(--border);font:600 13.5px/1.4 ui-monospace,"SF Mono",Menlo,Consolas,monospace;letter-spacing:1.4px;text-align:center;color:var(--fg);overflow-wrap:anywhere}
.big-code{display:block;font:700 28px/1.2 ui-monospace,"SF Mono",Menlo,Consolas,monospace;letter-spacing:2.5px;text-align:center;padding:14px;margin:14px 0;background:#070707;border:1px solid var(--border);border-radius:12px;color:var(--fg)}
#status{min-height:1.4em;font-size:14px;color:var(--fg);margin-top:12px}
#status[data-tone=error]{color:#ffb4b4}
#status.toast{position:fixed;top:16px;right:16px;z-index:100;margin:0;min-height:0;max-width:min(400px,calc(100vw - 32px));padding:12px 16px;border-radius:12px;background:#161b14;border:1px solid rgba(143,220,78,.35);color:var(--fg);box-shadow:0 12px 32px rgba(0,0,0,.6)}
#status.toast[data-tone=error]{background:#1f1212;border-color:rgba(242,124,124,.45);color:#ffd6d6}
#status.toast:empty{display:none}
.foot-note{margin-top:24px;padding-top:16px;border-top:1px solid var(--border);font-size:13px;color:var(--dim);line-height:1.55}
@media (max-width:560px){
  body{padding:12px 12px 36px}
  main,main.wide{margin-top:12px;padding:20px 18px;border-radius:16px}
  h1{font-size:23px}
  .inline button{width:100%}
  .actions button,.actions .button{flex:1 1 auto}
}`;

export const page = (title, body, script='', nonce='', opts={}) => {
  const wide = opts.wide ? ' class="wide"' : '';
  const right = opts.headerRight || `<a class="top-link" href="https://android-backstage.com/">Back to site</a>`;
  const header = `<header class="top"><a class="brand" href="https://android-backstage.com/"><img src="https://android-backstage.com/backstage.png" width="26" height="26" alt=""><span>Android Backstage</span></a><div class="top-right">${right}</div></header>`;
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#000000"><link rel="icon" href="https://android-backstage.com/backstage.png"><title>${escape(title)} · Android Backstage</title><style>${BASE_CSS}</style></head><body><main${wide}>${header}${body}</main>${script?`<script nonce="${nonce}">${script}</script>`:''}</body></html>`;
};

export function consentPage({clientName,redirect,ticket,base,nonce,devices=[],current='all'}) {
  const all=current==='all',allowed=new Set(Array.isArray(current)?current:[]);
  const picker=devices.length?`<fieldset><legend>Devices ${escape(clientName)} may use</legend>${devices.map(d=>`<label class="row"><input type="checkbox" name="device" value="${escape(d.id)}" ${all||allowed.has(d.id)?'checked':''}> ${escape(d.name)}</label>`).join('')}<label class="row"><input type="checkbox" id="future" ${all?'checked':''}> Devices I add later</label></fieldset>`:'';
  return page('Connect assistant',`<h1>Allow ${escape(clientName)} to use your ${devices.length>1?'devices':'phone'}?</h1><p>Request from <strong>${escape(new URL(redirect).origin)}</strong></p>${picker}<p>This assistant can run phone commands and use apps on the devices you tick. You can change or remove its access in Backstage at any time.</p><p class="foot-note">The Backstage server relays each command and sees the commands your assistant sends and the results your devices return. The relay component and Cloudflare cannot read the encrypted phone envelopes. <a href="https://android-backstage.com/privacy/">Privacy</a> · <a href="https://android-backstage.com/terms/">Terms</a></p><button id="connect">Connect</button><button id="cancel" class="secondary">Cancel</button><p id="status" role="status"></p>`, `
const endpoint=${scriptJSON(base+'/authorize')},ticket=${scriptJSON(ticket)},status=document.getElementById('status');
function selection(){const future=document.getElementById('future'),boxes=[...document.querySelectorAll('input[name=device]')];if(!boxes.length)return {allDevices:true};if(future&&future.checked&&boxes.every(b=>b.checked))return {allDevices:true};return {devices:boxes.filter(b=>b.checked).map(b=>b.value)};}
async function send(cancel){const picked=selection();if(!cancel&&picked.devices&&!picked.devices.length){status.textContent='Tick at least one device, or cancel.';return;}document.querySelectorAll('button').forEach(b=>b.disabled=true);try{const r=await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ticket,...(cancel?{cancel:true}:{approve:true,...picked})})});const data=await r.json();if(!r.ok)throw Error(data.error==='choose_device'?'Tick at least one device.':'This request expired. Return to your assistant and connect again.');if(data.redirect)window.location.assign(data.redirect);else status.textContent='Connection cancelled. You can close this page.';}catch(e){status.textContent=e.message;document.querySelectorAll('button').forEach(b=>b.disabled=false);}}
document.getElementById('connect').onclick=()=>send(false);document.getElementById('cancel').onclick=()=>send(true);`,nonce);
}

/** OAuth approval completed from the Backstage app: the owner types this code on the phone. */
export function phoneApprovalPage({base,ticket,nonce,code,clientName,clientOrigin,passkeyUrl}) {
  const local=/^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(:|$)/.test(clientOrigin);
  return page('Connect Android Backstage',`<h1>Connect ${escape(clientName)}</h1><p>Request from <strong>${local?'an app on this computer':escape(clientOrigin)}</strong></p><form id="to-phone"><label for="owner-email">Your Backstage email</label><input id="owner-email" type="email" autocomplete="email" required><button>Send to my phone</button></form><p id="check" class="big-code" hidden></p><p id="status" role="status"></p><p id="fallback" hidden></p><details><summary>Use a connection code instead</summary><form id="invite"><label for="invitation">Connection code from Backstage</label><input id="invitation" type="text" autocomplete="off" required><button class="secondary">Connect</button></form><p><small>In Backstage, tap <strong>Connect an agent</strong> and copy the code.</small></p></details><p data-approval="${escape(code)}" style="margin-top:16px"><small><a href="${escape(passkeyUrl)}">Sign in in browser instead</a></small></p><button id="cancel" class="secondary">Cancel</button>`,`
const endpoint=${scriptJSON(base+'/authorize')},ticket=${scriptJSON(ticket)},status=document.getElementById('status'),fallback=document.getElementById('fallback');let stopped=false;
document.getElementById('invite').onsubmit=async e=>{e.preventDefault();const button=e.target.querySelector('button');button.disabled=true;try{const r=await fetch(${scriptJSON(base+'/connect/claim')},{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ticket,code:document.getElementById('invitation').value.trim()})});const data=await r.json();if(!r.ok)throw Error(data.message||'Could not connect. Copy a new code in Backstage.');e.target.hidden=true;status.textContent=data.approved?'Approved. Returning to your assistant…':'Check these six digits in Backstage on your phone, then tap Allow.';if(!data.approved){const check=document.getElementById('check');check.hidden=false;check.textContent=data.check;}}catch(err){status.textContent=err.message;button.disabled=false;}};
async function poll(){while(!stopped){try{const r=await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json','Accept':'application/json'},body:JSON.stringify({ticket})});const data=await r.json();if(stopped)return;if(!r.ok){status.textContent='This request expired. Return to your assistant and connect again.';return;}if(data.redirect){status.textContent='Approved. Returning to your assistant…';if(/^http:\\/\\/localhost[:/]/.test(data.redirect)){fallback.hidden=false;const alt=data.redirect.replace('http://localhost','http://127.0.0.1');const a=document.createElement('a');a.href=alt;a.textContent='Try 127.0.0.1 callback';fallback.replaceChildren(document.createTextNode('If localhost refuses to connect: '),a);}window.location.assign(data.redirect);return;}if(data.denied){status.textContent='Declined on your phone. You can close this page.';return;}await new Promise(r=>setTimeout(r,1500));}catch{status.textContent='Check your internet connection. Trying again…';await new Promise(r=>setTimeout(r,3000));}}}
const email=document.getElementById('owner-email');try{email.value=localStorage.getItem('backstage-email')||'';}catch{}if(email.value)document.querySelector('#to-phone button').focus();else email.focus();
document.getElementById('to-phone').onsubmit=async e=>{e.preventDefault();const button=e.target.querySelector('button');button.disabled=true;try{const r=await fetch(${scriptJSON(base+'/authorize/phone')},{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ticket,email:email.value})});const data=await r.json();if(!r.ok)throw Error(data.error||'Could not send.');try{localStorage.setItem('backstage-email',email.value);}catch{}if(data.check){const check=document.getElementById('check');check.hidden=false;check.textContent=data.check;status.textContent='Your phone shows this request. Check the digits match, then tap Allow.';}else status.textContent='If that email has a Backstage account, its phones now show the request.';button.textContent='Send again';}catch(err){status.textContent=err.message;}button.disabled=false;};
document.getElementById('cancel').onclick=async()=>{stopped=true;status.textContent='Cancelled. You can close this page.';document.getElementById('cancel').disabled=true;try{await fetch(endpoint,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ticket,cancel:true})});}catch{}};poll();`,nonce);
}
