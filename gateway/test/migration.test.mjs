import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {migrateLegacy} from '../migrate-legacy.mjs';
import {createConnections} from '../connections.mjs';
import {writePrivateJson} from '../../cli/state.mjs';
test('migration preserves approved tokens and one original locked counter file without copying keys',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'backstage-migrate-'));
 try {
 const configFile=path.join(dir,'original.json'),stateFile=path.join(dir,'oauth.json'),directory=path.join(dir,'connections');
 const config={apiKey:'test-private',agentId:'agt_test',device:'phone',links:{phone:{linkKey:'test-key',callCounter:'20'}}};
 writePrivateJson(configFile,config);writePrivateJson(stateFile,{clients:{a:{}},codes:{c:{clientId:'a'}},access:{a:{clientId:'a'},b:{clientId:'b'}},refresh:{r:{clientId:'a'}}});
 assert.equal(migrateLegacy({configFile,stateFile,directory}).migrated,4);
 assert.equal(migrateLegacy({configFile,stateFile,directory}).migrated,0);
 const state=JSON.parse(fs.readFileSync(stateFile));const id=state.access.a.grantId;
 assert.equal(state.codes.c.grantId,id);assert.equal(state.refresh.r.grantId,id);
 assert.ok(!fs.readFileSync(path.join(directory,id+'.json'),'utf8').includes('test-private'));
 const c=createConnections({directory,relayUrl:'https://example.com',call:async cfg=>{assert.equal(cfg.filePath,configFile);assert.equal(cfg.links.phone.callCounter,'20');return {ok:true};}});
 assert.equal((await c.call('test',{},id)).ok,true);
 c.revoke(id);assert.equal(c.active(id),false);assert.equal(c.active(state.access.b.grantId),true);assert.deepEqual(JSON.parse(fs.readFileSync(configFile)),config);
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
