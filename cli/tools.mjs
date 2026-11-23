/**
 * android.* tool catalogue and per-tool budgets. Served by the CLI MCP
 * stdio server; the relay never sees these names.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {advertisedSchema} from './advertised-schema.mjs';
import {resultText} from './result-preview.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const VERSION = "2.0.0";
export const INSTRUCTIONS = fs.readFileSync(path.join(__dirname, "instructions.txt"), "utf8").trim();

// Leave time for a mobile network to deliver both the request and its receipt.
export const DEFAULT_BUDGET_MS = 45_000;
export const SLOW_MS = 30_000;

export const SLOW_TOOLS = new Map([
  ...['observe','open','tap','type','key','gesture','scroll','scroll_until','wait','sequence','task','task_begin','task_inspect','task_finish','task_share','task_list','handoff','location','record','device'].map(name=>['android.'+name,90000]),
  ['android.confirm',135000],
  ...['install','maintenance','fetch','push','file'].map(name=>['android.'+name,600000]),
  ['android.developer',620000],['android.media',30000]
]);

export function budgetMs(name) {
  return SLOW_TOOLS.get(name) ?? DEFAULT_BUDGET_MS;
}

export const CONTRACT = JSON.parse(fs.readFileSync(path.join(__dirname, "tool-contract.json"), "utf8"));
export const TOOLS = CONTRACT.map(t=>[t.name,t.description,t.inputSchema.properties]);
export function toolSchema(args) { return {type:'object',properties:args,additionalProperties:false}; }
const DESTRUCTIVE_TOOLS = new Set(['android.shell', 'android.install', 'android.file', 'android.developer', 'android.maintenance', 'android.device', 'android.open', 'android.tap', 'android.type', 'android.key', 'android.gesture', 'android.sequence', 'android.notification']);
const OPEN_WORLD_TOOLS = new Set(['android.open', 'android.file', 'android.install', 'android.events', 'android.shell']);
export function toolAnnotations(name, readOnly = false) {
  const dotName = String(name || '').replace(/^android_/, 'android.');
  const ro = Boolean(readOnly) || ['android.observe', 'android.wait', 'android.packages', 'android.logs', 'android.devices', 'android.job_status', 'android.personal'].includes(dotName);
  return {
    readOnlyHint: ro,
    destructiveHint: ro ? false : DESTRUCTIVE_TOOLS.has(dotName),
    idempotentHint: ro || ['android.wait', 'android.devices', 'android.job_status'].includes(dotName),
    openWorldHint: OPEN_WORLD_TOOLS.has(dotName),
  };
}
export function mcpTools({developer=false,strict=false}={}) {
  return CONTRACT.filter(t=>developer||!t.surface).map(({name,description,inputSchema,readOnly})=>({name,description,inputSchema:strict?inputSchema:advertisedSchema(inputSchema,inputSchema,{toolName:name}),annotations:toolAnnotations(name,readOnly)}));
}

export function asToolResult(payload, isError = false) {
  const images=[];
  function clean(value) {
    if (!value || typeof value !== 'object') return value;
    if (Array.isArray(value)) return value.map(clean);
    const out={};
    const data=value.pngBase64 || value.base64;
    const mime=value.mime || value.mimeType;
    const isImage=typeof data==='string' && /^image\//.test(mime || '');
    for (const [key,item] of Object.entries(value)) {
      if(isImage && ['pngBase64','base64'].includes(key)) continue;
      out[key]=clean(item);
    }
    if(isImage) {out.imageIndex=images.length;images.push({type:'image',data,mimeType:mime});}
    return out;
  }
  const structured=clean(typeof payload==='string'?{text:payload}:payload);
  const failed=isError || structured?.ok===false || ['failed','unknown'].includes(structured?.outcome);
  if(structured && typeof structured==='object'){if(!structured.outcome)structured.outcome=failed?'failed':'succeeded';delete structured.ok;delete structured.expectedResultObserved;delete structured.verified;}
  const text=resultText(structured);
  return {content:[{type:'text',text},...images],structuredContent:structured,isError:failed};
}
