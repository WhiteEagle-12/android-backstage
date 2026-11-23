/**
 * Link keys, envelopes, and BSF1 file encryption for Android Backstage.
 * Must match SECURITY.md / ARCHITECTURE §7 and the Android side.
 */
import crypto from "node:crypto";
import fs from "node:fs";

export const FILE_CHUNK = 1024 * 1024;
export const BSF1_MAGIC = "BSF1";
export const FILE_AAD = "backstage file v1";

export function hkdfSha256(ikm, salt, info, length) {
  const saltBuf =
    !salt || (Buffer.isBuffer(salt) ? salt.length === 0 : String(salt).length === 0)
      ? Buffer.alloc(32)
      : Buffer.from(salt);
  const infoBuf = info == null ? Buffer.alloc(0) : Buffer.from(info);
  return Buffer.from(crypto.hkdfSync("sha256", Buffer.from(ikm), saltBuf, infoBuf, length));
}

export function hmacSha256(key, data) {
  return crypto.createHmac("sha256", Buffer.from(key)).update(data).digest();
}

export function linkKey(spakeKey) {
  return hkdfSha256(spakeKey, Buffer.alloc(0), "backstage link v1", 32);
}

export function callKey(link) {
  return hkdfSha256(link, Buffer.alloc(0), "backstage call v1", 32);
}

export function resultKey(link) {
  return hkdfSha256(link, Buffer.alloc(0), "backstage result v1", 32);
}

export function confirmA(link) {
  return hmacSha256(link, "confirm agent");
}

export function confirmB(link) {
  return hmacSha256(link, "confirm device");
}

export function b64url(buf) {
  return Buffer.from(buf).toString("base64url");
}

export function unb64url(s) {
  return Buffer.from(String(s), "base64url");
}

export function sha256hex(data) {
  return crypto.createHash("sha256").update(data).digest("hex");
}

export function nonceForCounter(n) {
  const buf = Buffer.alloc(12);
  let v = BigInt(n);
  if (v < 0n || v > 18446744073709551615n) throw new Error("counter outside uint64");
  for (let i = 11; i >= 4; i -= 1) {
    buf[i] = Number(v & 0xffn);
    v >>= 8n;
  }
  return buf;
}

export function nonce12Be(i) {
  const buf = Buffer.alloc(12);
  let v = BigInt(i);
  for (let k = 11; k >= 0; k -= 1) {
    buf[k] = Number(v & 0xffn);
    v >>= 8n;
  }
  return buf;
}

export function aes256GcmSeal(key, nonce, aad, plaintext) {
  const c = crypto.createCipheriv("aes-256-gcm", Buffer.from(key), nonce);
  if (aad != null) c.setAAD(Buffer.from(aad));
  const ct = Buffer.concat([c.update(Buffer.from(plaintext)), c.final()]);
  return Buffer.concat([ct, c.getAuthTag()]);
}

export function aes256GcmOpen(key, nonce, aad, blob) {
  if (!blob || blob.length < 16) throw new Error("ciphertext too short");
  const tag = blob.subarray(blob.length - 16);
  const ct = blob.subarray(0, blob.length - 16);
  const d = crypto.createDecipheriv("aes-256-gcm", Buffer.from(key), nonce);
  if (aad != null) d.setAAD(Buffer.from(aad));
  d.setAuthTag(tag);
  return Buffer.concat([d.update(ct), d.final()]);
}

export function sealEnvelope({ key, from, n, plaintext }) {
  const nStr = String(n);
  const nonce = nonceForCounter(nStr);
  const aad = `${from}:${nStr}`;
  const blob = aes256GcmSeal(key, nonce, aad, Buffer.from(plaintext));
  return { v: "1", from: String(from), n: nStr, ct: b64url(blob) };
}

export function sealEnvelopeV2({ key, from, n, plaintext }) {
  const r = crypto.randomBytes(12).toString("base64url");
  const derived = hkdfSha256(key, Buffer.alloc(0), "backstage envelope v2", 32);
  const aad = `2:${from}:${n}:${r}`;
  return {v:2, from:String(from), n:String(n), r,
    ct:b64url(aes256GcmSeal(derived, unb64url(r), aad, Buffer.from(plaintext)))};
}

export function openEnvelope({ key, envelope }) {
  if (envelope && Number(envelope.v) === 2) {
    if (typeof envelope.r !== "string" || !/^[A-Za-z0-9_-]{16}$/.test(envelope.r)) throw new Error("invalid random nonce");
    nonceForCounter(envelope.n);
    const derived=hkdfSha256(key,Buffer.alloc(0),"backstage envelope v2",32);
    return aes256GcmOpen(derived,unb64url(envelope.r),`2:${envelope.from}:${envelope.n}:${envelope.r}`,unb64url(envelope.ct));
  }
  if (!envelope || (envelope.v !== "1" && envelope.v !== 1)) throw new Error("unsupported envelope version");
  const from = String(envelope.from);
  const nStr = String(envelope.n);
  const nonce = nonceForCounter(nStr);
  const aad = `${from}:${nStr}`;
  const blob = unb64url(envelope.ct);
  return aes256GcmOpen(key, nonce, aad, blob);
}

