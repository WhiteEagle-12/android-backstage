import crypto from 'node:crypto';
import {isoCBOR} from '@simplewebauthn/server/helpers';
export function passkeyFixture(origin, rpID = new URL(origin).hostname) {
  const {privateKey,publicKey}=crypto.generateKeyPairSync('ec',{namedCurve:'prime256v1'}),jwk=publicKey.export({format:'jwk'});
  const id=crypto.randomBytes(32),rpHash=crypto.createHash('sha256').update(rpID).digest();let counter=0;
  const cose=isoCBOR.encode(new Map([[1,2],[3,-7],[-1,1],[-2,new Uint8Array(Buffer.from(jwk.x,'base64url'))],[-3,new Uint8Array(Buffer.from(jwk.y,'base64url'))]]));
  const b64=value=>Buffer.from(value).toString('base64url');
  const clientData=(type,challenge,overrides)=>Buffer.from(JSON.stringify({type,challenge,origin,crossOrigin:false,...overrides}));
  return {
    register(options,overrides={}) {
      const length=Buffer.alloc(2);length.writeUInt16BE(id.length);
      const authData=Buffer.concat([rpHash,Buffer.from([0x45]),Buffer.alloc(4),Buffer.alloc(16),length,id,Buffer.from(cose)]);
      const attestation=isoCBOR.encode(new Map([['fmt','none'],['attStmt',new Map()],['authData',new Uint8Array(authData)]]));
      return {id:b64(id),rawId:b64(id),type:'public-key',clientExtensionResults:{credProps:{rk:true}},response:{clientDataJSON:b64(clientData('webauthn.create',options.challenge,overrides)),attestationObject:b64(attestation),transports:['internal']}};
    },
    authenticate(options,overrides={}) {
      const n=Buffer.alloc(4);n.writeUInt32BE(++counter);
      const authData=Buffer.concat([rpHash,Buffer.from([0x05]),n]),client=clientData('webauthn.get',options.challenge,overrides);
      const signed=Buffer.concat([authData,crypto.createHash('sha256').update(client).digest()]);
      return {id:b64(id),rawId:b64(id),type:'public-key',clientExtensionResults:{},response:{clientDataJSON:b64(client),authenticatorData:b64(authData),signature:b64(crypto.sign('sha256',signed,privateKey)),userHandle:null}};
    }
  };
}
export function cookies(response,prior='') {
  const values=new Map(prior.split('; ').filter(Boolean).map(row=>{const i=row.indexOf('=');return [row.slice(0,i),row.slice(i+1)];}));
  for(const row of response.headers.getSetCookie()){const first=row.split(';')[0],i=first.indexOf('=');values.set(first.slice(0,i),first.slice(i+1));}
  return [...values].map(([key,value])=>key+'='+value).join('; ');
}
