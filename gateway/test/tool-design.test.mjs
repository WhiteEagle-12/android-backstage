import test from 'node:test';
import assert from 'node:assert/strict';
import Ajv from 'ajv';
import {strictCatalogue,catalogue as advertised,normalizeToolCall,explainOldPhone,callTiming,SERVER_INSTRUCTIONS} from '../server.mjs';
import {mcpTools} from '../../cli/tools.mjs';
import {invalidArguments,exampleArguments} from '../validation.mjs';

const sessionId='session_'+'a'.repeat(32);
const ajv=new Ajv({strict:false,allErrors:true});
const compiled=new Map(strictCatalogue.map(t=>[t.name,ajv.compile(t.inputSchema)]));
const schemaOf=name=>strictCatalogue.find(t=>t.name===name).inputSchema;
// Mirror the hosted handler: aliases, then validation with a generated requestId.
function call(name,raw){
 const n=normalizeToolCall(name,raw),v=compiled.get(n.name);
 const withId=n.args.requestId===undefined?{...n.args,requestId:'auto_test'}:n.args;
 if(v(withId))return {ok:true,args:withId};
 const autoErrors=[...v.errors];
 if(v(n.args))return {ok:true,args:n.args};
 // Read-only tools reject requestId; report the caller's own arguments then.
 const rejectsId=autoErrors.some(e=>e.params?.additionalProperty==='requestId');
 const [args,errors]=rejectsId?[n.args,[...v.errors]]:[withId,autoErrors];
 return {ok:false,args:n.args,failure:invalidArguments(n.name,args,errors,schemaOf(n.name))};
}
const passes=(name,example)=>{const v=compiled.get(name);return v({...example,requestId:'auto_test'})||v(example);};

