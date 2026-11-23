/**
 * BoringSSL SPAKE2 over Ed25519 — byte-for-byte with
 * app/.../adb/Spake2.java and Ed25519.java.
 */
import crypto from "node:crypto";

export const P = (1n << 255n) - 19n;
export const L = 7237005577332262213973186563042994240857116359379907606001950938285454250989n;
export const D = 37095705934669439343138083508754565189542113879843219016388785533085940283555n;
export const SQRT_M1 = modPow(2n, (P - 1n) >> 2n, P);

export const ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789";
export const ALICE_NAME = Buffer.from("backstage agent\0", "utf8");
export const BOB_NAME = Buffer.from("backstage device\0", "utf8");

export const M_ENCODED = Buffer.from(
  "5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e",
  "hex",
);
export const N_ENCODED = Buffer.from(
  "10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778",
  "hex",
);

function mod(a, m) {
  const r = a % m;
  return r < 0n ? r + m : r;
}

function modPow(base, exp, modulus) {
  let result = 1n;
  let b = mod(base, modulus);
  let e = exp;
  while (e > 0n) {
    if (e & 1n) result = (result * b) % modulus;
    b = (b * b) % modulus;
    e >>= 1n;
  }
  return result;
}

function modInverse(a, m) {
  let t = 0n;
  let newT = 1n;
  let r = m;
  let newR = mod(a, m);
  while (newR !== 0n) {
    const q = r / newR;
    [t, newT] = [newT, t - q * newT];
    [r, newR] = [newR, r - q * newR];
  }
  if (r > 1n) throw new Error("not invertible");
  if (t < 0n) t += m;
  return t;
}

export function fromLe(le) {
  let n = 0n;
  for (let i = le.length - 1; i >= 0; i -= 1) {
    n = (n << 8n) + BigInt(le[i]);
  }
  return n;
}

export function toLe32(v) {
  const out = Buffer.alloc(32);
  let t = v < 0n ? 0n : v;
  for (let i = 0; i < 32; i += 1) {
    out[i] = Number(t & 0xffn);
    t >>= 8n;
  }
  return out;
}

export class Point {
  constructor(x, y) {
    this.x = x;
    this.y = y;
  }
}

export const IDENTITY = new Point(0n, 1n);

export function add(p1, p2) {
  const x1x2 = mod(p1.x * p2.x, P);
  const y1y2 = mod(p1.y * p2.y, P);
  const x1y2 = mod(p1.x * p2.y, P);
  const y1x2 = mod(p1.y * p2.x, P);
  const dxxyy = mod(D * x1x2 * y1y2, P);
  const x3 = mod((x1y2 + y1x2) * modInverse(1n + dxxyy, P), P);
  const y3 = mod((y1y2 + x1x2) * modInverse(1n - dxxyy, P), P);
  return new Point(x3, y3);
}

export function neg(p) {
  return new Point(mod(P - p.x, P), p.y);
}

export function sub(p, q) {
  return add(p, neg(q));
}

export function scalarmult(scalar32, p) {
  const bytes = Buffer.isBuffer(scalar32) ? scalar32 : Buffer.from(scalar32);
  if (bytes.length !== 32) throw new Error("scalar must be 32 bytes");
  let r = IDENTITY;
  let q = p;
  for (let i = 0; i < 32; i += 1) {
    let b = bytes[i];
    for (let bit = 0; bit < 8; bit += 1) {
      if (b & 1) r = add(r, q);
      q = add(q, q);
      b >>= 1;
    }
  }
  return r;
}

export function encode(pt) {
  const out = toLe32(pt.y);
  if (pt.x & 1n) out[31] |= 0x80;
  return out;
}

export function decode(enc) {
  if (!enc || enc.length !== 32) throw new Error("point encoding must be 32 bytes");
  const copy = Buffer.from(enc);
  const xSign = (copy[31] & 0x80) >>> 7;
  copy[31] &= 0x7f;
  const y = fromLe(copy);
  if (y >= P) throw new Error("y out of range");
  const y2 = mod(y * y, P);
  const u = mod(y2 - 1n, P);
  const v = mod(D * y2 + 1n, P);
  const uv3 = mod(u * modPow(v, 3n, P), P);
  const uv7 = mod(u * modPow(v, 7n, P), P);
  let x = mod(uv3 * modPow(uv7, (P - 5n) >> 3n, P), P);
  let vx2 = mod(v * x * x, P);
  if (mod(vx2 - u, P) !== 0n) {
    x = mod(x * SQRT_M1, P);
    vx2 = mod(v * x * x, P);
    if (mod(vx2 - u, P) !== 0n) throw new Error("point not on curve");
  }
  if (x === 0n && xSign === 1) throw new Error("invalid encoding of x=0");
  if (Number(x & 1n) !== xSign) x = P - x;
  return new Point(x, y);
}

