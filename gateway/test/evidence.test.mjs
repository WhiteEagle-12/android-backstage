import test from 'node:test';
import assert from 'node:assert/strict';
import {asToolResult} from '../../cli/tools.mjs';
import {catalogue} from '../server.mjs';
import {compareClientTools} from '../../scripts/check-client-tools.mjs';
test('one canonical result status preserves input and verification independently with compact screen text',()=>{
 const reply=asToolResult({ok:false,outcome:'failed',inputDelivered:true,verification:{matched:false},code:'verification_timeout',observation:'o',controls:[{id:'t',label:'Save',role:'button'}]});
 assert.equal(reply.isError,true);assert.equal(reply.structuredContent.inputDelivered,true);assert.deepEqual(reply.structuredContent.verification,{matched:false});assert.equal(reply.structuredContent.ok,undefined);assert.equal(reply.structuredContent.verified,undefined);
 assert.match(reply.content[0].text,/^failed o /);
 assert.match(reply.content[0].text,/verification_timeout/);
 assert.match(reply.content[0].text,/t btn "Save"/);
 const shell=asToolResult({outcome:'succeeded',commandSucceeded:false,exitCode:7,stdout:'out',stderr:'err'});assert.equal(shell.isError,false);assert.equal(shell.structuredContent.exitCode,7);
 assert.deepEqual(JSON.parse(shell.content[0].text),shell.structuredContent);
});
test('screenshot payload appears once with complete capture coordinates',()=>{
 const out=asToolResult({outcome:'succeeded',observation:'o',image:{pngBase64:'YWJj',mime:'image/png',displayId:2,width:500,height:250,sourceWidth:1000,sourceHeight:500,scaleX:.5,scaleY:.5,screenshotId:'shot',observedAt:'now'}});
 assert.equal(out.content[1].data,'YWJj');assert.equal(out.structuredContent.image.pngBase64,undefined);assert.equal(out.structuredContent.image.scaleX,.5);assert.equal(out.structuredContent.image.screenshotId,'shot');
});
test('large observations render compact 1-line text within budget, retain full structured content, and detect drift',()=>{
 const out=asToolResult({outcome:'succeeded',controls:Array.from({length:500},(_,i)=>({id:'target_'+i,label:'item '+i,role:'button'})),observation:'o'});
 assert.equal(out.structuredContent.controls.length,500);
 assert.ok(out.content[0].text.length<=3600);
 assert.match(out.content[0].text,/target_0 btn "item 0"/);
 assert.match(out.content[0].text,/target_100 btn "item 100"/);
 assert.equal(compareClientTools({tools:catalogue}).ok,true);assert.equal(compareClientTools({tools:catalogue.filter(t=>t.name!=='android_open')}).ok,false);
});
test('long replies expose useful shell output, location, and screenshot coordinates within a text budget',()=>{
 const fix={latitude:1,longitude:2,ageMs:20000,accuracyMeters:150,source:'cached',provider:'network'};
 const payload={outcome:'failed',code:'location_limits_not_met',inputDelivered:false,fix,
  verification:{matched:false},image:{screenshotId:'shot',scaleX:.5,scaleY:.5,width:500,height:1000,sourceWidth:1000,sourceHeight:2000},
  stdout:'BEGIN\n'+'line\n'.repeat(3000)+'END',stderr:'failure detail',tree:{nodes:Array(1000).fill({label:'noise'})}};
 const out=asToolResult(payload),preview=JSON.parse(out.content[0].text);
 assert.ok(out.content[0].text.length<=3600);assert.deepEqual(preview.fix,fix);
 assert.equal(preview.verification.matched,false);assert.equal(preview.image.screenshotId,'shot');
 assert.equal(preview.stderr,'failure detail');assert.match(preview.stdout,/BEGIN/);assert.match(preview.stdout,/END/);
 assert.equal(preview.tree,undefined);assert.deepEqual(out.structuredContent.tree,payload.tree);
 assert.doesNotMatch(out.content[0].text,/Full result is in/);
});
test('oversized errors and escaped strings cannot exceed the text budget',()=>{
 const out=asToolResult({outcome:'failed',error:'"\\\n'.repeat(10000),controls:Array(500).fill({id:'target',label:'x'.repeat(10000)})});
 assert.ok(out.content[0].text.length<=3600);assert.equal(JSON.parse(out.content[0].text).outcome,'failed');
});
test('file bytes and full debug trees do not fill the text excerpt',()=>{
 const out=asToolResult({outcome:'succeeded',path:'/tmp/file',bytes:10000,dataBase64:'YQ=='.repeat(3000),ui:{nodes:Array(1000).fill({text:'tree text'})},controls:[{id:'t1',label:'Save'}]});
 const preview=JSON.parse(out.content[0].text);
 assert.equal(preview.dataBase64,undefined);assert.equal(preview.ui,undefined);assert.equal(preview.bytes,10000);assert.equal(preview.controls[0].id,'t1');
});
