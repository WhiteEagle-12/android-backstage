import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createOwners} from '../owners.mjs';
import {createAuth} from '../auth.mjs';

const base='https://relay.example/work';
const proof=()=>{const verifier=crypto.randomBytes(32).toString('base64url');return {verifier,challenge:crypto.createHash('sha256').update(verifier).digest('base64url')};};
const tmp=t=>{const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-accounts-'));t.after(()=>fs.rmSync(dir,{recursive:true,force:true}));return dir;};
function mailbox(){const sent=[];return {sent,mailer:{configured:()=>true,send:async(to,subject,text)=>{sent.push({to,subject,code:/(\d{6})/.exec(text)[1]});}}};}

test('an emailed code signs in, creates an account for a new address, and is single use',async t=>{
  const dir=tmp(t),box=mailbox(),owners=createOwners({stateFile:path.join(dir,'owners.json'),origin:new URL(base).origin,mailer:box.mailer});
  await owners.startEmail('Gavin@Example.com');assert.equal(box.sent[0].to,'gavin@example.com');
  assert.throws(()=>owners.verifyEmail('gavin@example.com','000000'===box.sent[0].code?'111111':'000000'),/not right/);
  const id=owners.verifyEmail('gavin@example.com',box.sent[0].code);assert.ok(id.startsWith('owner_'));
  assert.throws(()=>owners.verifyEmail('gavin@example.com',box.sent[0].code),/expired/);
  await owners.startEmail('gavin@example.com');assert.equal(owners.verifyEmail('gavin@example.com',box.sent[1].code),id,'the same address signs in to the same account');
  assert.equal(owners.methods(id).email,'gavin@example.com');
  await assert.rejects(owners.startEmail('not-an-email'),/valid email/);
});
test('wrong codes are limited and new addresses cannot take another account email',async t=>{
  const dir=tmp(t),box=mailbox(),owners=createOwners({stateFile:path.join(dir,'owners.json'),origin:new URL(base).origin,mailer:box.mailer});
  await owners.startEmail('a@example.com');const a=owners.verifyEmail('a@example.com',box.sent[0].code);
  await owners.startEmail('b@example.com');const b=owners.verifyEmail('b@example.com',box.sent[1].code);
  await assert.rejects(owners.startEmail('a@example.com',{linkOwner:b}),/another account/);
  await owners.startEmail('c@example.com');for(let i=0;i<5;i++)assert.throws(()=>owners.verifyEmail('c@example.com','999999'===box.sent[2].code?'888888':'999999'));
  assert.throws(()=>owners.verifyEmail('c@example.com',box.sent[2].code),/Too many|expired/,'the code stops working after five wrong tries');
  assert.notEqual(a,b);
});
test('recovery codes sign in once each and an account keeps at least one way in',async t=>{
  const dir=tmp(t),box=mailbox(),owners=createOwners({stateFile:path.join(dir,'owners.json'),origin:new URL(base).origin,mailer:box.mailer});
  await owners.startEmail('r@example.com');const id=owners.verifyEmail('r@example.com',box.sent[0].code);
  const codes=owners.newRecoveryCodes(id);assert.equal(codes.length,10);assert.match(codes[0],/^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$/);
  assert.equal(owners.verifyRecovery(codes[3].toLowerCase()),id);assert.throws(()=>owners.verifyRecovery(codes[3]),/already used|not valid/);
  assert.equal(owners.methods(id).recoveryCodesLeft,9);
  assert.equal(owners.removeEmail(id),true,'recovery codes remain, so the email can go');
  owners.newRecoveryCodes(id);
});

function requestFixture(t){
  const dir=tmp(t);const grants=[];
  const connections={authorize(ownerId,clientId,clientName,access){grants.push({ownerId,clientId,clientName,access});return 'grant_'+grants.length;},active:()=>true,ownerOf:id=>grants[Number(id.slice(6))-1]?.ownerId||null};
  const auth=createAuth({base,stateFile:path.join(dir,'auth.json'),connections,owners:{owner:()=>null}});
  return {auth,grants};
}
test('an agent request approved on the web returns one connection per named agent',t=>{
  const {auth,grants}=requestFixture(t),p=proof();
  const made=auth.requests.create({names:['Claude Code','Grok'],challenge:p.challenge});
  assert.equal(made.ownerKnown,false);assert.match(made.approveUrl,/connect\/approve\?request=req_/);
  assert.deepEqual(auth.requests.poll(made.request,p.verifier),{pending:true,check:made.check});
  assert.throws(()=>auth.requests.poll(made.request,'wrong'));
  auth.requests.decide(made.request,'owner_a',true);
  const done=auth.requests.poll(made.request,p.verifier);assert.equal(done.connections.length,2);
  assert.deepEqual(done.connections.map(c=>c.name),['Claude Code','Grok']);assert.ok(done.connections[0].access_token);
  assert.deepEqual(grants.map(g=>[g.ownerId,g.access]),[['owner_a','all'],['owner_a','all']]);
  assert.deepEqual(auth.requests.poll(made.request,p.verifier),done,'polling again returns the same result');
  assert.throws(()=>auth.requests.decide(made.request,'owner_a',true),/finished/);
});
test('an agent that already holds a connection sends its request straight to the owner and its phones',async t=>{
  const {auth}=requestFixture(t),first=proof(),second=proof();
  const one=auth.requests.create({names:['Codex'],challenge:first.challenge});auth.requests.decide(one.request,'owner_a',true);
  const token=auth.requests.poll(one.request,first.verifier).connections[0].access_token;
  const waiting=auth.requests.wait('owner_a',2000);
  const two=auth.requests.create({names:['OpenCode'],challenge:second.challenge,bearer:token});
  await waiting;assert.equal(two.ownerKnown,true);
  assert.deepEqual(auth.requests.pending('owner_a').map(r=>r.names[0]),['OpenCode']);
  assert.deepEqual(auth.requests.pending('owner_b'),[]);
  assert.throws(()=>auth.requests.decide(two.request,'owner_b',true),/another account/);
  auth.requests.decide(two.request,'owner_a',false);
  assert.deepEqual(auth.requests.poll(two.request,second.verifier),{denied:true});
  assert.deepEqual(auth.requests.pending('owner_a'),[]);
});
test('request names and proof are validated',t=>{
  const {auth}=requestFixture(t),p=proof();
  assert.throws(()=>auth.requests.create({names:[],challenge:p.challenge}));
  assert.throws(()=>auth.requests.create({names:['x'.repeat(61)],challenge:p.challenge}));
  assert.throws(()=>auth.requests.create({names:['ok'],challenge:'short'}));
});
test('new computer request with email pushes straight to phone without browser login or email enumeration',async t=>{
  const dir=tmp(t),grants=[];
  const connections={authorize(ownerId,clientId,clientName,access){grants.push({ownerId,clientId,clientName,access});return 'grant_'+grants.length;},active:()=>true};
  const owners={owner:()=>null,ownerByEmail:e=>String(e||'').toLowerCase()==='gavin@example.com'?'owner_g':null};
  const auth=createAuth({base,stateFile:path.join(dir,'auth.json'),connections,owners});
  const p1=proof(),p2=proof();
  const waiting=auth.requests.wait('owner_g',2000);
  const known=auth.requests.create({names:['Claude Code','Codex'],challenge:p1.challenge,email:'Gavin@Example.com'});
  await waiting;
  assert.equal(known.ownerKnown,true);
  assert.deepEqual(auth.requests.pending('owner_g').map(r=>r.names),[['Claude Code','Codex']]);
  // Unknown email returns identical shape without revealing whether an account exists.
  const unknown=auth.requests.create({names:['Claude Code'],challenge:p2.challenge,email:'nobody@example.com'});
  assert.equal(unknown.ownerKnown,true);
  assert.equal(auth.requests.pending('owner_g').length,1);
  auth.requests.decide(known.request,'owner_g',true);
  const done=auth.requests.poll(known.request,p1.verifier);
  assert.equal(done.connections.length,2);
  assert.equal(auth.requests.statusPublic(known.request).state,'approved');
});
