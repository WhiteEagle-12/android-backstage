/**
 * File publishing: uploads land under RELAY_DATA_DIR/files/, local paths
 * the relay can read are copied there, downloads are single-use + TTL.
 */
import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import {Transform} from "node:stream";
import {pipeline} from "node:stream/promises";
import {writePrivateJson} from "./persist.mjs";

const DEFAULT_TTL_MS = 15 * 60 * 1000;
const ZERO_CHUNK = Buffer.alloc(65536);

function shredSync(p, shred) {
  if (shred) {
    try {
      const st = fs.statSync(p);
      if (st.isFile() && st.size > 0) {
        const fd = fs.openSync(p, "r+");
        try {
          let rem = st.size, pos = 0;
          while (rem > 0) {
            const n = Math.min(rem, ZERO_CHUNK.length);
            fs.writeSync(fd, ZERO_CHUNK, 0, n, pos);
            pos += n; rem -= n;
          }
        } finally { fs.closeSync(fd); }
      }
    } catch { /* best-effort */ }
  }
  try { fs.unlinkSync(p); } catch { /* already gone */ }
}

export function isLocalFile(p) {
  try {
    return typeof p === "string" && path.isAbsolute(p) && fs.statSync(p).isFile();
  } catch {
    return false;
  }
}

function sanitizeName(name) {
  const base = path.basename(String(name || "file.bin")) || "file.bin";
  return base.replace(/[^A-Za-z0-9._-]/g, "_");
}

function contentTypeFor(name) {
  if (String(name).toLowerCase().endsWith(".apk")) return "application/vnd.android.package-archive";
  if (String(name).toLowerCase().endsWith(".txt")) return "text/plain; charset=utf-8";
  if (String(name).toLowerCase().endsWith(".json")) return "application/json";
  return "application/octet-stream";
}