export function nextCounter(current) {
  return (BigInt(current) + 1n).toString();
}

export function counterGt(a, b) {
  return BigInt(a) > BigInt(b);
}

export function encryptBytes(plaintext, fileKey, chunkSize = FILE_CHUNK) {
  const plain = Buffer.from(plaintext);
  const hash = crypto.createHash("sha256");
  hash.update(plain);
  const header = Buffer.alloc(16);
  header.write(BSF1_MAGIC, 0, "ascii");
  header.writeUInt32BE(chunkSize >>> 0, 4);
  header.writeBigUInt64BE(BigInt(plain.length), 8);
  const parts = [header];
  let offset = 0;
  let i = 0;
  while (offset < plain.length) {
    const end = Math.min(offset + chunkSize, plain.length);
    const chunk = plain.subarray(offset, end);
    const ct = aes256GcmSeal(fileKey, nonce12Be(i), FILE_AAD, chunk);
    const lenBuf = Buffer.alloc(4);
    lenBuf.writeUInt32BE(ct.length, 0);
    parts.push(lenBuf, ct);
    offset = end;
    i += 1;
  }
  return { blob: Buffer.concat(parts), sha256: hash.digest("hex"), plaintextLength: plain.length };
}

export function decryptBytes(blob, fileKey) {
  if (!blob || blob.length < 16) throw new Error("BSF1 blob too short");
  const buf = Buffer.from(blob);
  if (buf.subarray(0, 4).toString("ascii") !== BSF1_MAGIC) throw new Error("not a BSF1 blob");
  const chunkSize = buf.readUInt32BE(4);
  const plaintextLength = Number(buf.readBigUInt64BE(8));
  const hash = crypto.createHash("sha256");
  const chunks = [];
  let off = 16;
  let i = 0;
  let got = 0;
  while (got < plaintextLength) {
    if (off + 4 > buf.length) throw new Error("truncated BSF1 chunk length");
    const ctLen = buf.readUInt32BE(off);
    off += 4;
    if (off + ctLen > buf.length) throw new Error("truncated BSF1 chunk");
    const ct = buf.subarray(off, off + ctLen);
    off += ctLen;
    const plain = aes256GcmOpen(fileKey, nonce12Be(i), FILE_AAD, ct);
    hash.update(plain);
    chunks.push(plain);
    got += plain.length;
    i += 1;
  }
  void chunkSize;
  return { plaintext: Buffer.concat(chunks, plaintextLength), sha256: hash.digest("hex"), plaintextLength };
}

export function encryptFileTo(srcPath, destPath, fileKey, chunkSize = FILE_CHUNK) {
  const hash = crypto.createHash("sha256");
  const plaintextLength = fs.statSync(srcPath).size;
  const fdIn = fs.openSync(srcPath, "r");
  const fdOut = fs.openSync(destPath, "w");
  try {
    const header = Buffer.alloc(16);
    header.write(BSF1_MAGIC, 0, "ascii");
    header.writeUInt32BE(chunkSize >>> 0, 4);
    header.writeBigUInt64BE(BigInt(plaintextLength), 8);
    fs.writeSync(fdOut, header);
    const buf = Buffer.alloc(chunkSize);
    let offset = 0;
    let i = 0;
    while (offset < plaintextLength) {
      const n = fs.readSync(fdIn, buf, 0, Math.min(chunkSize, plaintextLength - offset), offset);
      const plain = buf.subarray(0, n);
      hash.update(plain);
      const ct = aes256GcmSeal(fileKey, nonce12Be(i), FILE_AAD, plain);
      const lenBuf = Buffer.alloc(4);
      lenBuf.writeUInt32BE(ct.length, 0);
      fs.writeSync(fdOut, lenBuf);
      fs.writeSync(fdOut, ct);
      offset += n;
      i += 1;
    }
  } finally {
    fs.closeSync(fdIn);
    fs.closeSync(fdOut);
  }
  return { sha256: hash.digest("hex"), plaintextLength, chunkSize };
}

export function decryptFileTo(srcPath, destPath, fileKey) {
  const blob = fs.readFileSync(srcPath);
  const { plaintext, sha256, plaintextLength } = decryptBytes(blob, fileKey);
  fs.writeFileSync(destPath, plaintext);
  return { sha256, plaintextLength };
}

export function timingSafeEqualBuf(a, b) {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  if (left.length !== right.length) return false;
  return crypto.timingSafeEqual(left, right);
}
