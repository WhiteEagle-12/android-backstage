#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {catalogue,TOOL_CONTRACT} from '../gateway/server.mjs';

// Accept a tools/list response or a name -> visible parameter names export.
export function compareClientTools(snapshot) {
  const list=snapshot.result?.tools??snapshot.tools;
  const parameters=Array.isArray(list)?Object.fromEntries(list.map(t=>[t.name,Object.keys(t.inputSchema?.properties??{})])):snapshot.parameters;
  if(!parameters||typeof parameters!=='object')throw new Error('Supply tools/list JSON or a parameters map from the client.');
  const missingTools=[],missingParameters={},missingNestedFields=[];
  for(const tool of catalogue) {
    if(!Object.hasOwn(parameters,tool.name)){missingTools.push(tool.name);continue;}
    const missing=Object.keys(tool.inputSchema.properties).filter(key=>!parameters[tool.name].includes(key));
    if(missing.length)missingParameters[tool.name]=missing;
    if(Array.isArray(list)){
      const actual=list.find(t=>t.name===tool.name)?.inputSchema;
      const compare=(expected,seen,path)=>{
        if(expected.type&&seen?.type!==expected.type){missingNestedFields.push({field:path,expectedType:expected.type});return;}
        if(expected.type==='object')for(const [key,spec] of Object.entries(expected.properties||{})){
          if(!seen.properties?.[key])missingNestedFields.push({field:path+'.'+key,expectedType:spec.type||'typed'});
          else compare(spec,seen.properties[key],path+'.'+key);
        }
        if(expected.type==='array'&&expected.items)compare(expected.items,seen?.items,path+'[]');
      };
      compare(tool.inputSchema,actual,tool.name);
    }
  }
  return {ok:missingTools.length===0&&Object.keys(missingParameters).length===0&&missingNestedFields.length===0,toolContract:TOOL_CONTRACT,missingTools,missingParameters,missingNestedFields,nestedChecked:Array.isArray(list)};
}
if(process.argv[1]&&import.meta.url===pathToFileURL(path.resolve(process.argv[1])).href) {
  if(!process.argv[2])throw new Error('Usage: node scripts/check-client-tools.mjs client-tools.json');
  const result=compareClientTools(JSON.parse(fs.readFileSync(process.argv[2],'utf8')));
  console.log(JSON.stringify(result,null,2));
  if(!result.ok)process.exitCode=1;
}
