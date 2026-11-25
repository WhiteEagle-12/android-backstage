// Examples contain placeholders, never values copied from a rejected request.
const sessionId='session_'+'0'.repeat(32),requestId='new_write_id';
const primitives={
 observe:{},open:{url:'https://example.com'},tap:{selector:{label:'Save'}},
 type:{selector:{label:'Search'},text:'example'},key:{key:'BACK'},
 gesture:{preset:'swipe',x1:100,y1:300,x2:100,y2:100},
 scroll:{selector:{label:'List'},direction:'down'},
 wait:{verify:{conditions:[{kind:'exists',selector:{label:'Ready'}}]}}
};
function choice(name,args={}){
 const short=String(name).replace(/^android[_.]/,'');
 const groups=short==='open'?[['package'],['url'],['settingsAction'],['activityTaskId'],['intent'],['share']]:['tap','type','scroll','scroll_until'].includes(short)?[['target','observation'],['selector'],...(short==='tap'?[['x','y','screenshotId']]:[])]:[];
 const selected=groups.filter(g=>g.some(k=>Object.hasOwn(args,k)));
 return {groups,selected};
}
export function exampleArguments(name,args={}) {
 const short=name.replace(/^android_/,'');
 if(Object.hasOwn(primitives,short)){
  let example=primitives[short];
  if(short==='type'&&args.focused===true)example={focused:true,screenshotId:'returned_screenshot_id',mode:'insert',clipboard:true,text:'example'};
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
   if(short==='open')example=({package:{package:'com.android.settings'},url:{url:'https://example.com'},settingsAction:{settingsAction:'android.settings.SETTINGS'},activityTaskId:{activityTaskId:123},intent:{intent:{action:'android.intent.action.VIEW',data:'https://example.com'}},share:{share:{mimeType:'text/plain',text:'example'}}})[key];
   else if(key==='x')example={x:100,y:200};
   else if(key==='target'){const {selector,...rest}=example;example={...rest,target:'returned_target_id',observation:'returned_observation_id'};}
  }
  return {sessionId,...(!['observe','wait'].includes(short)?{requestId}:{}),...example};
 }
 if(short==='task'){
  const op=args.operation||'begin';
  if(op==='inspect')return {operation:'inspect',sessionId};
  if(op==='list')return {operation:'list'};
  if(op==='finish'||op==='share')return {operation:op,sessionId,requestId};
  if(op==='handoff')return {operation:'handoff',sessionId,requestId,confirmed:true};
  if(op==='record_status')return {operation:'record_status',sessionId};
  if(op==='record_start'||op==='record_stop')return {operation:op,sessionId,requestId};
  if(args.mode==='foreground')return {operation:'begin',requestId,mode:'foreground',confirmed:true};
  if(args.package)return {operation:'begin',requestId,package:'com.android.settings'};
  if(args.url)return {operation:'begin',requestId,url:'https://example.com'};
  return {operation:'begin',requestId};
 }
 if(short==='file'){
  const op=args.operation||'pull';
  if(op==='push')return {operation:'push',artifactId:'uploaded_artifact_id',remote:'/data/local/tmp/example.txt',requestId};
  if(op==='fetch')return {operation:'fetch',url:'https://example.com/file',remote:'/data/local/tmp/example.txt',requestId};
  return {operation:'pull',path:'/data/local/tmp/example.txt'};
 }
 if(short==='artifact'){
  const op=args.operation||'begin';
  if(op==='status')return {operation:'status',artifactId:'returned_artifact_id'};
  if(op==='delete')return {operation:'delete',artifactId:'art_'+'0'.repeat(24),requestId};
  return {operation:'begin',name:'example.txt',size:1,sha256:'0'.repeat(64),requestId};
 }
 if(short==='device'){
  const op=args.operation||'clipboard';
  if(op==='media')return {operation:'media',action:'status',requestId};
  if(op==='location')return {operation:'location',timeoutMs:20000};
  if(op==='control')return {operation:'control',action:'status'};
  if(op==='audio')return {operation:'audio',action:'status',audioId:'audio_'+'0'.repeat(32)};
  return {operation:'clipboard'};
 }
 if(short==='notification'&&['open','action','reply','dismiss'].includes(args.operation))return {
  requestId,operation:args.operation,notificationId:'returned_notification_id',
  ...(['open','action'].includes(args.operation)?{sessionId}:{}),
  ...(['action','reply'].includes(args.operation)?{actionId:'action_0'}:{}),
  ...(args.operation==='reply'?{responses:{returned_reply_field:'example'}}:{})
 };
 if(short==='sequence'&&Array.isArray(args.steps)){
  const step=args.steps.find(s=>s&&typeof s==='object'&&typeof s.tool==='string'&&Object.hasOwn(primitives,s.tool.replace(/^android[_.]/,'')));
  if(step){const tool=step.tool.replace(/^android[_.]/,'');const {sessionId:ignoredSession,requestId:ignoredRequest,...arguments_}=exampleArguments('android_'+tool,step.arguments||{});return {sessionId,requestId,steps:[{tool:'android.'+tool,arguments:arguments_}]};}
 }
 return {
  sequence:{sessionId,requestId,steps:[{tool:'android.tap',arguments:primitives.tap}]},
  devices:{},packages:{query:'chrome'},logs:{kind:'crash'},notification:{operation:'list'},
  shell:{command:'id',requestId},
  install:{source:{artifactId:'uploaded_artifact_id'},requestId},
  job_status:{jobId:'returned_job_id'},
  record:{operation:'status',sessionId},
  confirm:{sessionId,requestId,title:'Confirm action',summary:'Review before proceeding.'},
  personal:{operation:'recent_photos'},
  events:{operation:'list'},
 }[short];
}
export function invalidArguments(name,args,errors) {
 // Ignore other union alternatives once a supplied discriminator rules them out.
 const excluded=errors.filter(e=>['const','enum'].includes(e.keyword)).map(e=>e.schemaPath.match(/^(.*\/(?:oneOf|anyOf)\/\d+)(?:\/.*)?$/)?.[1]).filter(Boolean);
 const relevant=errors.filter(e=>!excluded.some(p=>e.schemaPath.startsWith(p+'/'))&&!['oneOf','anyOf'].includes(e.keyword));
 const issues=[];
 const contexts=[{name,args,path:''}];
 if(name==='android_sequence'&&Array.isArray(args.steps))args.steps.forEach((s,i)=>{if(s&&typeof s==='object'&&s.arguments&&typeof s.arguments==='object')contexts.push({name:s.tool||'',args:s.arguments,path:'/steps/'+i+'/arguments'});});
 for(const c of contexts){const {selected}=choice(c.name,c.args);if(selected.length>1)issues.push({field:c.path.slice(1).replaceAll('/','.')||'arguments',message:'must select one argument format: '+selected.map(g=>g.join(' + ')).join(' or ')});}

 for(const e of relevant){
  const c=contexts.findLast(c=>e.instancePath===c.path);
  if(c){const {groups,selected}=choice(c.name,c.args);
   if(selected.length===1&&e.keyword==='required'&&groups.flat().includes(e.params.missingProperty)&&!selected[0].includes(e.params.missingProperty))continue;
   if(selected.length&&e.keyword==='not')continue;
  }

  const field=e.params.missingProperty??e.params.additionalProperty;
  const path=(e.instancePath+(field?'/'+field:'')).replace(/^\//,'').replaceAll('/','.');
  const message=e.keyword==='required'?'is required':e.keyword==='additionalProperties'?'is not supported':e.keyword==='not'?'conflicts with another argument':e.message;
  const issue={field:path||'arguments',message};
  if(!issues.some(i=>i.field===issue.field&&i.message===message))issues.push(issue);
 }
 // With no target chosen, name the choices once instead of listing each one as required.
 const top=choice(name,args);
 if(top.groups.length&&!top.selected.length){
  const members=new Set(top.groups.flat()),rest=issues.filter(i=>!(i.message==='is required'&&members.has(i.field)));
  issues.length=0;issues.push({field:'arguments',message:'must include '+top.groups.map(g=>g.join(' and ')).join(', or ')},...rest);
 }
 if(!issues.length)issues.push({field:'arguments',message:'must use one of the supported argument choices'});
 const shown=issues.slice(0,4);
 return {outcome:'failed',code:'invalid_argument',inputDelivered:false,
  error:shown.map(i=>`${i.field} ${i.message}`).join('; ')+'.',issues:shown,
  example:exampleArguments(name,args),
  ...(JSON.stringify(exampleArguments(name,args)).includes('session_')?{hint:'Use the sessionId from android_task begin and a new requestId for a write.'}:{})};
}
