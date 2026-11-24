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

export async function writePrivateJsonAsync(file, data, { shouldCommit = null } = {}) {
  const dir = path.dirname(file);
  await fs.promises.mkdir(dir, { recursive: true, mode: 0o700 });
  const tmp = `${file}.${process.pid}.${Math.random().toString(16).slice(2)}.tmp`;
  const payload = JSON.stringify(data, null, 2) + '\n';
  const fh = await fs.promises.open(tmp, 'wx', 0o600);
  try {
    await fh.writeFile(payload, 'utf8');
    await fh.datasync();
  } finally {
    await fh.close();
  }
  if (shouldCommit && !shouldCommit()) {
    await fs.promises.rm(tmp, { force: true }).catch(() => {});
    return false;
  }
  await fs.promises.rename(tmp, file);
  return true;
}
