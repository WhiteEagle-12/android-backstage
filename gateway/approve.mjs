import crypto from 'node:crypto';
import path from 'node:path';
import { writePrivateJson } from '../cli/state.mjs';
import { digest } from './auth.mjs';
const dir = process.env.BACKSTAGE_WORK_STATE;
if(!dir) throw new Error('BACKSTAGE_WORK_STATE required');
const code = crypto.randomBytes(18).toString('base64url');
writePrivateJson(path.join(dir,'approval.json'),{hash:digest(code),expires:Date.now()+600000});
console.log(code);