export function createFiles({
  dataDir,
  filesDir: customFilesDir = null,
  maxBytes,
  quotaBytes = 2 * 1024 * 1024 * 1024,
  maxAccountFiles = 32,
  maxAccountBytes = 512 * 1024 * 1024,
  ttlMs = DEFAULT_TTL_MS,
  shred = false,
}) {
  const filesDir = customFilesDir || path.join(dataDir, "files");
  fs.mkdirSync(filesDir, { recursive: true, mode: 0o700 });
  let reservedBytes = 0, activeUploads = 0;
  const reservedByOwner = new Map();
  const activeByOwner = new Map();
  /** @type {Map<string, { id: string, name: string, bytes: number, owner: string|null, expiresAt: number, singleUse: boolean, path: string }>} */
  const registry = new Map();

  function metaPath(id) {
    return path.join(filesDir, `${id}.json`);
  }

  function dataPath(id) {
    return path.join(filesDir, id);
  }

  function loadExisting() {
    let names = [];
    try {
      names = fs.readdirSync(filesDir);
    } catch {
      return;
    }
    for (const name of names) {
      if (!name.endsWith(".json")) continue;
      try {
        const meta = JSON.parse(fs.readFileSync(path.join(filesDir, name), "utf8"));
        if (/^[a-f0-9]{64}$/.test(meta?.id) && Number.isFinite(meta.bytes) && meta.bytes>=0) registry.set(meta.id, {...meta,path:dataPath(meta.id)});
      } catch {
        /* skip */
      }
    }
  }

  function persistMeta(entry) {
    writePrivateJson(metaPath(entry.id), entry);
  }

  function removeEntry(id) {
    registry.delete(id);
    for (const p of [dataPath(id), metaPath(id)]) {
      shredSync(p, shred);
    }
  }

  function sweep() {
    const now = Date.now();
    for (const [id, entry] of [...registry.entries()]) {
      if (entry.expiresAt <= now) removeEntry(id);
    }
  }

  loadExisting();
  sweep();
  // Incomplete streams and data without metadata cannot be downloaded after restart.
  for(const name of fs.readdirSync(filesDir)) {
    if(name.endsWith('.part') || (/^[a-f0-9]{64}$/.test(name) && !registry.has(name))) fs.rmSync(path.join(filesDir,name),{force:true});
  }
  function capacity(bytes, owner = null) {
    const all = [...registry.values()];
    const used = all.reduce((n, f) => n + f.bytes, 0);
    if (registry.size + activeUploads >= 256 || used + reservedBytes + bytes > quotaBytes) {
      throw Object.assign(new Error("file storage quota exceeded"), { status: 429 });
    }
    if (owner) {
      const own = all.filter((f) => f.owner === owner);
      const ownUsed = own.reduce((n, f) => n + f.bytes, 0);
      const ownActive = activeByOwner.get(owner) || 0;
      const ownReserved = reservedByOwner.get(owner) || 0;
      if (own.length + ownActive >= maxAccountFiles || ownUsed + ownReserved + bytes > maxAccountBytes) {
        throw Object.assign(new Error("account file storage quota exceeded"), { status: 429 });
      }
    }
  }

  function newId() {
    return crypto.randomBytes(32).toString("hex");
  }

  function fileUrl(publicBase, id) {
    return `${String(publicBase || "").replace(/\/$/, "")}/v1/files/${id}`;
  }

  function register({ id, name, bytes, owner = null, ttl, singleUse, dest }) {
    const entry = {
      id,
      name,
      bytes,
      owner,
      expiresAt: Date.now() + (ttl ?? ttlMs),
      singleUse: singleUse !== false,
      path: dest,
    };
    registry.set(id, entry);
    persistMeta(entry);
    return entry;
  }

  async function saveUpload(req, { fileName, owner = null, ttlMs: ttl, singleUse }) {
    sweep();
    const declared = Number(req.headers?.["content-length"]);
    const reserve = Number.isFinite(declared) && declared >= 0 && declared <= maxBytes ? declared : maxBytes;
    capacity(reserve, owner);
    reservedBytes += reserve;
    activeUploads++;
    if (owner) {
      reservedByOwner.set(owner, (reservedByOwner.get(owner) || 0) + reserve);
      activeByOwner.set(owner, (activeByOwner.get(owner) || 0) + 1);
    }
    const id=newId(), name=sanitizeName(fileName), dest=dataPath(id), tmp=`${dest}.part`;
    let received=0;
    const limit=new Transform({transform(chunk,encoding,callback){
      received+=chunk.length;
      callback(received>maxBytes ? Object.assign(new Error('file too large'),{status:413}) : null,chunk);
    }});
    try {
      await pipeline(req,limit,fs.createWriteStream(tmp,{flags:'wx',mode:0o600}));
      const fd=fs.openSync(tmp,'r'); try{fs.fsyncSync(fd);}finally{fs.closeSync(fd);}
      fs.renameSync(tmp,dest);
      return register({id,name,bytes:received,owner,ttl,singleUse,dest});
    } catch(err) {
      fs.rmSync(tmp,{force:true}); fs.rmSync(dest,{force:true}); registry.delete(id);
      throw err;
    } finally {
      reservedBytes -= reserve;
      activeUploads--;
      if (owner) {
        const nextBytes = (reservedByOwner.get(owner) || 0) - reserve;
        if (nextBytes > 0) reservedByOwner.set(owner, nextBytes); else reservedByOwner.delete(owner);
        const nextActive = (activeByOwner.get(owner) || 0) - 1;
        if (nextActive > 0) activeByOwner.set(owner, nextActive); else activeByOwner.delete(owner);
      }
    }
  }

  function publishLocal(localPath, { owner = null, ttl, singleUse } = {}) {
    if (!isLocalFile(localPath)) throw new Error(`not a readable local file: ${localPath}`);
    sweep();
    const bytes = fs.statSync(localPath).size;
    if(bytes>maxBytes)throw Object.assign(new Error("file too large"),{status:413});
    capacity(bytes, owner);
    const id = newId();
    const name = sanitizeName(localPath);
    const dest = dataPath(id);
    fs.copyFileSync(localPath, dest);
    fs.chmodSync(dest,0o600);
    const fd=fs.openSync(dest,"r");try{fs.fsyncSync(fd);}finally{fs.closeSync(fd);}
    return register({ id, name, bytes, owner, ttl, singleUse: singleUse !== false, dest });
  }

  function serveDownload(req, res, id) {
    sweep();
    const entry = registry.get(id);
    if (!entry || !["GET", "HEAD"].includes(req.method)) return false;
    let stat;
    try {
      stat = fs.statSync(entry.path);
    } catch {
      removeEntry(id);
      return false;
    }
    if (entry.singleUse && req.method === "HEAD") {
      res.writeHead(200, { "content-length": stat.size, "cache-control": "no-store" });
      res.end();
      return true;
    }
    if (entry.singleUse) {
      registry.delete(id);
      try {
        fs.unlinkSync(metaPath(id));
      } catch {
        /* ignore */
      }
    }
    const filename = entry.name.replace(/"/g, "");
    const headers = {
      "content-type": contentTypeFor(entry.name),
      "content-disposition": `attachment; filename="${filename}"`,
      "cache-control": "no-store",
      "access-control-allow-origin": "*",
    };
    // Reusable files resume after a dropped connection (curl -C -). Single-use files are sent whole.
    let start = 0, end = stat.size - 1, status = 200;
    const range = entry.singleUse ? null : /^bytes=(\d+)-(\d*)$/.exec(String(req.headers.range || ""));
    if (!entry.singleUse) headers["accept-ranges"] = "bytes";
    if (range) {
      start = Number(range[1]);
      if (range[2]) end = Math.min(end, Number(range[2]));
      if (start > end || start >= stat.size) {
        res.writeHead(416, { ...headers, "content-range": `bytes */${stat.size}` });
        res.end();
        return true;
      }
      status = 206;
      headers["content-range"] = `bytes ${start}-${end}/${stat.size}`;
    }
    headers["content-length"] = stat.size === 0 ? 0 : end - start + 1;
    res.writeHead(status, headers);
    if (req.method === "HEAD" || stat.size === 0) {
      res.end();
      return true;
    }
    const stream = fs.createReadStream(entry.path, { start, end });
    stream.on("close", () => {
      if (entry.singleUse) {
        shredSync(entry.path, shred);
      }
    });
    stream.on("error", () => {
      if (!res.headersSent) {
        res.writeHead(500, { "content-type": "application/json" });
      }
      res.end();
    });
    stream.pipe(res);
    return true;
  }

  function publishedCount() {
    sweep();
    return registry.size;
  }

  let sweepTimer = null;
  function startSweeper() {
    if (sweepTimer) return;
    sweepTimer = setInterval(() => sweep(), 60_000);
    sweepTimer.unref?.();
  }

  function stopSweeper() {
    if (sweepTimer) {
      clearInterval(sweepTimer);
      sweepTimer = null;
    }
  }

  return {
    saveUpload,
    publishLocal,
    serveDownload,
    removeEntry,
    fileUrl,
    sweep,
    publishedCount,
    startSweeper,
    stopSweeper,
  };
}
