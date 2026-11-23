/** Share the existing HTTPS hostname while keeping the blind relay and Work service separate. */
import http from 'node:http';
import net from 'node:net';
import { createRelay } from '../relay/server.mjs';
const publicPort = Number(process.env.PORT || 18421);
const zeroRetention = process.env.RELAY_ZERO_RETENTION !== '0';
const relayHost = process.env.BACKSTAGE_INTERNAL_RELAY_HOST || '127.0.0.1';
const relayUpstreamHost = (relayHost === '0.0.0.0' || relayHost === '::') ? '127.0.0.1' : relayHost;
const relayPort = Number(process.env.BACKSTAGE_INTERNAL_RELAY_PORT || 18420);
const workHost = process.env.BACKSTAGE_WORK_UPSTREAM_HOST || '127.0.0.1';
const workPort = Number(process.env.BACKSTAGE_WORK_UPSTREAM_PORT || 18424);
const relay = await createRelay({port:relayPort,host:relayHost,zeroRetention});
const front = http.createServer((req,res)=>{
  let pathname;try {pathname=new URL(req.url,'http://localhost').pathname;}catch{res.writeHead(400).end();return;}
  const work = pathname === '/.well-known/assetlinks.json' || pathname === '/work' || pathname.startsWith('/work/') || pathname === '/.well-known/oauth-authorization-server/work' || pathname === '/.well-known/oauth-protected-resource/work/mcp';
  // Cloudflare also answers plain HTTP for this hostname. Never let a token travel unencrypted: send browsers to HTTPS and refuse everything else.
  let scheme=String(req.headers['x-forwarded-proto']||'').split(',')[0].trim();
  try {if(!scheme&&req.headers['cf-visitor'])scheme=JSON.parse(req.headers['cf-visitor']).scheme||'';}catch{}
  if(scheme==='http'){
    if(req.method==='GET'||req.method==='HEAD'){res.writeHead(308,{location:'https://'+(req.headers.host||'relay.android-backstage.com')+req.url,'strict-transport-security':'max-age=31536000'});res.end();return;}
    res.writeHead(400,{'content-type':'application/json'});res.end('{"error":"https_required"}');return;
  }
  const headers={...req.headers};
  // Cloudflare overwrites this header; the front listener is loopback-only.
  // Never trust an arbitrary client-supplied forwarding chain.
  headers['x-forwarded-for']=net.isIP(req.headers['cf-connecting-ip']||'')?req.headers['cf-connecting-ip']:req.socket.remoteAddress;
  const upstream=http.request({host:work?workHost:relayUpstreamHost,port:work?workPort:relay.port,method:req.method,path:req.url,headers},reply=>{
    // Browser-facing hardening for every response, whichever service answered.
    const out={...reply.headers,'strict-transport-security':'max-age=31536000','x-content-type-options':'nosniff','referrer-policy':'no-referrer','x-frame-options':'DENY','permissions-policy':'camera=(), microphone=(), geolocation=(), payment=()','cross-origin-opener-policy':'same-origin','cache-control':reply.headers['cache-control']||'no-store'};
    res.writeHead(reply.statusCode,out);reply.pipe(res);
  });
  upstream.setTimeout(660000,()=>upstream.destroy());
  upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502,{'content-type':'application/json'});res.end('{"error":"service_unavailable"}');});
  req.on('aborted',()=>upstream.destroy());res.on('close',()=>upstream.destroy());req.pipe(upstream);
});
front.listen(publicPort,process.env.HOST || '127.0.0.1',()=>console.log('Backstage HTTPS services ready on loopback'));
// Phones on the tailnet download transfer files straight from this computer instead of
// through Cloudflare. Only tailnet peers are answered, only for capability file URLs
// (ciphertext, unguessable IDs), and the listener forwards nothing else.
const directFilesPort=Number(process.env.BACKSTAGE_DIRECT_FILES_PORT||0);
let directFiles=null;
if(directFilesPort){
  const tailnetPeer=address=>/^(::ffff:)?100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(address||'')||/^fd7a:115c:a1e0:/i.test(address||'');
  directFiles=http.createServer((req,res)=>{
    let pathname;try {pathname=new URL(req.url,'http://localhost').pathname;}catch{res.writeHead(400).end();return;}
    if(!['GET','HEAD'].includes(req.method)||!/^\/v1\/files\/[a-f0-9]{64}$/i.test(pathname)){res.writeHead(404,{'content-type':'application/json'}).end('{"error":"not_found"}');return;}
    const headers={host:'127.0.0.1','x-forwarded-for':req.socket.remoteAddress};
    if(req.headers.range)headers.range=req.headers.range;
    const upstream=http.request({host:'127.0.0.1',port:relay.port,method:req.method,path:pathname,headers},reply=>{res.writeHead(reply.statusCode,reply.headers);reply.pipe(res);});
    upstream.setTimeout(660000,()=>upstream.destroy());
    upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end();});
    res.on('close',()=>upstream.destroy());upstream.end();
  });
  directFiles.on('connection',socket=>{if(!tailnetPeer(socket.remoteAddress))socket.destroy();});
  directFiles.on('error',error=>console.error('Direct file listener unavailable:',error.code));
  directFiles.listen(directFilesPort,process.env.BACKSTAGE_DIRECT_FILES_HOST||'0.0.0.0',()=>console.log('Direct tailnet file transfers on port '+directFilesPort));
}
// Phone long polls would hold the relay open until systemd kills it 15 s later; end them now (phones reconnect).
for(const signal of ['SIGINT','SIGTERM'])process.on(signal,async()=>{front.close();directFiles?.close();front.closeAllConnections?.();directFiles?.closeAllConnections?.();relay.server.closeAllConnections?.();
  await Promise.race([relay.close(),new Promise(resolve=>setTimeout(resolve,5000))]);process.exit(0);});
