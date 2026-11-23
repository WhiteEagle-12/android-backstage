import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {writePrivateJson} from '../cli/state.mjs';

// Run with the old gateway stopped. Retain one shared reference for the existing,
// owner-approved phone link; never duplicate its encryption identity or counters.
export function migrateLegacy({stateFile,configFile,directory}) {
  const state=JSON.parse(fs.readFileSync(stateFile,'utf8'));
  const rows=['codes','access','refresh'].flatMap(key=>Object.values(state[key] || {}));
  if (!rows.some(row=>!row.grantId)) return {migrated:0};
  const config=JSON.parse(fs.readFileSync(configFile,'utf8'));
  if (!config.apiKey || !config.agentId || !config.links?.[config.device]?.linkKey) throw new Error('Legacy phone config is incomplete');
  fs.mkdirSync(directory,{recursive:true,mode:0o700});
  const marker=path.join(directory,'legacy-migration.json');
  let migration = {configFile:path.resolve(configFile), grants:{}};
  if(fs.existsSync(marker)) {
    migration=JSON.parse(fs.readFileSync(marker,'utf8'));
    if(migration.configFile!==path.resolve(configFile))throw new Error('Legacy migration belongs to another config');
  }
  for (const row of rows) if (!row.grantId && !Object.hasOwn(migration.grants,row.clientId))
    migration.grants[row.clientId]='grant_'+crypto.randomBytes(16).toString('hex');
  writePrivateJson(marker,migration);
  for (const id of Object.values(migration.grants)) {
    const target=path.join(directory,id+'.json');
    if(fs.existsSync(target+'.revoked'))throw new Error('A legacy connection has been revoked');
    writePrivateJson(target,{legacyConfigPath:path.resolve(configFile)});
  }
  const backup=stateFile+'.before-connections';
  if(!fs.existsSync(backup))writePrivateJson(backup,state);
  let migrated=0;for(const row of rows)if(!row.grantId){row.grantId=migration.grants[row.clientId];migrated++;}
  writePrivateJson(stateFile,state);
  return {migrated};
}
if(process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href===import.meta.url){
 if(!process.argv.includes('--gateway-stopped'))throw new Error('Stop the gateway before migrating, then pass --gateway-stopped');
 const state=process.env.BACKSTAGE_WORK_STATE,configFile=process.env.BACKSTAGE_WORK_CONFIG;
 if(!state||!configFile)throw new Error('BACKSTAGE_WORK_STATE and BACKSTAGE_WORK_CONFIG required');
 console.log(JSON.stringify(migrateLegacy({stateFile:path.join(state,'oauth.json'),configFile,directory:path.join(state,'connections')})));
}
