#!/usr/bin/env node
/**
 * Encrypted state backup & restore for Android Backstage VMs (zero dependencies).
 *
 * Uses AES-256-GCM with a 32-byte key file (hex or base64/base64url) to encrypt
 * a tar.gz snapshot of state files, then streams the ciphertext to Cloudflare R2
 * via SigV4 (reusing relay/r2.mjs).
 *
 * Usage:
 *   node deploy/gcp/backup-state-to-r2.mjs backup --node vm1|vm2 --src <path> [--src <path> ...]
 *   node deploy/gcp/backup-state-to-r2.mjs decrypt --in <file.enc> --out <file.tar.gz>
 *
 * Required env (for backup):
 *   BACKUP_R2_ENDPOINT, BACKUP_R2_BUCKET, BACKUP_R2_ACCESS_KEY_ID, BACKUP_R2_SECRET_ACCESS_KEY
 *   BACKUP_KEY_FILE (path to 32-byte key, mode 0600)
 */
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { createR2Store } from "../../relay/r2.mjs";

const MAGIC = Buffer.from("BSBK1", "ascii"); // 5 bytes magic + 12 bytes IV + 16 bytes tag + ciphertext

function loadKey(keyFile) {
  if (!keyFile || !fs.existsSync(keyFile)) throw new Error(`Missing backup key file: ${keyFile || "(unset)"}`);
  const raw = fs.readFileSync(keyFile, "utf8").trim();
  if (!raw || raw.length < 32) throw new Error("Backup key file must contain at least 32 characters");
  if (/^[a-f0-9]{64}$/i.test(raw)) return Buffer.from(raw, "hex");
  const b64 = Buffer.from(raw, raw.includes("-") || raw.includes("_") ? "base64url" : "base64");
  if (b64.length === 32) return b64;
  // High-entropy passphrase / longer key material (e.g. 48-byte base64): derive 32-byte AES-256 key deterministically.
  return crypto.createHash("sha256").update(raw, "utf8").digest();
}

function encryptBuffer(plaintext, key, aad) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", key, iv);
  cipher.setAAD(Buffer.from(aad, "utf8"));
  const ct = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([MAGIC, iv, tag, ct]);
}

function decryptBuffer(blob, key, aad) {
  if (blob.length < MAGIC.length + 12 + 16 || !blob.subarray(0, MAGIC.length).equals(MAGIC)) {
    throw new Error("Invalid backup header");
  }
  const iv = blob.subarray(MAGIC.length, MAGIC.length + 12);
  const tag = blob.subarray(MAGIC.length + 12, MAGIC.length + 28);
  const ct = blob.subarray(MAGIC.length + 28);
  const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAAD(Buffer.from(aad, "utf8"));
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(ct), decipher.final()]);
}

function parseArgs(argv) {
  const cmd = argv[0];
  const opts = { srcs: [] };
  for (let i = 1; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--node") opts.node = argv[++i];
    else if (a === "--src") opts.srcs.push(argv[++i]);
    else if (a === "--key") opts.key = argv[++i];
    else if (a === "--in") opts.inFile = argv[++i];
    else if (a === "--out") opts.outFile = argv[++i];
    else if (a === "--aad") opts.aad = argv[++i];
  }
  return { cmd, opts };
}

const { cmd, opts } = parseArgs(process.argv.slice(2));
const keyFile = opts.key || process.env.BACKUP_KEY_FILE || "/etc/android-backstage/backup.key";

if (cmd === "backup") {
  const nodeName = (opts.node || os.hostname()).replace(/[^a-zA-Z0-9_-]/g, "");
  const existing = opts.srcs.filter((p) => fs.existsSync(p));
  if (!existing.length) throw new Error("No existing --src paths to back up");

  const endpoint = (process.env.BACKUP_R2_ENDPOINT || "").trim();
  const bucket = (process.env.BACKUP_R2_BUCKET || "").trim();
  const accessKeyId = (process.env.BACKUP_R2_ACCESS_KEY_ID || "").trim();
  const secretAccessKey = (process.env.BACKUP_R2_SECRET_ACCESS_KEY || "").trim();
  if (!endpoint || !bucket || !accessKeyId || !secretAccessKey) {
    throw new Error("BACKUP_R2_ENDPOINT, BACKUP_R2_BUCKET, BACKUP_R2_ACCESS_KEY_ID, and BACKUP_R2_SECRET_ACCESS_KEY are required");
  }

  const key = loadKey(keyFile);
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const objectKey = `${nodeName}/${stamp}.tar.gz.enc`;
  const tmpTar = path.join(os.tmpdir(), `bs-backup-${process.pid}-${Date.now()}.tar.gz`);

  try {
    execFileSync("tar", ["-czf", tmpTar, "--ignore-failed-read", ...existing], { stdio: "pipe" });
    const plain = fs.readFileSync(tmpTar);
    const enc = encryptBuffer(plain, key, `backstage-backup-v1:${nodeName}`);
    const r2 = createR2Store({ endpoint, bucket, accessKeyId, secretAccessKey, region: "auto", prefix: "" });
    try {
      await r2.putBuffer(objectKey, enc, "application/octet-stream");
      await r2.putBuffer(`${nodeName}/latest.json`, Buffer.from(JSON.stringify({
        node: nodeName,
        key: objectKey,
        bytes: enc.length,
        sha256: crypto.createHash("sha256").update(enc).digest("hex"),
        createdAt: new Date().toISOString(),
        sources: existing,
      }, null, 2) + "\n"), "application/json");
    } finally {
      r2.close();
    }
    console.log(JSON.stringify({ ok: true, node: nodeName, bucket, key: objectKey, bytes: enc.length }));
  } finally {
    fs.rmSync(tmpTar, { force: true });
  }
} else if (cmd === "decrypt") {
  if (!opts.inFile || !opts.outFile || !opts.node) {
    throw new Error("Usage: decrypt --node vm1|vm2 --in <file.enc> --out <file.tar.gz>");
  }
  const key = loadKey(keyFile);
  const plain = decryptBuffer(fs.readFileSync(opts.inFile), key, opts.aad || `backstage-backup-v1:${opts.node}`);
  fs.writeFileSync(opts.outFile, plain, { mode: 0o600 });
  console.log(JSON.stringify({ ok: true, out: opts.outFile, bytes: plain.length }));
} else {
  console.error("Usage: backup-state-to-r2.mjs <backup|decrypt> ...");
  process.exit(2);
}
