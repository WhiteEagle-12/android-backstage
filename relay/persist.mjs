import fs from 'node:fs';
import path from 'node:path';

export function writePrivateJson(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  const tmp = `${file}.${process.pid}.${Math.random().toString(16).slice(2)}.tmp`;
  const fd = fs.openSync(tmp, 'wx', 0o600);
  try { fs.writeFileSync(fd, JSON.stringify(data, null, 2) + '\n'); fs.fsyncSync(fd); }
  finally { fs.closeSync(fd); }
  fs.renameSync(tmp, file);
  const parent = fs.openSync(path.dirname(file), 'r');
  try { fs.fsyncSync(parent); } finally { fs.closeSync(parent); }
}
