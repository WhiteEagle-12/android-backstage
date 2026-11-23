/**
 * Cloudflare ends a proxied request that sends nothing for 100 s (error 524), so a
 * long install or push looked failed to the agent while the phone kept working, and
 * the agent's retry joined the same call. A slow JSON reply starts early instead:
 * status and headers go out, then a space every few seconds until the transport
 * writes the result. JSON parsers skip leading whitespace, so clients read the same
 * reply. Fast replies are untouched.
 */
export function jsonKeepAlive(res,{firstMs=25000,everyMs=20000}={}){
  let ours=false;
  let timer=setTimeout(function beat(){
    if(res.writableEnded||res.destroyed)return;
    // Never write into a reply the transport has started.
    if(res.headersSent&&!ours)return;
    if(!ours){
      ours=true;
      res.writeHead(200,{'Content-Type':'application/json','Cache-Control':'no-store'});
      // The MCP transport sets status and headers when it answers; they are already sent.
      res.writeHead=function(){return this;};
      res.setHeader=function(){return this;};
    }
    res.write(' ');
    timer=setTimeout(beat,everyMs);
  },firstMs);
  const stop=()=>clearTimeout(timer);
  res.on('finish',stop);res.on('close',stop);
  return stop;
}
