import os from 'node:os';

/**
 * Finds a direct path from this computer to a phone's own Backstage listener, so
 * calls and file transfers skip Cloudflare and the relay when both are on the
 * tailnet or the same LAN. The relay stays the fallback for everything.
 *
 * The phone reports its addresses at each relay hello. A candidate counts only after
 * its /health answers as Backstage for the expected device. Envelopes are still
 * end-to-end encrypted and authenticated, so a wrong address can refuse a call but
 * never run it.
 */
const TAILNET = /^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}$/;

/** True for a route such as http://100.64.0.10:18422: the phone is on the tailnet and can reach this computer's tailnet address. */
export function overTailnet(url) {
  try { return TAILNET.test(new URL(url).hostname); } catch { return false; }
}

function ipv4ToInt(address) {
  const parts = address.split('.').map(Number);
  if (parts.length !== 4 || parts.some((p) => !Number.isInteger(p) || p < 0 || p > 255)) return null;
  return ((parts[0] << 24) >>> 0) + (parts[1] << 16) + (parts[2] << 8) + parts[3];
}

/** This computer's IPv4 networks, as [address, mask] integer pairs. */
function localNetworks(interfaces = os.networkInterfaces()) {
  const out = [];
  for (const rows of Object.values(interfaces)) for (const row of rows || []) {
    if (row.family !== 'IPv4' || row.internal) continue;
    const address = ipv4ToInt(row.address), mask = ipv4ToInt(row.netmask);
    if (address != null && mask != null) out.push([address, mask]);
  }
  return out;
}

/** Tailnet addresses first (stable across Wi-Fi changes), then addresses on a LAN this computer shares. */
export function directCandidates(addresses, interfaces) {
  const list = Array.isArray(addresses) ? addresses.filter((a) => typeof a === 'string' && ipv4ToInt(a) != null) : [];
  const blocked = (a) => a.startsWith('169.254.') || a.startsWith('127.') || a.startsWith('0.') || a.startsWith('192.0.0.');
  const nets = localNetworks(interfaces);
  const sameLan = (a) => { const value = ipv4ToInt(a); return nets.some(([address, mask]) => mask !== 0 && ((address & mask) >>> 0) === ((value & mask) >>> 0)); };
  return [...list.filter((a) => TAILNET.test(a)), ...list.filter((a) => !TAILNET.test(a) && !blocked(a) && sameLan(a))];
}

export function createDirectRoutes({ fetchImpl = fetch, now = Date.now, port = 18422, okMs = 60_000, failMs = 60_000, probeTimeoutMs = 450, interfaces, log = () => {} } = {}) {
  const cache = new Map();
  const inflight = new Map();

  async function probe(address, expectedDeviceId) {
    try {
      const response = await fetchImpl(`http://${address}:${port}/health`, { signal: AbortSignal.timeout(probeTimeoutMs) });
      if (!response.ok) return false;
      const body = await response.json();
      if (body?.ok !== true || body.service !== 'backstage') return false;
      return !body.deviceId || !expectedDeviceId || body.deviceId === expectedDeviceId;
    } catch {
      return false;
    }
  }

  return {
    /** A base URL such as http://100.64.0.10:18422, or null to use the relay. */
    async route(key, addresses, expectedDeviceId) {
      const hit = cache.get(key);
      if (hit && hit.ok && now() - hit.at < okMs) return hit.url;
      if (hit && !hit.ok && now() - hit.at < failMs) return null;
      const existing = inflight.get(key);
      if (existing) return existing;
      const task = (async () => {
        for (const address of directCandidates(addresses, interfaces?.())) {
          if (await probe(address, expectedDeviceId)) {
            const url = `http://${address}:${port}`;
            if (!hit?.ok || hit.url !== url) log(`direct route to ${key}: ${url}`);
            cache.set(key, { ok: true, url, at: now() });
            return url;
          }
        }
        if (hit?.ok !== false) log(`no direct route to ${key}; using the relay`);
        cache.set(key, { ok: false, at: now() });
        return null;
      })();
      inflight.set(key, task);
      try {
        return await task;
      } finally {
        if (inflight.get(key) === task) inflight.delete(key);
      }
    },
    /** A call over the direct route failed; use the relay until the next probe. */
    failed(key) {
      cache.set(key, { ok: false, at: now() });
    },
    status(key) {
      const hit = cache.get(key);
      return hit?.ok ? hit.url : null;
    },
  };
}
