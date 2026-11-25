import crypto from 'node:crypto';

const B32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

export function base32Encode(buf) {
  let bits = 0, value = 0, out = '';
  for (const byte of buf) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += B32_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += B32_ALPHABET[(value << (5 - bits)) & 31];
  return out;
}

export function base32Decode(str) {
  const clean = String(str || '').toUpperCase().replace(/[^A-Z2-7]/g, '');
  let bits = 0, value = 0;
  const out = [];
  for (const ch of clean) {
    const idx = B32_ALPHABET.indexOf(ch);
    if (idx < 0) continue;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(out);
}

/** Generate a 160-bit RFC 4226/6238 secret encoded as Base32. */
export function generateTotpSecret() {
  return base32Encode(crypto.randomBytes(20));
}

export function totpOtpAuthUri({ secret, accountName, issuer = 'Android Backstage' }) {
  const label = `${issuer}:${accountName || 'Account'}`;
  const params = new URLSearchParams({
    secret,
    issuer,
    algorithm: 'SHA1',
    digits: '6',
    period: '30',
  });
  return `otpauth://totp/${encodeURIComponent(label)}?${params.toString()}`;
}

export function totpAtCounter(secret, counter) {
  const key = base32Decode(secret);
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));
  const digest = crypto.createHmac('sha1', key).update(buf).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const bin = ((digest[offset] & 0x7f) << 24)
    | ((digest[offset + 1] & 0xff) << 16)
    | ((digest[offset + 2] & 0xff) << 8)
    | (digest[offset + 3] & 0xff);
  return String(bin % 1_000_000).padStart(6, '0');
}

export function totpNow(secret, timeMs = Date.now(), stepSec = 30) {
  return totpAtCounter(secret, Math.floor(timeMs / 1000 / stepSec));
}

/**
 * Verify a 6-digit TOTP code within ±window steps while blocking replays
 * at or before lastUsedStep.
 */
export function verifyTotp(secret, rawCode, { now = Date.now(), stepSec = 30, window = 1, lastUsedStep = -1 } = {}) {
  const code = String(rawCode || '').replace(/\D/g, '');
  if (!/^\d{6}$/.test(code) || !secret) return { ok: false };
  const currentStep = Math.floor(now / 1000 / stepSec);
  const target = Buffer.from(code, 'utf8');
  for (let delta = -window; delta <= window; delta++) {
    const step = currentStep + delta;
    if (step <= lastUsedStep) continue;
    const expected = Buffer.from(totpAtCounter(secret, step), 'utf8');
    if (expected.length === target.length && crypto.timingSafeEqual(expected, target)) {
      return { ok: true, step };
    }
  }
  return { ok: false };
}
