import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {execFileSync} from 'node:child_process';
import {CONTRACT} from '../../cli/tools.mjs';
test('generated native contract and model schemas stay synchronized',()=>{
 const root=new URL('../../',import.meta.url);const before=fs.readFileSync(new URL('app/app/src/main/java/com/deviceagent/tools/ToolCatalog.java',root),'utf8');
 execFileSync(process.execPath,['scripts/generate-tool-contract.mjs'],{cwd:root});assert.equal(fs.readFileSync(new URL('app/app/src/main/java/com/deviceagent/tools/ToolCatalog.java',root),'utf8'),before);
 const names=CONTRACT.map(t=>t.name);assert.equal(new Set(names).size,names.length);
});
