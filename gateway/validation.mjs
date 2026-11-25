// Examples contain placeholders, never values copied from a rejected request.
// The gateway generates requestId, so examples and errors never ask for one.
const sessionId='session_'+'0'.repeat(32);
const scrollArea={label:'Scroll area',role:'scroll'};
const primitives={
 observe:{},open:{url:'https://example.com'},tap:{selector:{label:'Save'}},
 type:{selector:{label:'Search'},text:'example'},key:{key:'BACK'},
 gesture:{preset:'swipe',x1:100,y1:300,x2:100,y2:100},
 scroll:{selector:scrollArea,direction:'down'},
 wait:{verify:{conditions:[{kind:'exists',selector:{label:'Ready'}}]}}
};
const STEP_TOOLS=Object.keys(primitives).map(n=>'android.'+n);
const timerIntent={action:'android.intent.action.SET_TIMER',extras:[{name:'android.intent.extra.alarm.LENGTH',type:'integer',value:'30'}]};
const viewIntent={action:'android.intent.action.VIEW',data:'geo:0,0?q=coffee'};
// Where a capability lives when a model looks for it in the wrong place.
const WHERE=[
 [/record|video|screencast/i,'Screen recording is android_task operation "record_start", then "record_stop".'],
 [/clip/i,'Clipboard is android_device operation "clipboard": omit text to read, pass text to write.'],
 [/screenshot|image|visual|picture/i,'Screenshots come from android_observe with image:"always" (or "only" for pixels only).'],
 [/bright/i,'Brightness is android_device operation "brightness": omit value to read, value 0-255 to set.'],
 [/volume|wifi|bluetooth|rotat/i,'Volume, rotation, Wi-Fi and Bluetooth are android_device operation "control".'],
 [/install|apk/i,'APK install is android_install.'],
 [/file|pull|push|download/i,'Files are android_file (pull, push, fetch).'],
];
const FIELD_HELP={
 screenshotId:'take it from android_observe with image:"always"',
 sessionId:'start a session with android_task operation:"begin"',
 observation:'use the observation id from the same android_observe result as target',
 verify:'for example {"conditions":[{"kind":"exists","selector":{"label":"Ready"}}]}',
};
const PATTERN_HELP={
 url:'must start with http:// or https://. For other schemes use intent {"action":"android.intent.action.VIEW","data":"geo:0,0?q=coffee"}',
 sessionId:'must be the sessionId returned by android_task begin',
 settingsAction:'must look like android.settings.WIFI_SETTINGS',
 package:'must be a package name such as com.android.settings',
 windowId:'must be a windowId from android_observe windows',
 join:'must be a join code returned by android_task share',
 actionId:'must be an actionId such as action_0 from android_notification list',
};
function choice(name,args={}){
 const short=String(name).replace(/^android[_.]/,'');
 const groups=short==='open'?[['package'],['url'],['settingsAction'],['activityTaskId'],['intent'],['share']]
  :['tap','type','scroll','scroll_until'].includes(short)?[['target','observation'],['selector'],...(short==='tap'?[['x','y','screenshotId']]:short==='type'?[['focused','screenshotId','clipboard']]:[])]:[];
 const selected=groups.filter(g=>g.some(k=>Object.hasOwn(args,k)));
 // Scroll without a container is valid; the phone picks the largest scroll area.
 return {groups,selected,optional:['scroll','scroll_until'].includes(short)};
}
export function exampleArguments(name,args={}) {
 const short=name.replace(/^android_/,'');
 const wants=re=>Object.keys(args).some(k=>re.test(k))||typeof args.operation==='string'&&re.test(args.operation);
 if(Object.hasOwn(primitives,short)){
  let example=primitives[short];
  if(short==='observe'&&wants(/image|screenshot|visual|picture/i))example={image:'always'};
  if(short==='type'&&Object.hasOwn(args,'focused'))example={focused:true,screenshotId:'returned_screenshot_id',text:'example'};
  if(short==='scroll'&&Object.hasOwn(args,'until'))example={selector:scrollArea,direction:'down',until:{label:'Done'},maxScrolls:10};
  if(short==='gesture'){
   const gestures={
   swipe:example,drag:{preset:'drag',x1:100,y1:300,x2:100,y2:100},
   pinch:{preset:'pinch',centerX:200,centerY:300,startSpan:40,endSpan:100},
   paths:{preset:'paths',paths:[[{x:100,y:300},{x:100,y:100}]]}
   };example=Object.hasOwn(gestures,args.preset)?gestures[args.preset]:example;
  }
  const {selected}=choice(name,args);
  if(selected.length===1){
   const key=selected[0][0];
   if(short==='open')example=({package:{package:'com.android.settings'},url:{url:'https://example.com'},settingsAction:{settingsAction:'android.settings.SETTINGS'},activityTaskId:{activityTaskId:123},intent:{intent:Array.isArray(args.intent?.extras)||args.intent?.extras?timerIntent:viewIntent},share:{share:{mimeType:'text/plain',text:'example'}}})[key];
   else if(key==='x')example={x:100,y:200};
   else if(key==='target'){const {selector,...rest}=example;example={...rest,target:'returned_target_id',observation:'returned_observation_id'};}
  }
  return {sessionId,...example};
 }
 if(short==='task'){
  const op=args.operation||'begin';
  if(op==='inspect')return {operation:'inspect',sessionId};
  if(op==='list')return {operation:'list'};
  if(op==='finish'||op==='share')return {operation:op,sessionId};
  if(op==='handoff')return {operation:'handoff',sessionId,confirmed:true};
  if(op==='record_status')return {operation:'record_status',sessionId};
  if(op==='record_start'||op==='record_stop'||/record/i.test(op))return {operation:/stop/i.test(op)?'record_stop':'record_start',sessionId};
  if(args.mode==='foreground')return {operation:'begin',mode:'foreground',confirmed:true};
  if(args.intent)return {operation:'begin',intent:args.intent.extras?timerIntent:viewIntent};
  if(args.package)return {operation:'begin',package:'com.android.settings'};
  if(args.url)return {operation:'begin',url:'https://example.com'};
  return {operation:'begin'};
 }
 if(short==='file'){
  const op=args.operation||'pull';
  if(op==='push')return {operation:'push',artifactId:'uploaded_artifact_id',remote:'/data/local/tmp/example.txt'};
  if(op==='fetch')return {operation:'fetch',url:'https://example.com/file',remote:'/data/local/tmp/example.txt'};
  return {operation:'pull',path:'/data/local/tmp/example.txt'};
 }
 if(short==='artifact'){
  const op=args.operation||'begin';
  if(op==='status')return {operation:'status',artifactId:'returned_artifact_id'};
  if(op==='delete')return {operation:'delete',artifactId:'art_'+'0'.repeat(24)};
  return {operation:'begin',name:'example.txt',size:1,sha256:'0'.repeat(64)};
 }
 if(short==='device'){
  const op=args.operation||'clipboard';
  if(op==='media')return {operation:'media',action:'status'};
  if(op==='location')return {operation:'location',timeoutMs:20000};
  if(op==='control')return {operation:'control',action:'status'};
  if(op==='audio')return {operation:'audio',action:'status',audioId:'audio_'+'0'.repeat(32)};
  if(op==='brightness'||/bright/i.test(op))return Object.hasOwn(args,'value')?{operation:'brightness',value:128}:{operation:'brightness'};
  return Object.hasOwn(args,'text')?{operation:'clipboard',text:'example'}:{operation:'clipboard'};
 }
 if(short==='notification'&&['open','action','reply','dismiss'].includes(args.operation))return {
  operation:args.operation,notificationId:'returned_notification_id',
  ...(['open','action'].includes(args.operation)?{sessionId}:{}),
  ...(['action','reply'].includes(args.operation)?{actionId:'action_0'}:{}),
  ...(args.operation==='reply'?{responses:{returned_reply_field:'example'}}:{})
 };
 if(short==='sequence'&&Array.isArray(args.steps)){
  const step=args.steps.find(s=>s&&typeof s==='object'&&typeof s.tool==='string'&&Object.hasOwn(primitives,s.tool.replace(/^android[_.]/,'')));
  if(step){const tool=step.tool.replace(/^android[_.]/,'');const {sessionId:ignoredSession,...arguments_}=exampleArguments('android_'+tool,step.arguments||{});return {sessionId,steps:[{tool:'android.'+tool,arguments:arguments_}]};}
 }
 return {
  sequence:{sessionId,steps:[{tool:'android.tap',arguments:primitives.tap}]},
  devices:{},packages:{query:'chrome'},logs:{kind:'crash'},notification:{operation:'list'},
  shell:{command:'id'},
  install:{source:{artifactId:'uploaded_artifact_id'}},
  job_status:{jobId:'returned_job_id'},
  record:{operation:'status',sessionId},
  confirm:{sessionId,title:'Confirm action',summary:'Review before proceeding.'},
  personal:{operation:'recent_photos'},
  events:{operation:'list'},
 }[short];
}
/** The subschema an Ajv schemaPath ("#/allOf/0/oneOf/1/not") points to, or undefined. */
export function schemaAt(root,schemaPath){
 if(!root||typeof schemaPath!=='string'||!schemaPath.startsWith('#'))return undefined;
 return schemaPath.slice(2).split('/').filter(Boolean).map(p=>p.replaceAll('~1','/').replaceAll('~0','~')).reduce((node,key)=>node==null?node:node[key],root);
}
const dataAt=(args,instancePath)=>instancePath.split('/').slice(1).reduce((v,k)=>v==null?v:v[k],args);
const fieldPath=p=>p.replace(/^\//,'').replaceAll('/','.');
const listValues=values=>values.length<=20?values.join(', '):values.slice(0,12).join(', ')+`, … (${values.length} values; see the tool description)`;
/** Fields one call may use: every property, minus those the selected operation forbids. */
function fieldsFor(schema,args){
 const all=Object.keys(schema?.properties||{}).filter(k=>!['requestId','display'].includes(k));
 const forbidden=new Set();
 const branches=[];
 (function walk(node){
  if(!node||typeof node!=='object')return;
  for(const key of ['allOf','oneOf','anyOf'])if(Array.isArray(node[key]))node[key].forEach(b=>{branches.push(b);walk(b);});
 })(schema);
 for(const b of branches){
  const op=b.properties?.operation;
  if(!op||!(op.const===args.operation||op.enum?.includes(args.operation)))continue;
  for(const r of b.not?.anyOf||[])for(const k of r.required||[])forbidden.add(k);
 }
 return all.filter(k=>!forbidden.has(k));
}
export function invalidArguments(name,args,errors,schema) {
 // Ignore other union alternatives once a supplied discriminator rules them out.
 const excluded=errors.filter(e=>['const','enum'].includes(e.keyword)).map(e=>e.schemaPath.match(/^(.*\/(?:oneOf|anyOf)\/\d+)(?:\/.*)?$/)?.[1]).filter(Boolean);
 const relevant=errors.filter(e=>!excluded.some(p=>e.schemaPath.startsWith(p+'/'))&&!['oneOf','anyOf'].includes(e.keyword));
 const issues=[],hints=new Set(),unknown=[];
 const add=(field,message)=>{if(!issues.some(i=>i.field===field&&i.message===message))issues.push({field,message});};
 const contexts=[{name,args,path:''}];
 if(name==='android_sequence'&&Array.isArray(args.steps))args.steps.forEach((s,i)=>{
  if(!s||typeof s!=='object')return;
  if(typeof s.tool!=='string')add(`steps.${i}.tool`,'is required: one of '+STEP_TOOLS.join(', '));
  else if(!STEP_TOOLS.includes(s.tool))add(`steps.${i}.tool`,'must be one of: '+STEP_TOOLS.join(', '));
  if(s.arguments&&typeof s.arguments==='object')contexts.push({name:s.tool||'',args:s.arguments,path:'/steps/'+i+'/arguments'});
 });
 for(const c of contexts){const {selected}=choice(c.name,c.args);if(selected.length>1)add(fieldPath(c.path)||'arguments','must select one argument format: '+selected.map(g=>g.join(' + ')).join(' or '));}
 const operation=typeof args.operation==='string'?args.operation:null;
 const top=choice(name,args),topMembers=new Set(top.groups.flat());
 const noFormat=top.groups.length&&!top.selected.length&&!top.optional;
 const isCondition=p=>/(^|\/)(verify|before)$/.test(p);
 const conditionHelp='must be {"conditions":[{"kind":"exists","selector":{"label":"Ready"}}],"match":"all"}; kind is exists, absent, appeared, field, state, window or stable';
 const kept=relevant.filter(e=>{
  const c=contexts.findLast(c=>e.instancePath===c.path);
  if(c){const {groups,selected}=choice(c.name,c.args);
   if(selected.length===1&&e.keyword==='required'&&groups.flat().includes(e.params.missingProperty)&&!selected[0].includes(e.params.missingProperty))return false;
   if(selected.length&&e.keyword==='not')return false;
  }
  // With no format chosen, the choices are named once below.
  if(noFormat&&!e.instancePath&&e.keyword==='required'&&topMembers.has(e.params.missingProperty))return false;
  if(/^\/steps\/\d+$/.test(e.instancePath)&&e.keyword==='required'&&['tool','arguments'].includes(e.params.missingProperty)&&issues.some(i=>i.field===fieldPath(e.instancePath)+'.tool'))return false;
  // The gateway adds requestId itself.
  if(e.params.missingProperty==='requestId')return false;
  const here=dataAt(args,e.instancePath);
  if(isCondition(e.instancePath)){
   // A {conditions:[...]} group was chosen: errors from the older single-leaf forms do not apply.
   if(here&&typeof here==='object'&&Array.isArray(here.conditions))return e.keyword==='additionalProperties'&&!['conditions','match','kind','selector','observation','value','checked','selected','stateDescription','package','title','activity','selectors','region','durationMs','all','any'].includes(e.params.additionalProperty);
   if(here&&typeof here==='object'&&!Object.hasOwn(here,'kind')){add(fieldPath(e.instancePath),conditionHelp);return false;}
  }
  // An enum error at the same place already names the allowed values.
  if(e.keyword==='type'&&relevant.some(o=>o.keyword==='enum'&&o.instancePath===e.instancePath))return false;
  return true;
 });
 // Required fields that are alternatives of one choice ("label or resourceId") become one issue.
 const alternatives=new Map(),branchOf=e=>e.schemaPath.match(/^(.*\/(?:oneOf|anyOf))\/\d+\//)?.[1];
 for(const e of kept)if(e.keyword==='required'&&branchOf(e)){
  const key=e.instancePath+'|'+branchOf(e),row=alternatives.get(key)||new Map(),set=row.get(e.schemaPath)||new Set();set.add(e.params.missingProperty);row.set(e.schemaPath,set);alternatives.set(key,row);
 }
 const grouped=new Set();

 for(const e of kept){
  const missing=e.params.missingProperty,extra=e.params.additionalProperty;
  const path=fieldPath(e.instancePath+(missing??extra?'/'+(missing??extra):''))||'arguments';
  if(e.keyword==='required'){
   const key=e.instancePath+'|'+branchOf(e),choices=alternatives.get(key);
   // Fields from one required list are all needed; separate branches are alternatives.
   if(choices?.size>1){if(!grouped.has(key)){grouped.add(key);add(fieldPath(e.instancePath)||'arguments','needs '+[...choices.values()].map(c=>[...c].join(' and ')).join(' or '));}continue;}
   add(path,FIELD_HELP[missing]?'is required: '+FIELD_HELP[missing]:'is required');continue;
  }
  if(e.keyword==='maximum'&&path==='timeoutMs'){add(path,`must be at most ${e.params.limit}; to wait longer, call again`);continue;}
  if(e.keyword==='maxItems'){add(path,`has at most ${e.params.limit} items`+(path==='steps'?'; split the work into more calls':''));continue;}
  if(e.keyword==='additionalProperties'){
   for(const [re,hint] of WHERE)if(re.test(extra))hints.add(hint);
   if(!e.instancePath){unknown.push(extra);continue;}
   const parent=schemaAt(schema,e.schemaPath.replace(/\/additionalProperties$/,''));
   const allowed=Object.keys(parent?.properties||{});
   add(path,'is not supported'+(allowed.length?'. Use: '+allowed.join(', '):''));continue;
  }
  if(e.keyword==='enum'||e.keyword==='const'){
   const values=e.keyword==='enum'?e.params.allowedValues:[e.params.allowedValue];
   if(fieldPath(e.instancePath)==='operation'&&operation)for(const [re,hint] of WHERE)if(re.test(operation))hints.add(hint);
   add(path,e.keyword==='const'&&values.length===1?'must be '+JSON.stringify(values[0]):'must be one of: '+listValues(values));continue;
  }
  if(e.keyword==='not'){
   const rule=schemaAt(schema,e.schemaPath),here=dataAt(args,e.instancePath);
   const present=k=>here&&typeof here==='object'&&Object.hasOwn(here,k);
   if(rule?.required?.every(present)){add(path,rule.required.join(' and ')+' cannot be used together; choose one');continue;}
   const used=(rule?.anyOf||[]).flatMap(r=>r.required||[]).filter(k=>present(k)&&k!=='requestId');
   if(used.length){add(path,used.join(', ')+(used.length>1?' are':' is')+' not used'+(operation&&!e.instancePath?` with operation "${operation}"`:' with these arguments'));continue;}
   if(rule&&JSON.stringify(rule).includes('"requestId"'))continue;
   add(path,'conflicts with another argument');continue;
  }
  if(e.keyword==='pattern'){const key=missing??extra??e.instancePath.split('/').pop();add(path,PATTERN_HELP[key]||e.message);continue;}
  if(e.keyword==='type'&&/extras$/.test(e.instancePath)){add(path,'must be an array of {"name","type","value"}: type is string, uri, boolean, integer or long; value is text');continue;}
  add(path,e.message);
 }
 if(unknown.length)issues.unshift({field:unknown.join(', '),message:`${unknown.length>1?'are':'is'} not supported. Use: ${fieldsFor(schema,args).join(', ')}`});
 if(noFormat)issues.unshift({field:'arguments',message:'must include '+top.groups.map(g=>g.filter(k=>k!=='clipboard'&&(k!=='screenshotId'||g[0]==='focused')).join(' and ')).join(', or ')});
 if(!issues.length)issues.push({field:'arguments',message:'must match the example'});
 const shown=issues.slice(0,4);
 const example=exampleArguments(name,args);
 return {outcome:'failed',code:'invalid_argument',inputDelivered:false,
  error:shown.map(i=>`${i.field} ${i.message}`).join('; ')+'.'+(hints.size?' '+[...hints].join(' '):''),issues:shown,
  example,
  ...(JSON.stringify(example).includes('session_')?{hint:'Use the sessionId from android_task begin.'}:{})};
}