export function scReduce(sixtyFour) {
  if (!sixtyFour || sixtyFour.length < 32) throw new Error("sc_reduce input too short");
  const inn = sixtyFour.length === 64 ? sixtyFour : Buffer.concat([sixtyFour, Buffer.alloc(64 - sixtyFour.length)]);
  return toLe32(fromLe(inn) % L);
}

export function leftShift3(n32) {
  const n = Buffer.from(n32);
  let carry = 0;
  for (let i = 0; i < 32; i += 1) {
    const cur = n[i];
    const nextCarry = cur >>> 5;
    n[i] = ((cur << 3) | carry) & 0xff;
    carry = nextCarry;
  }
  return n;
}

export const B = decode(
  Buffer.from(
    "5866666666666666666666666666666666666666666666666666666666666666",
    "hex",
  ),
);
export const M = decode(M_ENCODED);
export const N = decode(N_ENCODED);

export function derivePasswordScalar(passwordHash64, hack = true) {
  const reduced = scReduce(passwordHash64);
  if (!hack) return reduced;
  let s = fromLe(reduced);
  let order = L;
  if (s & 1n) s += order;
  order <<= 1n;
  if (s & 2n) s += order;
  order <<= 1n;
  if (s & 4n) s += order;
  return toLe32(s);
}

function sha512(data) {
  return crypto.createHash("sha512").update(data).digest();
}

function updateLen(hash, data) {
  const lenLe = Buffer.alloc(8);
  let len = BigInt(data.length);
  for (let i = 0; i < 8; i += 1) {
    lenLe[i] = Number(len & 0xffn);
    len >>= 8n;
  }
  hash.update(lenLe);
  hash.update(data);
}

export class Spake2 {
  /**
   * @param {"alice"|"bob"} role
   * @param {Buffer} myName
   * @param {Buffer} theirName
   * @param {boolean} [passwordScalarHack]
   */
  constructor(role, myName, theirName, passwordScalarHack = true) {
    this.role = role;
    this.myName = Buffer.from(myName);
    this.theirName = Buffer.from(theirName);
    this.passwordScalarHack = passwordScalarHack;
    this.privateKey = null;
    this.passwordScalar = null;
    this.passwordHash = null;
    this.myMsg = null;
    this.msgGenerated = false;
  }

  generateMsg(password, privateRandom64) {
    if (this.msgGenerated) throw new Error("generateMsg already called");
    const rnd = privateRandom64 ? Buffer.from(privateRandom64) : crypto.randomBytes(64);
    if (rnd.length !== 64) throw new Error("privateRandom64 must be 64 bytes");
    const reduced = scReduce(rnd);
    this.privateKey = leftShift3(reduced);
    const p = scalarmult(this.privateKey, B);
    this.passwordHash = sha512(Buffer.from(password));
    this.passwordScalar = derivePasswordScalar(this.passwordHash, this.passwordScalarHack);
    const maskBase = this.role === "alice" ? M : N;
    const mask = scalarmult(this.passwordScalar, maskBase);
    const pStar = add(p, mask);
    this.myMsg = encode(pStar);
    this.msgGenerated = true;
    return Buffer.from(this.myMsg);
  }

  processMsg(theirMsg) {
    if (!this.msgGenerated) throw new Error("generateMsg not called");
    if (!theirMsg || theirMsg.length !== 32) throw new Error("SPAKE2 message must be 32 bytes");
    const qStar = decode(theirMsg);
    const peersMaskBase = this.role === "alice" ? N : M;
    const peersMask = scalarmult(this.passwordScalar, peersMaskBase);
    const q = sub(qStar, peersMask);
    const k = scalarmult(this.privateKey, q);
    const kEnc = encode(k);
    const hash = crypto.createHash("sha512");
    if (this.role === "alice") {
      updateLen(hash, this.myName);
      updateLen(hash, this.theirName);
      updateLen(hash, this.myMsg);
      updateLen(hash, theirMsg);
    } else {
      updateLen(hash, this.theirName);
      updateLen(hash, this.myName);
      updateLen(hash, theirMsg);
      updateLen(hash, this.myMsg);
    }
    updateLen(hash, kEnc);
    updateLen(hash, this.passwordHash);
    return hash.digest();
  }
}

export function randomCodeChars(n) {
  let out = "";
  for (let i = 0; i < n; i += 1) {
    out += ALPHABET[crypto.randomInt(ALPHABET.length)];
  }
  return out;
}
