import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {writePrivateJson} from './state.mjs';
const read = (value,key) => key.split('.').reduce((v,k)=>v?.[k],value);
export function substitute(value,vars) {
  if(typeof value==='string') {
    const full=/^\$\{([\w.]+)\}$/.exec(value);
    if(full){const v=read(vars,full[1]);if(v===undefined)throw new Error(`Missing variable ${full[1]}`);return v;}
    return value.replace(/\$\{([\w.]+)\}/g,(_,key)=>{const v=read(vars,key);if(v===undefined)throw new Error(`Missing variable ${key}`);return String(v);});
  }
  if(Array.isArray(value))return value.map(x=>substitute(x,vars));
  if(value&&typeof value==='object')return Object.fromEntries(Object.entries(value).map(([k,v])=>[k,substitute(v,vars)]));
  return value;
}
export async function runWorkflow(spec,vars,call,{outputDir,device}={}) {
  if(spec.version!==1||!Array.isArray(spec.steps)||spec.steps.length>100)throw new Error('Workflow needs version:1 and at most 100 steps');
  const id=crypto.randomUUID(), state={...vars}, receipt={id,name:spec.name||'Workflow',device,startedAt:new Date().toISOString(),steps:[],status:'running'};
  const file=path.join(outputDir||'.',id+'.json');writePrivateJson(file,receipt);
  for(const step of spec.steps){
    const row={tool:step.tool,template:step,startedAt:new Date().toISOString()};
    try{
      if(typeof step.tool!=='string'||!step.tool.startsWith('android.'))throw new Error('Invalid tool');
      row.arguments=substitute(step.arguments||{},state);
      row.result=await call(step.tool,row.arguments);
      if(row.result?.ok===false||row.result?.isError||["failed","unknown"].includes(row.result?.outcome))throw new Error(row.result.error||'Tool failed');
      for(const check of step.assert||[]){
        const actual=read(row.result,check.path);
        if(Object.hasOwn(check,'equals') && JSON.stringify(actual)!==JSON.stringify(substitute(check.equals,state)))throw new Error(`Assertion failed: ${check.path}`);
        if(Object.hasOwn(check,'includes') && !String(actual).includes(String(substitute(check.includes,state))))throw new Error(`Assertion failed: ${check.path}`);
      }
      if(step.saveAs){if(!/^[A-Za-z][A-Za-z0-9_]*$/.test(step.saveAs))throw new Error('Invalid saveAs');state[step.saveAs]=row.result;}
      row.status='passed';
    }catch(e){row.status='failed';row.error=e.message;receipt.status='failed';}
    row.finishedAt=new Date().toISOString();receipt.steps.push(row);writePrivateJson(file,receipt);
    if(receipt.status==='failed')break; // Never retry a potentially completed side effect.
  }
  if(receipt.status==='running')receipt.status='passed';
  receipt.finishedAt=new Date().toISOString();writePrivateJson(file,receipt);
  return {status:receipt.status,receipt:file,steps:receipt.steps.length};
}
export function compileReceipt(receipt) {
  if(receipt.status!=='passed'||!Array.isArray(receipt.steps))throw new Error('Only a passed receipt can become a routine');
  return {version:1,name:receipt.name,steps:receipt.steps.map(s=>{
    if(s.template) return s.template;
    if(Object.hasOwn(s.arguments||{},'display'))throw new Error('Receipt has a fixed display ID; author a workflow using ${launch.display} before compiling');
    return {tool:s.tool,arguments:s.arguments};
  })};
}
