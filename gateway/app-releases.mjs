import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

function validExternalUrl(value) {
  if (typeof value !== 'string' || !value.trim()) return null;
  try {
    const u = new URL(value.trim());
    return u.protocol === 'https:' && !u.username && !u.password ? u.toString() : null;
  } catch {
    return null;
  }
}

/**
 * Published phone app builds. scripts/publish-apk.sh writes latest.json:
 *   {versionCode, versionName, file, sha256, size, url?}
 *
 * When `url` is an HTTPS URL (e.g. GitHub Releases or Cloudflare R2), phones
 * download directly from that zero-egress origin and verify the SHA-256 locally.
 * Otherwise, phones fall back to `${base}/app/download/${file}`.
 */
export function createAppReleases({
  directory = process.env.BACKSTAGE_APP_RELEASES || path.join(os.homedir(), '.local/share/android-backstage/app-releases'),
  base = process.env.BACKSTAGE_PUBLIC_BASE || 'https://relay.android-backstage.com/work',
} = {}) {
  const safe = (name) => /^[A-Za-z0-9._-]{1,100}\.apk$/.test(name || '');
  return {
    latest() {
      try {
        const row = JSON.parse(fs.readFileSync(path.join(directory, 'latest.json'), 'utf8'));
        if (!Number.isInteger(row.versionCode) || !/^[a-f0-9]{64}$/.test(row.sha256 || '')) return null;
        const externalUrl = row.url != null ? validExternalUrl(row.url) : null;
        if (row.url != null && !externalUrl) return null;
        if (!externalUrl && !safe(row.file)) return null;
        return {
          versionCode: row.versionCode,
          versionName: String(row.versionName || ''),
          sha256: row.sha256,
          size: row.size || null,
          url: externalUrl || `${base}/app/download/${row.file}`,
        };
      } catch {
        return null;
      }
    },
    file(name) {
      if (!safe(name)) return null;
      const full = path.join(directory, name);
      return fs.existsSync(full) ? full : null;
    },
  };
}
