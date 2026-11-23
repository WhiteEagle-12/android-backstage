import test from 'node:test';
import assert from 'node:assert/strict';
import Ajv from 'ajv';
import {strictCatalogue as catalogue,catalogue as advertised} from '../server.mjs';
import {mcpTools} from '../../cli/tools.mjs';
import {compareClientTools} from '../../scripts/check-client-tools.mjs';

test('ultra-lean discovery stays typed and under ~2k tokens while strict validation enforces full schemas',()=>{
 for(const tools of [advertised,mcpTools()]){
  const get=n=>tools.find(t=>t.name.replace('android.','android_')==='android_'+n).inputSchema;
  assert.equal(get('wait').properties.verify.type,'object');
  assert.equal(get('tap').properties.selector.type,'object');
  assert.ok(JSON.stringify(get('tap')).length<600);
  assert.ok(JSON.stringify(get('sequence')).length<400);
  assert.equal(get('sequence').properties.steps.items.type,'object');
 }
 const localBytes=mcpTools().reduce((n,t)=>n+JSON.stringify(t).length,0);
 assert.ok(localBytes<8000,`mcpTools() grew to ${localBytes} B (~${Math.round(localBytes/4)} tok)`);
 assert.equal(compareClientTools({tools:advertised}).nestedChecked,true);
 const broken=structuredClone(advertised);
 broken.find(t=>t.name==='android_sequence').inputSchema.properties.steps.items={};
 const report=compareClientTools({tools:broken});assert.equal(report.ok,false);
 assert.ok(report.missingNestedFields.some(f=>f.field==='android_sequence.steps[]'));
});

test('compact and existing verification forms share strict validation for standalone and sequence calls',()=>{
 const sessionId='session_'+'a'.repeat(32),requestId='semantic_write';
 const ajv=new Ajv({strict:false,allErrors:true});
 const tap=ajv.compile(catalogue.find(t=>t.name==='android_tap').inputSchema);
 const seq=ajv.compile(catalogue.find(t=>t.name==='android_sequence').inputSchema);
 const leaf={kind:'state',selector:{label:'Power saver'},checked:false};
 for(const verify of [leaf,{all:[leaf]},{any:[leaf]},{conditions:[leaf]},{conditions:[leaf],match:'any'}]){
  const args={selector:{label:'Power saver'},verify};
  assert.ok(tap({sessionId,requestId,...args}),JSON.stringify(tap.errors));
  assert.ok(seq({sessionId,requestId,steps:[{tool:'android.tap',arguments:args}]}),JSON.stringify(seq.errors));
 }
 for(const verify of [{conditions:[]},{conditions:Array(9).fill(leaf)},{conditions:[{conditions:[leaf]}]},{conditions:[leaf],match:'sometimes'},{conditions:[leaf],kind:'state'},{conditions:[{kind:'state',selector:{label:'Power saver'}}]},{conditions:[{...leaf,checked:'false'}]}]){
  assert.equal(tap({sessionId,requestId,selector:{label:'Power saver'},verify}),false);
  assert.equal(seq({sessionId,requestId,steps:[{tool:'android.tap',arguments:{selector:{label:'Power saver'},verify}}]}),false);
 }
});
