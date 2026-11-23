// The shorter discovery form has the same wire meaning on existing 2.x phones.
// Preserve malformed input so the phone still rejects it; never repair a write.
function condition(value) {
 if(!value||typeof value!=='object'||Array.isArray(value)||!Array.isArray(value.conditions)||
    Object.keys(value).some(k=>!['conditions','match'].includes(k))||!['all','any'].includes(Object.hasOwn(value,'match')?value.match:'all'))return value;
 return {[Object.hasOwn(value,'match')?value.match:'all']:value.conditions};
}
export function wireArguments(tool,args) {
 if(!args||typeof args!=='object'||Array.isArray(args))return args;
 if(!['android.observe','android.open','android.tap','android.type','android.key','android.gesture','android.scroll','android.scroll_until','android.wait','android.sequence'].includes(tool))return args;
 const out={...args};
 if(Object.hasOwn(out,'verify'))out.verify=condition(out.verify);
 if(tool==='android.sequence'&&Array.isArray(out.steps))out.steps=out.steps.map(step=>{
  if(!step||typeof step!=='object'||Array.isArray(step))return step;
  const next={...step};
  if(Object.hasOwn(next,'before'))next.before=condition(next.before);
  if(Object.hasOwn(next,'arguments'))next.arguments=wireArguments(next.tool,next.arguments);
  return next;
 });
 return out;
}
