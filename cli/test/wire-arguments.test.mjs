import test from 'node:test';
import assert from 'node:assert/strict';
import {wireArguments} from '../wire-arguments.mjs';

test('compact conditions keep the existing phone wire form without changing values or ownership',()=>{
 const leaf={kind:'exists',selector:{label:'Ready'}},args={sessionId:'s',requestId:'write',verify:{conditions:[leaf]},text:'{"conditions":["literal text"]}'};
 const original=structuredClone(args);
 assert.deepEqual(wireArguments('android.tap',args),{...args,verify:{all:[leaf]}});
 assert.deepEqual(args,original);
 const seq={...args,steps:[{tool:'android.type',arguments:{text:args.text,selector:{label:'Field'},verify:{conditions:[leaf],match:'any'}},before:{conditions:[leaf]}}]};
 const result=wireArguments('android.sequence',seq);
 assert.deepEqual(result.steps[0].before,{all:[leaf]});
 assert.deepEqual(result.steps[0].arguments.verify,{any:[leaf]});
 assert.equal(result.steps[0].arguments.text,args.text);
 assert.equal(result.sessionId,'s');assert.equal(result.requestId,'write');
 assert.deepEqual(wireArguments('android.shell',args),args);
});

test('invalid or legacy conditions pass through unchanged for strict phone validation',()=>{
 const leaf={kind:'exists',selector:{label:'Ready'}};
 for(const verify of [{conditions:[leaf],match:'invalid'},{conditions:[leaf],match:null},{conditions:[leaf],extra:1},{conditions:'invalid'},{all:[leaf]},leaf,null]){
  assert.deepEqual(wireArguments('android.tap',{verify}),{verify});
 }
 // Invalid array lengths and nested groups remain invalid in the existing wire form.
 assert.deepEqual(wireArguments('android.tap',{verify:{conditions:[]}}).verify,{all:[]});
 assert.deepEqual(wireArguments('android.tap',{verify:{conditions:[{conditions:[leaf]}]}}).verify,{all:[{conditions:[leaf]}]});
});
