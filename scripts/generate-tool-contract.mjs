import fs from 'node:fs';
const root=new URL('../',import.meta.url);
const str=(extra={})=>({type:'string',minLength:1,...extra});
const num=(min,max)=>({type:'integer',minimum:min,maximum:max});
const obj=(properties,required=[])=>({type:'object',properties,required,additionalProperties:false});
const en=(...values)=>({type:'string',enum:values});
const selector={...obj({label:str({maxLength:1000}),role:en('button','field','scroll','switch','checkbox','radio','link'),context:str({maxLength:1000}),resourceId:str({maxLength:1000})}),anyOf:[{required:['label']},{required:['resourceId']}]};
const leaves=[
 ...['exists','absent'].map(kind=>obj({kind:{const:kind},selector},['kind','selector'])),
 obj({kind:{const:'appeared'},selector,observation:str()},['kind','selector','observation']),
 obj({kind:{const:'field'},selector,value:{type:'string',maxLength:10000}},['kind','selector','value']),
 {...obj({kind:{const:'state'},selector,checked:{type:'boolean'},selected:{type:'boolean'},stateDescription:str()},['kind','selector']),anyOf:['checked','selected','stateDescription'].map(k=>({required:[k]}))},
 {...obj({kind:{const:'window'},package:str(),title:str(),activity:str()},['kind']),anyOf:['package','title','activity'].map(k=>({required:[k]}))},
 {...obj({kind:{const:'stable'},selectors:{type:'array',minItems:1,maxItems:8,items:selector},region:obj({left:num(0,100000),top:num(0,100000),right:num(1,100000),bottom:num(1,100000)},['left','top','right','bottom']),durationMs:num(100,5000)},['kind']),oneOf:[{required:['selectors']},{required:['region']}]}
];
const condition=obj({conditions:{type:'array',minItems:1,maxItems:8,items:{$ref:'#/$defs/leaf'}},match:en('all','any')},['conditions']);
const defs={selector,leaf:{oneOf:leaves},condition:{'x-backstage-preferred':0,oneOf:[condition,{$ref:'#/$defs/leaf'},...['all','any'].map(k=>obj({[k]:{type:'array',minItems:1,maxItems:8,items:{$ref:'#/$defs/leaf'}}},[k]))]}};
const common={windowId:str({pattern:'^window_[a-f0-9]{24}$'}),verify:{$ref:'#/$defs/condition'},timeoutMs:num(0,20000),expectedPackage:str(),observe:{type:'boolean'},detail:en('compact','debug'),timing:{type:'boolean'},maxItems:num(1,300),since:str(),full:{type:'boolean'},image:en('never','always','if_needed','only'),scale:{type:'number',exclusiveMinimum:0,maximum:1}};
const keyboardKeys=[...'ABCDEFGHIJKLMNOPQRSTUVWXYZ',...'0123456789',...Array.from({length:12},(_,i)=>`F${i+1}`),...Array.from({length:10},(_,i)=>`NUMPAD_${i}`),'BACK','ENTER','TAB','DEL','FORWARD_DEL','ESCAPE','DPAD_UP','DPAD_DOWN','DPAD_LEFT','DPAD_RIGHT','DPAD_CENTER','MOVE_HOME','MOVE_END','PAGE_UP','PAGE_DOWN','INSERT','CUT','COPY','PASTE','SPACE','COMMA','PERIOD','GRAVE','MINUS','EQUALS','LEFT_BRACKET','RIGHT_BRACKET','BACKSLASH','SEMICOLON','APOSTROPHE','SLASH','AT','PLUS','NUMPAD_ADD','NUMPAD_SUBTRACT','NUMPAD_MULTIPLY','NUMPAD_DIVIDE','NUMPAD_DOT','NUMPAD_COMMA','NUMPAD_ENTER','NUMPAD_EQUALS','NUMPAD_LEFT_PAREN','NUMPAD_RIGHT_PAREN','HOME','APP_SWITCH','NOTIFICATION','POWER','WAKEUP','SLEEP','VOLUME_UP','VOLUME_DOWN','VOLUME_MUTE','MUTE','MENU','SEARCH','SETTINGS','BRIGHTNESS_UP','BRIGHTNESS_DOWN','ASSIST','VOICE_ASSIST'];
const target={target:str(),observation:str(),selector:{$ref:'#/$defs/selector'}};
const targetOnly=[{required:['target','observation'],not:{required:['selector']}},{required:['selector'],not:{anyOf:[{required:['target']},{required:['observation']}]}}];
const intentExtra=obj({name:str(),type:en('string','uri','boolean','integer','long'),value:{type:'string',maxLength:10000}},['name','type','value']);
const intentSpec=obj({action:str(),data:str(),mimeType:str(),package:str(),activity:str(),categories:{type:'array',maxItems:16,items:str()},extras:{type:'array',maxItems:64,items:intentExtra}},['action']);
const shareSpec={...obj({mimeType:str(),text:{type:'string',maxLength:10000},subject:{type:'string',maxLength:1000},package:str(),activity:str(),files:{type:'array',minItems:1,items:obj({path:str(),name:str(),mimeType:str()},['path','mimeType'])}},['mimeType']),anyOf:[{required:['text']},{required:['files']}],allOf:[{anyOf:[{not:{required:['activity']}},{required:['package']}]}]};
const primitives={
 observe:obj(common),
 open:{...obj({...common,share:shareSpec,intent:intentSpec,activityTaskId:num(1,2147483647),package:str({pattern:'^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$'}),activity:str({pattern:'^[A-Za-z0-9_.$]+$'}),url:str({pattern:'^https?://[^\\s]+$'}),settingsAction:str({pattern:'^android\\.settings\\.[A-Z_]+$'})}),oneOf:['package','url','settingsAction','activityTaskId','intent','share'].map(k=>({required:[k]})),allOf:[{anyOf:[{not:{required:['activity']}},{required:['package']}]}]},
 tap:{...obj({...common,...target,x:num(0,100000),y:num(0,100000),screenshotId:str(),holdMs:num(0,3000)}),oneOf:[...targetOnly.map(s=>({...s,allOf:[{not:{anyOf:['x','y','screenshotId'].map(k=>({required:[k]}))}}]})),{required:['x','y'],not:{anyOf:['target','observation','selector'].map(k=>({required:[k]}))}}]},
 type:{...obj({...common,...target,text:{type:'string',maxLength:10000},mode:en('replace','insert'),focused:{const:true},screenshotId:str(),clipboard:{const:true}},['text']),oneOf:[...targetOnly.map(s=>({...s,allOf:[{not:{anyOf:['focused','screenshotId','clipboard'].map(k=>({required:[k]}))}}]})),{required:['focused','screenshotId','clipboard','mode'],not:{anyOf:['target','observation','selector'].map(k=>({required:[k]}))}}]},
 key:obj({...common,key:en(...keyboardKeys),modifiers:{type:'array',maxItems:6,uniqueItems:true,items:en('CTRL','ALT','SHIFT','META','SYM','FUNCTION')},holdMs:num(0,3000),repeat:num(1,100),screenshotId:str()},['key']),
 gesture:obj({...common,screenshotId:str(),preset:en('swipe','drag','pinch','paths'),x1:num(0,100000),y1:num(0,100000),x2:num(0,100000),y2:num(0,100000),centerX:num(0,100000),centerY:num(0,100000),startSpan:{type:'number',exclusiveMinimum:0,maximum:32767},endSpan:{type:'number',exclusiveMinimum:0,maximum:32767},angleDegrees:{type:'number',minimum:-360,maximum:360},durationMs:num(1,5000),holdMs:num(0,3000),paths:{type:'array',minItems:1,maxItems:5,items:{type:'array',minItems:2,maxItems:100,items:obj({x:{type:'number',minimum:0},y:{type:'number',minimum:0}},['x','y'])}}},['preset']),
 scroll:{...obj({...common,...target,direction:en('up','down','left','right'),amount:num(1,5),until:{$ref:'#/$defs/selector'},maxScrolls:num(1,20)}),oneOf:[...targetOnly,{not:{anyOf:['target','observation','selector'].map(k=>({required:[k]}))}}],allOf:[{anyOf:[{not:{required:['maxScrolls']}},{required:['until']}]}],required:['direction']},
 wait:obj(common,['verify'])
};
primitives.gesture.oneOf=[...['swipe','drag'].map(p=>({properties:{preset:{const:p}},required:['x1','y1','x2','y2']})),{properties:{preset:{const:'pinch'}},required:['centerX','centerY','startSpan','endSpan']},{properties:{preset:{const:'paths'}},required:['paths']}];
const descriptions={
 observe:'Read UI controls/content (auto-diffs; full:true forces full, image:always|if_needed|only, detail:debug).',
 open:'Open package (+activity), url, settingsAction, activityTaskId, intent, or share.',
 tap:'Tap selector, target+observation, or x+y (+screenshotId). holdMs long-presses.',
 type:'Type text into selector/target (mode:replace|insert), or into the focused field with focused:true+screenshotId (from observe image:always).',
 key:'Send key (BACK, ENTER, TAB, DEL, SPACE, A-Z, 0-9, DPAD_*, HOME, VOLUME_*, etc.).',
 gesture:'Run swipe/drag (x1,y1,x2,y2), pinch (centerX,centerY,startSpan,endSpan), or paths ([[{x,y}]]).',
 scroll:'Scroll in direction. Container: selector or target+observation; none uses the largest scroll area (app 2.0.10+). until: selector to stop at (+maxScrolls 1-20).',
 wait:'Wait for verify {conditions:[{kind,...}],match?:all|any}.'
};
const requestId=str({pattern:'^[A-Za-z0-9_-]{1,128}$'});
const sessionId=str({pattern:'^session_[a-f0-9]{32}$'});
const tools=[];
function add(name,description,inputSchema,readOnly=false,extra={}){tools.push({name:'android.'+name,description,inputSchema:{...inputSchema,...(JSON.stringify(inputSchema).includes('$ref')?{$defs:defs}:{})},readOnly,...extra});}
for(const [name,schema] of Object.entries(primitives))add(name,descriptions[name],{...schema,properties:{...schema.properties,sessionId,display:num(0,2147483647),...(!['observe','wait'].includes(name)?{requestId}:{})},required:[...schema.required,'sessionId',...(!['observe','wait'].includes(name)?['requestId']:[])]},['observe','wait'].includes(name));
add('sequence','Run up to 12 UI steps [{tool,arguments,before?,optional?}] ("previous"/"step:0" refs).',obj({sessionId,requestId,display:num(0,2147483647),deadlineMs:num(1,30000),timing:{type:'boolean'},steps:{type:'array',minItems:1,maxItems:12,items:{oneOf:Object.entries(primitives).map(([name,schema])=>obj({tool:{const:'android.'+name},arguments:schema,before:{$ref:'#/$defs/condition'},optional:{type:'boolean'}},['tool','arguments']))}}},['sessionId','requestId','steps']));
add('task','Sessions: begin (optional package/url/intent/join; mode:foreground+confirmed:true), inspect, list, finish, share, handoff, record_start/record_stop/record_status.',{
 ...obj({
  operation:en('begin','inspect','list','finish','share','handoff','record_start','record_stop','record_status'),
  requestId,sessionId,
  mode:en('private','foreground'),confirmed:{const:true},
  leaseMinutes:num(1,30),width:num(240,4096),height:num(240,4096),dpi:num(72,640),
  package:str({pattern:'^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$'}),
  url:str({pattern:'^https?://[^\\s]+$'}),intent:intentSpec,
  join:str({pattern:'^join_[a-f0-9]{32}$'}),
  activityTaskId:num(1,2147483647),
  maxSeconds:num(1,30),fps:num(1,15),scale:{type:'number',minimum:0.25,maximum:0.75}
 },['operation']),
 oneOf:[
  {properties:{operation:{const:'begin'}},required:['requestId'],not:{anyOf:['sessionId','activityTaskId','maxSeconds','fps','scale'].map(k=>({required:[k]}))},allOf:[
   {oneOf:[{properties:{mode:{const:'private'}},not:{required:['confirmed']}},{properties:{mode:{const:'foreground'}},required:['mode','confirmed'],not:{anyOf:['width','height','dpi'].map(k=>({required:[k]}))}}]},
   {not:{required:['package','url']}},{not:{required:['package','intent']}},{not:{required:['url','intent']}},
   {not:{required:['join','package']}},{not:{required:['join','url']}},{not:{required:['join','intent']}}
  ]},
  {properties:{operation:{const:'inspect'}},required:['sessionId'],not:{anyOf:['requestId','mode','confirmed','leaseMinutes','width','height','dpi','package','url','intent','join','activityTaskId','maxSeconds','fps','scale'].map(k=>({required:[k]}))}},
  {properties:{operation:{const:'list'}},not:{anyOf:['requestId','sessionId','mode','confirmed','leaseMinutes','width','height','dpi','package','url','intent','join','activityTaskId','maxSeconds','fps','scale'].map(k=>({required:[k]}))}},
  {properties:{operation:{enum:['finish','share']}},required:['sessionId','requestId'],not:{anyOf:['mode','confirmed','leaseMinutes','width','height','dpi','package','url','intent','join','activityTaskId','maxSeconds','fps','scale'].map(k=>({required:[k]}))}},
  {properties:{operation:{const:'handoff'}},required:['sessionId','requestId','confirmed'],not:{anyOf:['mode','leaseMinutes','width','height','dpi','url','intent','join','maxSeconds','fps','scale'].map(k=>({required:[k]}))}},
  {properties:{operation:{enum:['record_start','record_stop']}},required:['sessionId','requestId'],not:{anyOf:['mode','confirmed','leaseMinutes','width','height','dpi','package','url','intent','join','activityTaskId'].map(k=>({required:[k]}))}},
  {properties:{operation:{const:'record_status'}},required:['sessionId'],not:{anyOf:['requestId','mode','confirmed','leaseMinutes','width','height','dpi','package','url','intent','join','activityTaskId','maxSeconds','fps','scale'].map(k=>({required:[k]}))}}
 ]
});
add('devices','List devices (detail:debug adds battery/network).',obj({detail:en('compact','debug')}),true);
add('packages','Search installed apps or inspect one package.',{...obj({query:str(),package:str(),fields:{type:'array',minItems:1,maxItems:5,uniqueItems:true,items:en('package','versionName','versionCode','debuggable','permissions')},detail:en('compact','debug')}),not:{required:['query','package']}},true);
add('logs','Read logcat, crash, ANR, or jank (needs package).',obj({kind:en('log','crash','anr','jank'),package:str(),maxLines:num(1,500),max:num(1,50),since:str()},['kind']),true);
add('install','Install APK from source {url} or {path}.',obj({requestId,source:{oneOf:[obj({url:str(),fileKey:str(),sha256:str()},['url']),obj({path:str()},['path'])]}},['requestId','source']));
add('confirm','Ask user on Android device to approve an action (kind:confirm) or take over the live screen for sign-in, 2FA, CAPTCHA, or manual help (kind:handoff).',obj({sessionId,requestId,kind:en('confirm','handoff'),title:str({maxLength:120}),summary:{type:'string',maxLength:1000},timeoutMs:num(5000,300000)},['sessionId','requestId','title']));
add('personal','Shortcuts: recent_photos, contacts, alarm, timer, inbox.',{...obj({operation:en('recent_photos','contacts','alarm','timer','inbox'),query:{type:'string',maxLength:200},limit:num(1,50),hour:num(0,23),minute:num(0,59),seconds:num(1,86400),message:{type:'string',maxLength:200},itemId:str(),clear:{type:'boolean'},requestId},['operation']),oneOf:[{properties:{operation:{enum:['recent_photos','contacts']}}},{properties:{operation:{const:'alarm'}},required:['hour','minute','requestId']},{properties:{operation:{const:'timer'}},required:['seconds','requestId']},{properties:{operation:{const:'inbox'}}}]});
add('events','Pushed events (list), triggers (rules/set_rules), webhooks (webhook_set/webhook_clear).',{...obj({operation:en('list','rules','set_rules','webhook_set','webhook_clear'),since:num(0,9007199254740991),limit:num(1,100),enabled:{type:'boolean'},notificationPackages:{type:'array',maxItems:20,items:str()},senderContains:{type:'string',maxLength:200},textContains:{type:'string',maxLength:200},batteryBelow:num(5,95),url:str({pattern:'^https://[^\\s]+$'}),secret:{type:'string',maxLength:256},requestId},['operation']),oneOf:[{properties:{operation:{enum:['list','rules']}}},{properties:{operation:{const:'set_rules'}},required:['requestId']},{properties:{operation:{const:'webhook_set'}},required:['url','requestId']},{properties:{operation:{const:'webhook_clear'}},required:['requestId']}]});
add('notification','Notifications: list, open, action, reply, dismiss.',{...obj({operation:en('list','open','action','reply','dismiss'),max:num(1,100),package:str(),notificationId:str(),actionId:str({pattern:'^action_[0-9]+$'}),responses:{type:'object',minProperties:1,maxProperties:8,additionalProperties:{type:'string',maxLength:10000}},sessionId,requestId}),oneOf:[{properties:{operation:{const:'list'}},not:{anyOf:['notificationId','actionId','responses','sessionId','requestId'].map(k=>({required:[k]}))}},{properties:{operation:{const:'open'}},required:['operation','notificationId','sessionId','requestId'],not:{anyOf:['actionId','responses','max','package'].map(k=>({required:[k]}))}},{properties:{operation:{const:'action'}},required:['operation','notificationId','actionId','sessionId','requestId'],not:{anyOf:['responses','max','package'].map(k=>({required:[k]}))}},{properties:{operation:{const:'reply'}},required:['operation','notificationId','actionId','responses','requestId'],not:{anyOf:['max','package'].map(k=>({required:[k]}))}},{properties:{operation:{const:'dismiss'}},required:['operation','notificationId','requestId'],not:{anyOf:['actionId','responses','max','package','sessionId'].map(k=>({required:[k]}))}}]});
add('shell','Run background shell command.',obj({command:str(),requestId},['command','requestId']));
add('file','Files: pull (single-call for large files with saveTo or download URL), push, fetch.',{
 ...obj({operation:en('pull','push','fetch'),path:str(),offset:num(0,9007199254740991),length:num(1,1048576),fileId:str({pattern:'^file_[a-f0-9]{64}$'}),saveTo:str(),local:str(),remote:str(),url:str(),fileKey:str(),sha256:str(),requestId},['operation']),
 oneOf:[
  {properties:{operation:{const:'pull'}},required:['path'],not:{anyOf:['local','remote','url','fileKey','sha256','requestId'].map(k=>({required:[k]}))}},
  {properties:{operation:{const:'push'}},required:['local','remote','requestId'],not:{anyOf:['path','offset','length','fileId','saveTo','url','fileKey','sha256'].map(k=>({required:[k]}))}},
  {properties:{operation:{const:'fetch'}},required:['url','remote','requestId'],not:{anyOf:['path','offset','length','fileId','saveTo','local'].map(k=>({required:[k]}))}}
 ]
});
add('device','Utilities: clipboard (no text reads, text writes), media (no action reads now playing), location, control (volume/rotation/wifi/bluetooth), brightness (no value reads, value 0-255 sets; app 2.0.10+), audio. Screen recording is android.task record_start.',{
 ...obj({
  operation:en('clipboard','media','location','control','audio','brightness'),
  action:en('status','play','pause','next','previous','seek','volume','rotation','wifi','bluetooth','capture','inject','stop','renew'),
  text:{type:'string'},package:str(),sessionId:str(),positionMs:num(0,9007199254740991),
  maxAgeMs:num(0,3600000),accuracyMeters:{type:'number',minimum:1,maximum:100000},timeoutMs:num(0,20000),
  stream:en('media','ring','notification','alarm','call','system'),value:num(0,1000),rotation:en('auto','0','90','180','270'),enabled:{type:'boolean'},
  path:str(),audioId:str({pattern:'^audio_[a-f0-9]{32}$'}),leaseSeconds:num(1,1800),sampleRate:num(8000,192000),channels:num(1,2),
  requestId
 },['operation']),
 oneOf:[
  {properties:{operation:{const:'clipboard'}},anyOf:[{not:{required:['text']}},{required:['requestId']}],not:{anyOf:['action','package','sessionId','positionMs','maxAgeMs','accuracyMeters','timeoutMs','stream','value','rotation','enabled','path','audioId','leaseSeconds','sampleRate','channels'].map(k=>({required:[k]}))}},
  {properties:{operation:{const:'media'},action:en('status','play','pause','next','previous','seek')},required:['action','requestId'],not:{anyOf:['text','maxAgeMs','accuracyMeters','timeoutMs','stream','value','rotation','enabled','path','audioId','leaseSeconds','sampleRate','channels'].map(k=>({required:[k]}))}},
  {properties:{operation:{const:'location'}},not:{anyOf:['action','text','package','sessionId','positionMs','stream','value','rotation','enabled','path','audioId','leaseSeconds','sampleRate','channels','requestId'].map(k=>({required:[k]}))}},
  {properties:{operation:{const:'control'}},required:['action'],not:{anyOf:['text','package','positionMs','maxAgeMs','accuracyMeters','timeoutMs','path','audioId','leaseSeconds','sampleRate','channels'].map(k=>({required:[k]}))},allOf:[{oneOf:[
   {properties:{action:{const:'status'}},not:{anyOf:['stream','value','rotation','enabled','sessionId','requestId'].map(k=>({required:[k]}))}},
   {properties:{action:{const:'volume'}},required:['stream','value','sessionId','requestId'],not:{anyOf:['rotation','enabled'].map(k=>({required:[k]}))}},
   {properties:{action:{const:'rotation'}},required:['rotation','sessionId','requestId'],not:{anyOf:['stream','value','enabled'].map(k=>({required:[k]}))}},
   {properties:{action:{enum:['wifi','bluetooth']}},required:['enabled','sessionId','requestId'],not:{anyOf:['stream','value','rotation'].map(k=>({required:[k]}))}}
  ]}]},
  {properties:{operation:{const:'audio'}},required:['action'],not:{anyOf:['text','sessionId','positionMs','maxAgeMs','accuracyMeters','timeoutMs','stream','value','rotation','enabled'].map(k=>({required:[k]}))},allOf:[{oneOf:[
   {properties:{action:{const:'capture'}},required:['package','path','requestId'],not:{required:['audioId']}},
   {properties:{action:{const:'inject'}},required:['package','path','requestId'],not:{anyOf:['audioId','sampleRate','channels'].map(k=>({required:[k]}))}},
   {properties:{action:{const:'status'}},required:['audioId'],not:{anyOf:['package','path','sampleRate','channels','leaseSeconds','requestId'].map(k=>({required:[k]}))}},
   {properties:{action:{const:'stop'}},required:['audioId','requestId'],not:{anyOf:['package','path','sampleRate','channels','leaseSeconds'].map(k=>({required:[k]}))}},
   {properties:{action:{const:'renew'}},required:['audioId','requestId','leaseSeconds'],not:{anyOf:['package','path','sampleRate','channels'].map(k=>({required:[k]}))}}
  ]}]},
  {properties:{operation:{const:'brightness'},value:{maximum:255}},anyOf:[{not:{required:['value']}},{required:['requestId']}],not:{anyOf:['action','text','package','positionMs','maxAgeMs','accuracyMeters','timeoutMs','stream','rotation','enabled','path','audioId','leaseSeconds','sampleRate','channels'].map(k=>({required:[k]}))}}
 ]
});
add('developer','Run package stop/uninstall/grant/clear_data or instrumentation test.',obj({operation:en('stop','uninstall','grant','clear_data','instrument'),package:str(),permission:str(),component:str(),class:str(),args:str(),timeoutMs:num(1,600000),requestId},['operation','requestId']),false,{surface:'developer'});
add('maintenance','Update Backstage and reconnect; check device state before retrying after restart.',obj({source:{oneOf:[obj({url:str(),fileKey:str(),sha256:str()},['url']),obj({path:str()},['path'])]},requestId},['source','requestId']),false,{surface:'developer'});
// Keep object fields at the visible object level. Some MCP clients render oneOf/anyOf
// before properties, losing sibling fields when the alternatives are only constraints.
// Nest those constraints under allOf: identical validation, no duplicated field schemas.
function visibleObjects(value) {
 if(Array.isArray(value))return value.map(visibleObjects);
 if(!value||typeof value!=='object')return value;
 const out=Object.fromEntries(Object.entries(value).map(([k,v])=>[k,visibleObjects(v)]));
 if(out.type==='object'&&out.properties)for(const key of ['oneOf','anyOf'])if(out[key]){
  out.allOf=[...(out.allOf||[]),{[key]:out[key]}];delete out[key];
 }
 return out;
}
for(const tool of tools)tool.inputSchema=visibleObjects(tool.inputSchema);
const json=JSON.stringify(tools,null,2)+'\n';fs.writeFileSync(new URL('cli/tool-contract.json',root),json);
fs.writeFileSync(new URL('app/app/src/main/java/com/deviceagent/tools/ToolCatalog.java',root),`package com.deviceagent.tools;\nimport java.util.*;\n/** Generated by scripts/generate-tool-contract.mjs. */\npublic final class ToolCatalog {\n private ToolCatalog(){}\n private static final String DATA=String.join("",${JSON.stringify(tools).match(/.{1,16000}/gs).map(x=>JSON.stringify(x)).join(",")});\n @SuppressWarnings("unchecked") private static final List<Map<String,Object>> PARSED=Collections.unmodifiableList((List<Map<String,Object>>)(List<?>)Json.parse(DATA));\n private static final Map<String,Map<String,Object>> SCHEMAS=indexSchemas();\n private static Map<String,Map<String,Object>> indexSchemas(){Map<String,Map<String,Object>> m=new LinkedHashMap<>();for(var t:PARSED)m.put((String)t.get("name"),PrimitiveArguments.map(t.get("inputSchema")));return Collections.unmodifiableMap(m);}\n public static List<Map<String,Object>> tools(){return PARSED;}\n public static Map<String,Object> schema(String name){Map<String,Object> s=SCHEMAS.get(name);if(s!=null)return s;throw new IllegalArgumentException("Unknown tool: "+name);}\n public static void validate(String name,Map<String,Object> args){JsonSchema.validate(schema(name),args);}\n public static Map<String,Object> catalog(){Map<String,Object> out=new LinkedHashMap<>();for(var t:PARSED)if(!t.containsKey("surface"))out.put((String)t.get("name"),Json.mapOf("description",t.get("description"),"inputSchema",t.get("inputSchema")));return out;}\n}\n`);
