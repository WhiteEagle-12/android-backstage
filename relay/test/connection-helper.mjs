import assert from 'node:assert/strict';
import {httpJson,deviceHeaders} from './helpers.mjs';
import {Spake2,ALICE_NAME,BOB_NAME} from '../../cli/spake2.mjs';
import {linkKey,confirmB,confirmA,b64url,unb64url} from '../../cli/crypto.mjs';
export async function approve(url,connection,token) {
  const session = connection.code.split('-')[0];
  const bob=new Spake2('bob',BOB_NAME,ALICE_NAME);
  const msg=b64url(bob.generateMsg(Buffer.from(connection.code)));
  const claim=await httpJson(`${url}/v1/pair/session/${session}/device`,{method:'POST',headers:token?deviceHeaders(token):{},body:{msg,model:'Test phone',sdk:34}});
  assert.equal(claim.status,200);
  const key=linkKey(bob.processMsg(unb64url(claim.body.agentMsg)));
  const proof=await httpJson(`${url}/v1/pair/session/${session}/device-confirm`,{method:'PUT',headers:deviceHeaders(claim.body.deviceToken),body:{confirm:b64url(confirmB(key))}});
  assert.equal(proof.status,200);
  const config=await connection.poll();
  const ack=await httpJson(`${url}/v1/pair/session/${session}/confirm`,{headers:deviceHeaders(claim.body.deviceToken)});
  assert.equal(ack.body.confirm,b64url(confirmA(key)));
  return {config,claim:claim.body};
}

