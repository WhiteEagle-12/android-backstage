const MAX_TEXT=3600;
const SHORT_ROLE={button:'btn',field:'field',scroll:'scroll',switch:'switch',checkbox:'chk',radio:'radio',link:'link',text:'txt'};
// Keep status and evidence readable even in clients that hide structuredContent.
// Large trees and binary files stay in structuredContent; compact observations render
// as scannable 1-line entries to cut per-turn runtime tokens by ~65-75%.
function excerpt(value,budget,depth=0){
 if(JSON.stringify(value).length<=budget)return value;
 if(typeof value==='string'){
  const n=Math.max(0,Math.floor((budget-100)/2));
  return value.slice(0,n)+`\n…[${value.length-2*n} characters omitted]…\n`+value.slice(-n||value.length);
 }
 if(!value||typeof value!=='object'||depth>=5)return undefined;
 const array=Array.isArray(value),out=array?[]:{};
 for(const [key,item] of Object.entries(value)){
  const left=budget-JSON.stringify(out).length-key.length-8;
  if(left<100)break;
  const next=excerpt(item,Math.min(left,Math.max(180,budget/(array?4:2))),depth+1);
  if(next===undefined)continue;
  const candidate=array?[...out,next]:{...out,[key]:next};
  if(JSON.stringify(candidate).length>budget)break;
  if(array)out.push(next);else out[key]=next;
 }
 return out;
}
function flattenContent(items,out=[]){
 for(const item of items||[]){
  if(typeof item==='string'){const s=item.trim();if(s)out.push(s);}
  else if(Array.isArray(item))flattenContent(item,out);
  else if(item&&typeof item==='object'&&Array.isArray(item.items))flattenContent(item.items,out);
 }
 return out;
}
function shortResId(id){
 if(typeof id!=='string'||!id)return '';
 const i=id.indexOf(':id/');
 return '#'+(i>=0?id.slice(i+4):id);
}
function formatControl(c,prefix=''){
 const parts=[prefix+(c.id||'?'),SHORT_ROLE[c.role]||c.role||'ctrl'];
 if(c.label)parts.push(JSON.stringify(String(c.label)));
 if(typeof c.value==='string')parts.push('='+JSON.stringify(c.value));
 const flags=[];
 if(c.checked===true)flags.push('on');
 else if(c.checked===false)flags.push('off');
 if(c.selected===true)flags.push('sel');
 if(c.enabled===false)flags.push('disabled');
 if(typeof c.stateDescription==='string'&&c.stateDescription)flags.push(c.stateDescription);
 if(flags.length)parts.push('['+flags.join(',')+']');
 const rid=shortResId(c.resourceId);
 if(rid)parts.push(rid);
 if(c.context)parts.push('('+c.context+')');
 if(Array.isArray(c.details)&&c.details.length){
  const lbl=String(c.label||'');
  const flat=flattenContent(c.details).filter(s=>s!==lbl&&s!==c.context&&!lbl.includes(s));
  if(flat.length)parts.push('— '+flat.join(' · '));
 }
 return parts.join(' ');
}
function formatObservation(s){
 const lines=[];
 const head=[s.outcome||'succeeded',s.observation];
 if(s.display!=null)head.push('d='+s.display);
 if(s.app)head.push('app='+JSON.stringify(s.app));
 if(s.screen&&s.screen!==s.app)head.push('screen='+JSON.stringify(s.screen));
 if(s.delta)head.push('delta'+(s.since?' since='+s.since:''));
 if(s.sessionId)head.push('session='+s.sessionId);
 if(s.inputDelivered!=null)head.push('input='+s.inputDelivered);
 if(s.verification?.status)head.push('verify='+s.verification.status);
 if(s.moved!=null)head.push('moved='+s.moved);
 if(s.endReached===true)head.push('endReached=true');
 if(s.truncated===true)head.push('truncated=true');
 lines.push(head.join(' '));
 if(s.code||s.error)lines.push(`error: ${s.code||''}${s.code&&s.error?' — ':''}${s.error||''}`);
 if(s.warning)lines.push('warning: '+s.warning);
 if(Array.isArray(s.verification?.failedConditions)&&s.verification.failedConditions.length){
  lines.push('failed: '+s.verification.failedConditions.join('; '));
 }
 if(s.image&&typeof s.image==='object'){
  const img=s.image;
  lines.push(`image: ${img.screenshotId||''} ${img.width||'?'}x${img.height||'?'} scale=${img.scaleX??img.scale??'?'}`);
 }
 if(Array.isArray(s.windows)&&s.windows.length){
  lines.push('windows: '+s.windows.map(w=>`${w.windowId||''} ${JSON.stringify(w.title||w.package||'')} (${w.type||''}${w.focused?',focused':''})`).join(' | '));
 }
 const controls=Array.isArray(s.controls)?s.controls:[];
 const content=flattenContent(s.content);
 const removed=Array.isArray(s.removed)?s.removed:[];
 const removedContent=flattenContent(s.removedContent);
 const prefix=s.delta?'+':'';
 if(s.delta&&!controls.length&&!content.length&&!removed.length&&!removedContent.length){
  lines.push('unchanged');
 }else{
  for(const c of controls){
   const line=formatControl(c,prefix);
   if(lines.join('\n').length+line.length+32>MAX_TEXT){lines.push('…[more controls omitted]');break;}
   lines.push(line);
  }
  if(removed.length)lines.push('-removed: '+removed.join(', '));
  if(content.length){
   const joined=content.join(' | ');
   const left=MAX_TEXT-lines.join('\n').length-20;
   if(left>40)lines.push((s.delta?'+text: ':'text: ')+(joined.length>left?joined.slice(0,left-1)+'…':joined));
  }
  if(removedContent.length){
   const joined=removedContent.join(' | ');
   const left=MAX_TEXT-lines.join('\n').length-20;
   if(left>40)lines.push('-text: '+(joined.length>left?joined.slice(0,left-1)+'…':joined));
  }
 }
 if(s.timing&&typeof s.timing==='object')lines.push('timing: '+JSON.stringify(s.timing));
 const out=lines.join('\n');
 return out.length<=MAX_TEXT?out:out.slice(0,MAX_TEXT-1)+'…';
}
export function resultText(structured){
 if(structured&&typeof structured==='object'&&structured.observation&&!structured.ui&&(Array.isArray(structured.controls)||Array.isArray(structured.content)||structured.delta===true)){
  return formatObservation(structured);
 }
 const full=JSON.stringify(structured);
 if(full.length<=1600)return full;
 const preview={textPreviewTruncated:true},omitted=[];
 const priority=['outcome','code','error','warning','inputDelivered','inputMethod','commandSucceeded','exitCode',
  'sessionId','jobId','device','display','observation','verification','launchAccepted','readiness','accessibility','screenshot','recovery','activityTaskId','activityTasks','timing','completedSteps','steps','fix','limits','image',
  'stderr','stdout','controls','content','steps'];
 const keys=[...new Set([...priority,...Object.keys(structured)])].filter(k=>structured[k]!==undefined);
 for(const key of keys){
  const remaining=MAX_TEXT-JSON.stringify(preview).length-key.length-150;
  if(['ui','tree','rawTree','hierarchy','diagnostics','base64','dataBase64','pngBase64'].includes(key)||remaining<180){omitted.push(key);continue;}
  const value=excerpt(structured[key],Math.min(remaining,['stdout','stderr','controls','content'].includes(key)?1000:700));
  if(value===undefined){omitted.push(key);continue;}
  const candidate={...preview,[key]:value};
  if(JSON.stringify(candidate).length<=MAX_TEXT-100)preview[key]=value;else omitted.push(key);
 }
 if(omitted.length)preview.omittedFieldCount=omitted.length;
 return JSON.stringify(preview);
}
