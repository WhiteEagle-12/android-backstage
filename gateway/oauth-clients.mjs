import https from 'node:https';
import dns from 'node:dns/promises';
import ipaddr from 'ipaddr.js';

function publicIP(address) {
  try { return ipaddr.process(address).range() === 'unicast'; } catch { return false; }
}
export function validRedirect(value) {
  try {
    const u = new URL(value);
    if (u.username || u.password || u.hash || value.length > 2048) return false;
    if (u.protocol === 'http:') return ['localhost','127.0.0.1','[::1]'].includes(u.hostname);
    if (u.protocol !== 'https:' || ['localhost','localhost.localdomain'].includes(u.hostname) || u.hostname.endsWith('.localhost') || u.hostname.endsWith('.local')) return false;
    return !ipaddr.isValid(u.hostname.replace(/^\[|\]$/g,'')) || publicIP(u.hostname.replace(/^\[|\]$/g,''));
  } catch { return false; }
}

// Native clients choose an ephemeral loopback port at sign-in (RFC 8252 §7.3).
// All other parts of the registered callback remain bound exactly.
export function redirectMatches(registered,requested) {
  if(registered===requested)return true;
  if(!validRedirect(registered)||!validRedirect(requested))return false;
  const a=new URL(registered),b=new URL(requested);
  return a.protocol==='http:'&&b.protocol==='http:'&&a.hostname===b.hostname&&a.pathname===b.pathname&&a.search===b.search;
}

export function callbackOriginMatches(registered,origin) {
  try {
    const a=new URL(registered),b=new URL(origin);
    if(b.origin!==origin)return false;
    return a.origin===origin||(a.protocol==='http:'&&b.protocol==='http:'&&a.hostname===b.hostname&&validRedirect(registered));
  }catch{return false;}
}

// Resolve once and pin the request to a public address. Never follow redirects,
// send credentials, or fetch private network addresses while reading client data.
export async function fetchClientMetadata(clientId) {
  const url = new URL(clientId);
  if (!validRedirect(clientId) || url.protocol !== 'https:' || url.pathname === '/' || url.search || (url.port && url.port !== '443')) throw new Error('Invalid client metadata URL');
  let dnsTimer;
  const addresses = await Promise.race([dns.lookup(url.hostname,{all:true}),new Promise((_,reject)=>{dnsTimer=setTimeout(()=>reject(new Error('Client metadata lookup timed out')),5000);})]).finally(()=>clearTimeout(dnsTimer));
  if (!addresses.length || addresses.some(row=>!publicIP(row.address))) throw new Error('Client metadata must use a public address');
  const address = addresses[0];
  const data = await new Promise((resolve,reject)=>{
    const req = https.get(url,{headers:{Accept:'application/json'},lookup:(_host,opts,cb)=>opts.all ? cb(null,[address]) : cb(null,address.address,address.family)},res=>{
      if (res.statusCode !== 200 || !String(res.headers['content-type']).includes('application/json')) {res.resume();reject(new Error('Client metadata unavailable'));return;}
      let size=0;const chunks=[];
      res.on('data',chunk=>{size+=chunk.length;if(size>65536)req.destroy(new Error('Client metadata too large'));else chunks.push(chunk);});
      res.on('end',()=>{try{resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')));}catch{reject(new Error('Invalid client metadata'));}});
      res.on('error',reject);
    });
    const timer=setTimeout(()=>req.destroy(new Error('Client metadata timed out')),5000);req.on('close',()=>clearTimeout(timer));req.on('error',reject);
  });
  if (data.client_id !== clientId || typeof data.client_name !== 'string' || !data.client_name.trim() || !Array.isArray(data.redirect_uris) || !data.redirect_uris.length || data.redirect_uris.length>10 || !data.redirect_uris.every(validRedirect) || (data.token_endpoint_auth_method && data.token_endpoint_auth_method !== 'none')) throw new Error('Invalid client metadata');
  return {name:data.client_name.replace(/[\x00-\x1f\x7f]/g,'').slice(0,80),redirects:data.redirect_uris,secret:null,method:'none',metadata:true,expires:Date.now()+300000};
}
