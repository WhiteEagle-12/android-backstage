import test from 'node:test';
import assert from 'node:assert/strict';
import Ajv from 'ajv';
import {strictCatalogue as catalogue,catalogue as advertised,SERVER_INSTRUCTIONS} from '../server.mjs';
import {mcpTools} from '../../cli/tools.mjs';
import {invalidArguments,exampleArguments} from '../validation.mjs';
import {advertisedSchema} from '../../cli/advertised-schema.mjs';
const sessionId='session_'+'a'.repeat(32),requestId='write_1';
const samples={observe:{},open:{url:'https://example.com'},tap:{selector:{label:'Directions'}},type:{selector:{resourceId:'p:id/search'},text:'café 日本語 🙂'},key:{key:'BACK'},gesture:{preset:'pinch',centerX:100,centerY:100,startSpan:20,endSpan:100},scroll:{selector:{role:'scroll',label:'List'},direction:'down'},wait:{verify:{kind:'exists',selector:{label:'Ready'}}}};
test('visual editing and notification actions validate identically in public and native contracts',()=>{
 const ajv=new Ajv({strict:false});
 for(const tools of [catalogue,mcpTools({strict:true})]){
  const v=n=>ajv.compile(tools.find(t=>t.name.replace('android.','android_')==='android_'+n).inputSchema);
  assert.ok(v('observe')({sessionId,image:'only'}));
  assert.ok(v('observe')({sessionId,full:true}));
  assert.ok(v('key')({sessionId,requestId,key:'A',modifiers:['CTRL'],screenshotId:'capture',repeat:3}));
  assert.equal(v('key')({sessionId,requestId,key:'A',modifiers:['CTRL','CTRL']}),false);
  assert.ok(v('type')({sessionId,requestId,focused:true,screenshotId:'capture',clipboard:true,mode:'insert',text:'日本語'}));
  assert.equal(v('type')({sessionId,requestId,focused:true,screenshotId:'capture',mode:'insert',text:'private'}),false);
  assert.equal(v('type')({sessionId,requestId,focused:true,screenshotId:'capture',clipboard:true,mode:'insert',selector:{label:'Field'},text:'private'}),false);
  assert.ok(v('scroll')({sessionId,requestId,selector:{label:'List'},direction:'down',until:{label:'End'},maxScrolls:10}));
  assert.equal(v('scroll')({sessionId,requestId,selector:{label:'List'},direction:'down',maxScrolls:10}),false);
  const reply={operation:'reply',requestId,notificationId:'notification_x',actionId:'action_0',responses:{message:'café 日本語'}};
  assert.ok(v('notification')(reply));assert.equal(v('notification')({...reply,responses:{}}),false);assert.equal(v('notification')({...reply,responses:{message:42}}),false);
  assert.equal(v('notification')({operation:'open',requestId,notificationId:'notification_x'}),false);
  assert.ok(v('notification')({operation:'open',requestId,notificationId:'notification_x',sessionId}));
 }
 assert.deepEqual(advertisedSchema({anyOf:[{type:'string',minLength:1,description:'First use'},{type:'string',minLength:1,description:'Second use'}]}),{type:'string',minLength:1});
});
test('advertised schemas expose flat typed fields without renderer-dependent unions',()=>{
 const forbidden=new Set(['oneOf','anyOf','allOf','$ref']);
 function walk(s){for(const [k,v] of Object.entries(s)){assert.ok(!forbidden.has(k),k);if(v&&typeof v==='object')walk(v);}}
 for(const tools of [advertised,mcpTools()]){
  tools.forEach(t=>walk(t.inputSchema));
  const get=n=>tools.find(t=>t.name.replace('android.','android_')==='android_'+n).inputSchema;
  assert.equal(get('tap').properties.selector.type,'object');
  for(const k of ['x1','y1','x2','y2','paths'])assert.ok(get('gesture').properties[k]);
  assert.equal(get('sequence').properties.steps.items.type,'object');
 }
});
test('file ranges validate strictly in native and hosted contracts',()=>{
 for(const tools of [catalogue,mcpTools({strict:true})]){
  const schema=tools.find(t=>t.name.replace('android.','android_')==='android_file').inputSchema,v=new Ajv({strict:false}).compile(schema);
  assert.ok(v({operation:'pull',path:'/sdcard/Download/file'}));
  assert.ok(v({operation:'pull',path:'/sdcard/Download/file',offset:4294967296,length:1048576,fileId:'file_'+'a'.repeat(64)}));
  for(const args of [{offset:-1},{offset:0.5},{length:0},{length:1048577},{fileId:'wrong'}])assert.equal(v({operation:'pull',path:'/sdcard/Download/file',...args}),false);
 }
});
test('validation errors provide field paths and valid examples without echoing rejected values',()=>{
 const ajv=new Ajv({strict:false,allErrors:true});
 for(const tool of catalogue){const v=ajv.compile(tool.inputSchema);assert.ok(v(exampleArguments(tool.name)),tool.name+JSON.stringify(v.errors));}
 for(const [name,args,field] of [
  ['android_tap',{selector:{label:'secret user value'},requestId},'sessionId'],
  ['android_gesture',{sessionId,requestId,preset:'pinch'},'centerX'],
  ['android_sequence',{sessionId,requestId,steps:[{tool:'android.type',arguments:{selector:{label:'Search'}}}]},'steps.0.arguments.text']
 ]){
  const validate=ajv.compile(catalogue.find(t=>t.name===name).inputSchema);assert.equal(validate(args),false);
  const failure=invalidArguments(name,args,validate.errors);
  assert.ok(failure.issues.some(i=>i.field===field),JSON.stringify(failure));
  assert.equal(failure.inputDelivered,false);assert.ok(validate(failure.example));assert.ok(!JSON.stringify(failure).includes('secret user value'));
 }
 const gesture=ajv.compile(catalogue.find(t=>t.name==='android_gesture').inputSchema);
 for(const preset of ['__proto__','constructor','unknown'])assert.ok(gesture(exampleArguments('android_gesture',{preset})));
});
test('consolidated tool surface preserves capabilities and removes redundant entry points',()=>{
 const names=new Set(catalogue.map(t=>t.name));assert.equal(names.size,catalogue.length);assert.equal(catalogue.length,23);assert.ok(names.has('android_artifact'));
 for(const name of ['inspect','ui_tree','browser','open_inspect','vd_open','launch','settings','screenshot','vd_screenshot','tap_text','long_press','swipe','drag','pinch','multi_touch','vd_start','vd_stop','reserve','release','vd_wake','vd_status','task_status','install_url','qa_launch','crashes','status','getprop','dumpsys','ls','battery','device_summary','read_batch','grant','stop','uninstall','broadcast','instrument','self_update','help','apps','package_info','task_begin','task_inspect','task_finish','task_share','task_list','handoff','record','pull','push','fetch','scroll_until','clipboard','media','location','device_control','audio','artifact_begin','artifact_status','artifact_delete'])assert.ok(!names.has('android_'+name),name);
 for(const name of ['task','observe','open','tap','type','key','gesture','scroll','wait','sequence','confirm','notification','personal','devices','shell','file','install','packages','logs','events','device','job_status','artifact'])assert.ok(names.has('android_'+name),name);
 assert.ok(!mcpTools({strict:true}).some(t=>t.name==='android.maintenance'));assert.ok(mcpTools({developer:true}).some(t=>t.name==='android.maintenance'));
 for(const text of [SERVER_INSTRUCTIONS,...catalogue.map(t=>t.description)])for(const [name] of text.matchAll(/\bandroid_[a-z_]+\b/g))assert.ok(names.has(name),name);
});
test('native and hosted primitives require session ownership and share exact step arguments',()=>{
 for(const tools of [catalogue,mcpTools({strict:true})]){
  const get=n=>tools.find(t=>t.name==='android_'+n||t.name==='android.'+n);const ajv=new Ajv({strict:false});
  for(const [name,args] of Object.entries(samples)){
   const validate=ajv.compile(get(name).inputSchema),input={...args,sessionId,...(['observe','wait'].includes(name)?{}:{requestId})};
   assert.ok(validate(input),name+JSON.stringify(validate.errors));assert.equal(validate({...input,display:-1}),false);assert.equal(validate({...input,unexpected:true}),false);
   const noSession={...input};delete noSession.sessionId;assert.equal(validate(noSession),false);
   const seq=ajv.compile(get('sequence').inputSchema);assert.ok(seq({sessionId,requestId,steps:[{tool:'android.'+name,arguments:args}]}),name+JSON.stringify(seq.errors));
  }
 }
});
test('strict selectors, bounded gestures, conditions, sequences, and explicit handoff',()=>{
 const ajv=new Ajv({strict:false});const valid=n=>ajv.compile(catalogue.find(t=>t.name==='android_'+n).inputSchema);
 const base={sessionId,requestId};
 for(const bad of [{selector:{}},{selector:{label:'x',contains:true}},{target:'t1'},{target:'t1',observation:'o',x:1,y:1},{x:1}])assert.equal(valid('tap')({...base,...bad}),false);
 assert.equal(valid('type')({...base,text:'x'}),false);assert.equal(valid('type')({...base,selector:{label:'Field'},text:'x',mode:'paste'}),false);
 assert.equal(valid('observe')({sessionId,summary:'route'}),false);
 assert.equal(valid('observe')({sessionId,verify:{all:[{any:[{kind:'exists',selector:{label:'x'}}]}]}}),false);
 assert.equal(valid('observe')({sessionId,verify:{kind:'stable'}}),false);
 assert.equal(valid('observe')({sessionId,verify:{kind:'stable',selectors:[{label:'Directions'}],durationMs:300}}),true);
 assert.equal(valid('gesture')({...base,preset:'paths',paths:Array(6).fill([{x:1,y:1},{x:2,y:2}])}),false);
 assert.equal(valid('gesture')({...base,preset:'pinch',centerX:1,centerY:2,startSpan:-1,endSpan:2}),false);
 assert.equal(valid('sequence')({...base,steps:[{tool:'android.shell',arguments:{command:'id'}}]}),false);
 assert.equal(valid('sequence')({...base,steps:[{tool:'android.key',arguments:{key:'BACK',sessionId}}]}),false);
 assert.equal(valid('sequence')({...base,steps:[{tool:'android.tap',arguments:{selector:{label:'Dismiss'}},optional:true}]}),true);
 assert.equal(valid('sequence')({...base,steps:[{tool:'android.tap',arguments:{selector:{label:'Dismiss'}},optional:'yes'}]}),false);
 assert.equal(valid('task')({operation:'begin',requestId,package:'com.android.settings'}),true);
 assert.equal(valid('task')({operation:'begin',requestId,url:'https://example.com'}),true);
 assert.equal(valid('task')({operation:'begin',requestId,package:'com.android.settings',url:'https://example.com'}),false);
 assert.equal(valid('task')({operation:'begin',requestId,join:'join_'+'a'.repeat(32),package:'com.android.settings'}),false);
 assert.equal(valid('task')({operation:'handoff',...base,activityTaskId:1,confirmed:true}),true);
 assert.equal(valid('task')({operation:'handoff',...base,confirmed:false}),false);
 assert.equal(valid('task')({operation:'record_start',...base}),true);
 assert.equal(valid('task')({operation:'record_status',sessionId}),true);
});
test('selected formats produce relevant errors and matching examples',()=>{
 const ajv=new Ajv({strict:false,allErrors:true});
 for(const [name,args,expected,excluded] of [
  ['tap',{sessionId,requestId,x:1},'y',['target','observation','selector']],
  ['tap',{sessionId,requestId,target:'private'},'observation',['selector','x','y']],
  ['open',{sessionId,requestId,settingsAction:'bad'},'settingsAction',['package','url','activityTaskId']],
  ['scroll',{sessionId,requestId,target:'private',direction:'down'},'observation',['selector']]
 ]){
  const validate=ajv.compile(catalogue.find(t=>t.name==='android_'+name).inputSchema);assert.equal(validate(args),false);
  const error=invalidArguments('android_'+name,args,validate.errors);
  assert.ok(error.issues.some(i=>i.field===expected),JSON.stringify(error));
  assert.ok(!error.issues.some(i=>excluded.includes(i.field)),JSON.stringify(error));
  assert.ok(validate(error.example));assert.ok(!JSON.stringify(error).includes('private'));
 }
 const validate=ajv.compile(catalogue.find(t=>t.name==='android_tap').inputSchema);
 const args={sessionId,requestId,x:1,y:1,selector:{label:'x'}};validate(args);
 assert.match(invalidArguments('android_tap',args,validate.errors).error,/one argument format/);
});
test('preferred valid inputs remain valid in the advertised schema',()=>{
 const ajv=new Ajv({strict:false,allErrors:true});
 for(const tool of advertised){
  const v=ajv.compile(tool.inputSchema);
  assert.ok(v(exampleArguments(tool.name)),tool.name+JSON.stringify(v.errors));
 }
});
