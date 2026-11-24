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

/** Non-blocking atomic JSON write (temp file + rename, 0600) for coalesced background persistence. */
export async function writePrivateJsonAsync(file, data) {
  const dir = path.dirname(file);
  await fs.promises.mkdir(dir, { recursive: true, mode: 0o700 });
  const tmp = `${file}.${process.pid}.${Math.random().toString(16).slice(2)}.tmp`;
  const handle = await fs.promises.open(tmp, 'wx', 0o600);
  try {
    await handle.writeFile(JSON.stringify(data, null, 2) + '\n', 'utf8');
  } finally {
    await handle.close();
  }
  await fs.promises.rename(tmp, file);
}

// In-process per-file FIFO queue avoids 50 ms filesystem polling when multiple
// concurrent callers in the same gateway share one device link file.
const inProcessLocks = new Map();

// Serialize the entire exchange: allocating a counter alone does not prevent
// two senders from delivering their monotonically numbered calls out of order.
export async function withConfigLock(file, fn, { diskLock = true } = {}) {
  const key = path.resolve(file);
  const previous = inProcessLocks.get(key) || Promise.resolve();
  let releaseLocal;
  const current = new Promise(r => { releaseLocal = r; });
  const tail = previous.then(() => current);
  inProcessLocks.set(key, tail);
  await previous;
  try {
    return diskLock ? await withDiskConfigLock(file, fn) : await fn();
  } finally {
    releaseLocal();
    if (inProcessLocks.get(key) === tail) inProcessLocks.delete(key);
  }
}

async function withDiskConfigLock(file, fn) {
  fs.mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  const lock = `${file}.lock`;
  const until = Date.now() + 660_000;
  for (;;) {
    try {
      fs.mkdirSync(lock, { mode: 0o700 });
      fs.writeFileSync(path.join(lock, 'owner'), String(process.pid), { mode: 0o600 });
      break;
    } catch (err) {
      if (err.code !== 'EEXIST') throw err;
      try {
        const pid = Number(fs.readFileSync(path.join(lock, 'owner'), 'utf8'));
        if (Number.isSafeInteger(pid) && pid > 0) {
          try { process.kill(pid, 0); }
          catch (e) {
            if (e.code === 'ESRCH') throw Object.assign(new Error(`Config lock owner ${pid} has exited; remove ${lock} after checking no other client is using this config`), {code:'STALE_LOCK'});
          }
        }
      } catch (e) {
        if(e.code === 'STALE_LOCK') throw e;
        // Never steal an initializing or indeterminate lock.
      }
      if (Date.now() >= until) throw new Error('Another client owns this config; check its process before removing the .lock directory');
      await new Promise(r => setTimeout(r, 15));
    }
  }
  try { return await fn(); }
  finally { fs.rmSync(lock, { recursive: true, force: true }); }
}
