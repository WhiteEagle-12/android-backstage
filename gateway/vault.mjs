import crypto from 'node:crypto';
import fs from 'node:fs';

const AAD_PREFIX = 'backstage-vault-v1:';

/**
 * Parse a 32-byte master key from 64-char hex or base64/base64url.
 * Returns a Buffer of length 32 or throws on malformed input.
 */
export function parseVaultKey(raw) {
  if (Buffer.isBuffer(raw)) {
    if (raw.length !== 32) throw new Error('BACKSTAGE_VAULT_KEY must be 32 bytes');
    return Buffer.from(raw);
  }
  const s = String(raw || '').trim();
  if (!s) throw new Error('BACKSTAGE_VAULT_KEY is empty');
  let buf;
  if (/^[a-f0-9]{64}$/i.test(s)) {
    buf = Buffer.from(s, 'hex');
  } else {
    buf = Buffer.from(s, s.includes('-') || s.includes('_') ? 'base64url' : 'base64');
  }
  if (buf.length !== 32) {
    throw new Error('BACKSTAGE_VAULT_KEY must decode to 32 bytes (64 hex chars or 43-char base64url)');
  }
  return buf;
}

/**
 * Load vault from BACKSTAGE_VAULT_KEY or BACKSTAGE_VAULT_KEY_FILE.
 * Returns null if neither env var is set (plain local/dev mode).
 */
export function loadVaultFromEnv(env = process.env) {
  let raw = env.BACKSTAGE_VAULT_KEY || '';
  if (!raw && env.BACKSTAGE_VAULT_KEY_FILE) {
    raw = fs.readFileSync(env.BACKSTAGE_VAULT_KEY_FILE, 'utf8').trim();
  }
  if (!raw) return null;
  return createVault({ key: parseVaultKey(raw) });
}

/**
 * AES-256-GCM envelope vault for sealing device secrets (linkKey + apiKey) at rest.
 * Only routing metadata (agentId, relayUrl, deviceId, counters) stays readable in JSON;
 * every secret is authenticated with AAD bound to the deviceId.
 */
export function createVault({ key }) {
  const masterKey = parseVaultKey(key);

  function seal(obj, context) {
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', masterKey, iv);
    cipher.setAAD(Buffer.from(AAD_PREFIX + String(context || ''), 'utf8'));
    const plaintext = Buffer.from(JSON.stringify(obj), 'utf8');
    try {
      const ct = Buffer.concat([cipher.update(plaintext), cipher.final()]);
      const tag = cipher.getAuthTag();
      return {
        v: 1,
        alg: 'aes-256-gcm',
        iv: iv.toString('base64url'),
        tag: tag.toString('base64url'),
        ct: ct.toString('base64url'),
      };
    } finally {
      plaintext.fill(0);
    }
  }

  function open(envelope, context) {
    if (!envelope || envelope.v !== 1 || envelope.alg !== 'aes-256-gcm') {
      throw new Error('Unsupported vault envelope');
    }
    const iv = Buffer.from(String(envelope.iv || ''), 'base64url');
    const tag = Buffer.from(String(envelope.tag || ''), 'base64url');
    const ct = Buffer.from(String(envelope.ct || ''), 'base64url');
    if (iv.length !== 12 || tag.length !== 16) throw new Error('Invalid vault envelope parameters');
    const decipher = crypto.createDecipheriv('aes-256-gcm', masterKey, iv);
    decipher.setAAD(Buffer.from(AAD_PREFIX + String(context || ''), 'utf8'));
    decipher.setAuthTag(tag);
    const plaintext = Buffer.concat([decipher.update(ct), decipher.final()]);
    try {
      return JSON.parse(plaintext.toString('utf8'));
    } finally {
      plaintext.fill(0);
    }
  }

  /** Strip apiKey & linkKeys from a phone config object and seal them inside sealedSecrets. */
  function sealPhoneConfig(config, deviceId) {
    if (!config || typeof config !== 'object') return config;
    const out = { ...config };
    const secrets = {};
    if (typeof out.apiKey === 'string' && out.apiKey) {
      secrets.apiKey = out.apiKey;
      delete out.apiKey;
    }
    const linkKeys = {};
    if (out.links && typeof out.links === 'object') {
      const cleanLinks = {};
      for (const [name, link] of Object.entries(out.links)) {
        if (link && typeof link === 'object') {
          const copy = { ...link };
          if (typeof copy.linkKey === 'string' && copy.linkKey) {
            linkKeys[name] = copy.linkKey;
            delete copy.linkKey;
          }
          cleanLinks[name] = copy;
        } else {
          cleanLinks[name] = link;
        }
      }
      out.links = cleanLinks;
    }
    secrets.linkKeys = linkKeys;
    out.sealedSecrets = seal(secrets, deviceId);
    return out;
  }

  /** Restore apiKey & linkKeys into a fresh in-memory config copy. Reports `migrated` if input was plaintext. */
  function openPhoneConfig(stored, deviceId) {
    if (!stored || typeof stored !== 'object') return { config: stored, migrated: false };
    if (!stored.sealedSecrets) {
      const hasPlainSecret = Boolean(stored.apiKey) || Object.values(stored.links || {}).some(l => l && l.linkKey);
      return { config: stored, migrated: hasPlainSecret };
    }
    const secrets = open(stored.sealedSecrets, deviceId);
    const out = { ...stored };
    delete out.sealedSecrets;
    if (typeof secrets.apiKey === 'string') out.apiKey = secrets.apiKey;
    if (out.links && typeof out.links === 'object') {
      const restoredLinks = {};
      for (const [name, link] of Object.entries(out.links)) {
        if (link && typeof link === 'object') {
          const copy = { ...link };
          if (secrets.linkKeys && typeof secrets.linkKeys[name] === 'string') {
            copy.linkKey = secrets.linkKeys[name];
          }
          restoredLinks[name] = copy;
        } else {
          restoredLinks[name] = link;
        }
      }
      out.links = restoredLinks;
    }
    return { config: out, migrated: false };
  }

  return { seal, open, sealPhoneConfig, openPhoneConfig };
}
