import crypto from 'node:crypto';
import {Spake2, ALICE_NAME, BOB_NAME, randomCodeChars} from './spake2.mjs';
import {linkKey, confirmA, confirmB, b64url, unb64url, timingSafeEqualBuf} from './crypto.mjs';

// Pairing secrets and link keys stay in this process, never in relay URLs.
export async function beginConnection({relayUrl, name='phone', clientName='Assistant', fetchImpl=fetch}) {
  const origin = new URL(relayUrl);
  if (origin.username || origin.password || origin.search || origin.hash || origin.pathname !== '/' ||
      (origin.protocol !== 'https:' && !(origin.protocol === 'http:' && ['127.0.0.1','localhost','[::1]'].includes(origin.hostname))))
    throw new Error('Use an HTTPS relay address.');
  relayUrl = origin.origin;
  let token;
  const request = async (route, method='GET', body) => {
    const response = await fetchImpl(relayUrl+route, {method, redirect:'error', signal:AbortSignal.timeout(30000),
      headers:{'Content-Type':'application/json', ...(token ? {Authorization:'Bearer '+token} : {})},
      ...(body ? {body:JSON.stringify(body)} : {})});
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || 'Could not connect. Try again.');
    return data;
  };
  const session = await request('/v1/pair/connect','POST',{name});
  token = session.token;
  const route = '/v1/pair/session/'+session.sessionId;
  const code = session.sessionId+'-'+randomCodeChars(6);
  const agentId = 'agt_'+crypto.randomBytes(8).toString('hex');
  const alice = new Spake2('alice', ALICE_NAME, BOB_NAME);
  await request(route+'/agent','PUT',{agentId,msg:b64url(alice.generateMsg(Buffer.from(code)))});
  const link = new URL('androidbackstage://connect');
  link.searchParams.set('relay',relayUrl); link.searchParams.set('code',code); link.searchParams.set('name',clientName);
  let completed, verified, inFlight, cancelled=false;
  async function poll() {
    if (cancelled) throw new Error('Connection cancelled. Start again.');
    if (completed) return completed;
    if (Date.now() >= Date.parse(session.expiresAt)) throw new Error('Connection expired. Start again.');
    let device, key;
    if (verified) ({device,key}=verified);
    else {
      device = await request(route+'/device');
      if (!device.msg || !device.confirm) return null;
      key = linkKey(alice.processMsg(unb64url(device.msg)));
      if (!timingSafeEqualBuf(confirmB(key),unb64url(device.confirm))) {
        await cancel(); throw new Error('The connection code did not match. Start again.');
      }
      verified={device,key};
    }
    if (cancelled) throw new Error('Connection cancelled. Start again.');
    await request(route+'/confirm','PUT',{confirm:b64url(confirmA(key))});
    completed = {relayUrl, apiKey:token, agentId, device:device.name,
      links:{[device.name]:{deviceId:device.deviceId,linkKey:b64url(key),callCounter:String(Date.now()),lastResultCounter:'0'}}};
    return completed;
  }
  async function cancel() { cancelled=true; await request(route,'DELETE'); }
  const phoneLink = new URL('https://android-backstage.com/connect/');
  phoneLink.hash = new URLSearchParams({relay:relayUrl,code,name:clientName,expires:session.expiresAt}).toString();
  return {code, link:link.href, phoneLink:phoneLink.href, expiresAt:session.expiresAt, cancel,
    poll() { if (!inFlight) inFlight=poll().finally(()=>{inFlight=null;}); return inFlight; }};
}