test('errors list allowed values, supported fields and where a capability lives',()=>{
 const op=call('android_device',{operation:'clipboard_get'}).failure;
 assert.match(op.error,/operation must be one of: clipboard, media, location, control, audio, brightness/);
 assert.match(op.error,/android_device operation "clipboard"/);
 const rec=call('android_device',{operation:'screenrecord',sessionId}).failure;
 assert.match(rec.error,/record_start/);
 const field=call('android_observe',{sessionId,includeImage:true}).failure;
 assert.match(field.error,/includeImage is not supported\. Use: .*\bimage\b/);
 assert.equal(field.example.image,'always');
 const detail=call('android_observe',{sessionId,detail:'verbose'}).failure;
 assert.match(detail.error,/detail must be one of: compact, debug/);
 const nested=call('android_open',{sessionId,intent:{action:'android.intent.action.MAIN',flavor:'x'}}).failure;
 assert.match(nested.error,/intent\.flavor is not supported\. Use: action, data/);
 const selector=call('android_tap',{sessionId,selector:{role:'button'}}).failure;
 assert.match(selector.error,/selector needs label or resourceId/);
 const extras=call('android_task',{operation:'begin',intent:{action:'android.intent.action.SET_TIMER',extras:{length:30}}}).failure;
 assert.match(extras.error,/array of \{"name","type","value"\}/);
 assert.ok(passes('android_task',extras.example));
 const stable=call('android_wait',{sessionId,verify:{conditions:[{kind:'stable'}],match:'any'}}).failure;
 assert.equal(stable.error,'verify.conditions.0 needs selectors or region.');
 const before=call('android_sequence',{sessionId,steps:[{tool:'android.tap',arguments:{selector:{label:'Go'}},before:{delayMs:100}}]}).failure;
 assert.match(before.error,/steps\.0\.before must be \{"conditions"/);
 const tool=call('android_sequence',{sessionId,steps:[{tool:'android.shell',arguments:{command:'id'}}]}).failure;
 assert.match(tool.error,/steps\.0\.tool must be one of: android\.observe, android\.open, android\.tap/);
 const focused=call('android_type',{sessionId,focused:true,text:'x'}).failure;
 assert.match(focused.error,/^screenshotId is required: take it from android_observe/);
 for(const f of [op,rec,field,detail,nested,selector,extras,stable,before,tool,focused]){
  assert.equal(f.inputDelivered,false);assert.ok(!/requestId/.test(f.error),f.error);
 }
});

test('every error example is a valid call for the same tool and operation',()=>{
 for(const tool of strictCatalogue)assert.ok(passes(tool.name,exampleArguments(tool.name)),tool.name);
 const bad=[
  ['android_task',{operation:'record'}],['android_task',{operation:'begin',mode:'foreground'}],['android_task',{operation:'finish'}],
  ['android_device',{operation:'brightness',value:'high'}],['android_device',{operation:'media',action:'rewind'}],['android_device',{operation:'clipboard',text:5}],
  ['android_file',{operation:'fetch',url:'https://example.com/a'}],['android_file',{operation:'push'}],
  ['android_scroll',{sessionId,direction:'sideways',until:{label:'End'}}],['android_type',{sessionId,focused:true,text:'x'}],
  ['android_open',{sessionId,intent:{data:'x'}}],['android_observe',{sessionId,screenshot:true}],['android_gesture',{sessionId,preset:'pinch'}],
  ['android_sequence',{sessionId,steps:[{tool:'android.tap',arguments:{}}]}],['android_notification',{operation:'reply'}],
 ];
 for(const [name,args] of bad){const r=call(name,args);assert.equal(r.ok,false,name+JSON.stringify(args));const ex=call(name,r.failure.example);assert.ok(ex.ok,name+JSON.stringify(r.failure.example)+JSON.stringify(ex.failure));}
});

test('nested shapes advertise their fields, types and enums one level deep',()=>{
 for(const tools of [advertised,mcpTools()]){
  const get=n=>tools.find(t=>t.name.replace('android.','android_')==='android_'+n).inputSchema.properties;
  assert.deepEqual(Object.keys(get('tap').selector.properties).sort(),['context','label','resourceId','role']);
  assert.ok(get('tap').selector.properties.role.enum.includes('scroll'));
  assert.ok(get('scroll').until.properties.label);assert.equal(get('scroll').maxScrolls.type,'integer');
  const condition=get('wait').verify.properties.conditions.items;
  assert.ok(condition.properties.kind.enum.includes('stable'));assert.deepEqual(condition.required,['kind']);
  assert.deepEqual(get('wait').verify.properties.match.enum,['all','any']);assert.equal(get('wait').timeoutMs.type,'integer');
  const extra=get('task').intent.properties.extras.items;
  assert.deepEqual(extra.required,['name','type','value']);assert.ok(extra.properties.type.enum.includes('integer'));
  assert.ok(get('open').intent.properties.categories);
  const step=get('sequence').steps.items;
  assert.ok(step.properties.tool.enum.includes('android.tap'));assert.deepEqual(step.required,['tool','arguments']);
  assert.ok(get('device').action.enum.includes('status'));assert.ok(get('device').operation.enum.includes('brightness'));
  const legacyVerify=new Ajv({strict:false}).compile(tools.find(t=>t.name.replace('android.','android_')==='android_wait').inputSchema);
  assert.ok(legacyVerify({sessionId,verify:{kind:'exists',selector:{label:'Done'}},timeoutMs:5000}),'cached clients keep the single-condition verify form');
  assert.equal(get('type').screenshotId.type,'string');assert.equal(get('type').focused.type,'boolean');
 }
});

test('scroll needs no container on the gateway and old phones get a fix',()=>{
 for(const tools of [strictCatalogue,mcpTools({strict:true})]){
  const v=new Ajv({strict:false}).compile(tools.find(t=>t.name.replace('android.','android_')==='android_scroll').inputSchema);
  assert.ok(v({sessionId,requestId:'r',direction:'down'}));
  assert.ok(v({sessionId,requestId:'r',direction:'down',until:{label:'End'},maxScrolls:8}));
  assert.equal(v({sessionId,requestId:'r',direction:'down',target:'t1'}),false);
  assert.equal(v({sessionId,requestId:'r',direction:'down',target:'t1',observation:'o',selector:{label:'List'}}),false);
 }
 const until=call('android_scroll',{sessionId,direction:'down',until:{selector:{label:'Sydney'},maxScrolls:15}});
 assert.ok(until.ok);assert.deepEqual(until.args.until,{label:'Sydney'});assert.equal(until.args.maxScrolls,15);
 const phone={outcome:'failed',code:'invalid_argument',inputDelivered:false,error:'oneOf failed'};
 const old=explainOldPhone('android_scroll',{sessionId,direction:'down'},phone);
 assert.match(old.error,/\{"label":"Scroll area","role":"scroll"\}/);assert.equal(old.phoneError,'oneOf failed');
 assert.ok(call('android_scroll',{sessionId,direction:'down',selector:{label:'Scroll area',role:'scroll'}}).ok);
 assert.equal(explainOldPhone('android_scroll',{sessionId,direction:'down',selector:{label:'List'}},phone),phone);
});

test('conflicts name the fields involved, never a missing requestId',()=>{
 const join=call('android_task',{operation:'begin',join:'join_'+'a'.repeat(32),package:'com.android.settings'}).failure;
 assert.match(join.error,/join and package cannot be used together/);assert.ok(!/requestId/.test(join.error));
 const inspect=call('android_task',{operation:'inspect',sessionId,package:'com.android.settings'}).failure;
 assert.match(inspect.error,/package is not used with operation "inspect"/);assert.ok(!/requestId/.test(inspect.error));
 const media=call('android_device',{operation:'media'});assert.ok(media.ok);assert.equal(media.args.action,'status');
});

test('clipboard and other common spellings map to the canonical call',()=>{
 for(const [raw,expected] of [
  [{operation:'clipboard',action:'get',sessionId},{operation:'clipboard'}],
  [{operation:'clipboard',action:'read'},{operation:'clipboard'}],
  [{operation:'clipboard',action:'set',text:'hi'},{operation:'clipboard',text:'hi'}],
  [{operation:'clipboard',text:'hi',sessionId},{operation:'clipboard',text:'hi'}],
 ]){const r=call('android_device',raw);assert.ok(r.ok,JSON.stringify(r.failure));const {requestId,...rest}=r.args;assert.deepEqual(rest,expected);}
 assert.equal(call('android_device',{operation:'clipboard',action:'set'}).ok,false);
 assert.deepEqual(normalizeToolCall('android_clipboard',{action:'get'}).args,{operation:'clipboard'});
 const open=call('android_open',{sessionId,action:'android.settings.WIFI_SETTINGS'});assert.ok(open.ok);assert.equal(open.args.settingsAction,'android.settings.WIFI_SETTINGS');
 const swipe=call('android_gesture',{sessionId,from:{x:1,y:2},to:{x:3,y:4}});assert.ok(swipe.ok);assert.deepEqual([swipe.args.preset,swipe.args.x1,swipe.args.y2],['swipe',1,4]);
 const fetch=call('android_file',{operation:'fetch',url:'https://example.com',path:'/sdcard/Download/a.html'});assert.ok(fetch.ok);assert.equal(fetch.args.remote,'/sdcard/Download/a.html');
 const typed=call('android_type',{sessionId,focused:true,screenshotId:'shot',text:'x'});assert.ok(typed.ok);assert.equal(typed.args.clipboard,true);assert.equal(typed.args.mode,'replace');
 const wait=call('android_wait',{sessionId,verify:{conditions:[{kind:'exists',selector:{label:'Go'}}],timeoutMs:8000}});assert.ok(wait.ok);assert.equal(wait.args.timeoutMs,8000);
 const task=call('android_task',{action:'begin'});assert.ok(task.ok);assert.equal(task.args.operation,'begin');
 const extras=call('android_task',{operation:'begin',intent:{action:'android.intent.action.SET_TIMER',category:'android.intent.category.DEFAULT',extras:[{key:'android.intent.extra.alarm.LENGTH',type:'int',value:30},{name:'android.intent.extra.alarm.SKIP_UI',value:true}]}});
 assert.ok(extras.ok,JSON.stringify(extras.failure));assert.deepEqual(extras.args.intent.extras,[{type:'integer',value:'30',name:'android.intent.extra.alarm.LENGTH'},{name:'android.intent.extra.alarm.SKIP_UI',value:'true',type:'boolean'}]);
 assert.deepEqual(extras.args.intent.categories,['android.intent.category.DEFAULT']);
});

test('sequence steps accept android.tap, android_tap, tap and inline step forms',()=>{
 for(const tool of ['android.tap','android_tap','tap']){
  const r=call('android_sequence',{sessionId,steps:[{tool,arguments:{selector:{label:'1'}}}]});assert.ok(r.ok,tool);assert.equal(r.args.steps[0].tool,'android.tap');
 }
 const inline=call('android_sequence',{sessionId,observation:'obs_1',steps:[{action:'tap',target:'t1'},{operation:'tap',selector:{label:'2'},optional:true}]});
 assert.ok(inline.ok,JSON.stringify(inline.failure));
 assert.deepEqual(inline.args.steps,[{tool:'android.tap',arguments:{target:'t1',observation:'obs_1'}},{tool:'android.tap',arguments:{selector:{label:'2'}},optional:true}]);
 const nested=call('android_sequence',{sessionId,steps:[{tool:'scroll',arguments:{direction:'down',until:{selector:{label:'End'}}}}]});
 assert.ok(nested.ok);assert.deepEqual(nested.args.steps[0].arguments.until,{label:'End'});
});

test('non-web URLs and URLs pinned to an app open as VIEW intents',()=>{
 const view='android.intent.action.VIEW';
 const geo=call('android_open',{sessionId,url:'geo:0,0?q=gas'});assert.ok(geo.ok);assert.deepEqual(geo.args.intent,{action:view,data:'geo:0,0?q=gas'});assert.ok(!('url' in geo.args));
 const market=call('android_task',{operation:'begin',url:'market://details?id=org.videolan.vlc'});assert.ok(market.ok);assert.equal(market.args.intent.data,'market://details?id=org.videolan.vlc');
 const pinned=call('android_task',{operation:'begin',url:'https://maps.google.com/?q=x',package:'com.google.android.apps.maps'});
 assert.ok(pinned.ok);assert.deepEqual(pinned.args.intent,{action:view,data:'https://maps.google.com/?q=x',package:'com.google.android.apps.maps'});assert.ok(!('package' in pinned.args));
 const intentPackage=call('android_task',{operation:'begin',package:'com.google.android.apps.maps',intent:{action:view,data:'geo:0,0?q=x'}});
 assert.ok(intentPackage.ok);assert.equal(intentPackage.args.intent.package,'com.google.android.apps.maps');
 const bare=call('android_open',{sessionId,url:'www.example.com'});assert.equal(bare.ok,false);assert.ok(!('intent' in bare.args));assert.match(bare.failure.error,/url/);
 const web=call('android_task',{operation:'begin',url:'https://example.com'});assert.ok(web.ok);assert.equal(web.args.url,'https://example.com');
});

test('brightness is an optional device operation with a clear error on older phones',()=>{
 assert.ok(call('android_device',{operation:'brightness'}).ok);
 const set=call('android_device',{operation:'brightness',value:200});assert.ok(set.ok);assert.ok(set.args.requestId);
 assert.match(call('android_device',{operation:'brightness',value:300}).failure.error,/value must be <= 255/);
 const old=explainOldPhone('android_device',{operation:'brightness'},{outcome:'failed',code:'invalid_argument',inputDelivered:false,error:'operation must be one of'});
 assert.equal(old.code,'app_update_required');assert.match(old.error,/2\.0\.10/);
});

test('timing keeps phone fields and adds gateway and network time',()=>{
 const started=performance.now()-25;
 const t=callTiming({deviceTotalMs:40,relayRoundTripMs:100,linkQueueMs:1},started);
 assert.ok(t.gatewayMs>=25);assert.equal(t.networkMs,60);assert.equal(t.deviceTotalMs,40);
 assert.deepEqual(Object.keys(callTiming(undefined,started)),['gatewayMs']);
});

test('instructions say where recording, clipboard, media, brightness, files and install live',()=>{
 for(const words of [/screen recording `android_task` `record_start`/,/clipboard, media/,/brightness/,/files `android_file`/,/APK install `android_install`/])assert.match(SERVER_INSTRUCTIONS,words);
 assert.match(advertised.find(t=>t.name==='android_device').description,/Screen recording is android_task record_start/);
});
