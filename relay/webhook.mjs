/**
 * Zero-dependency DNS-pinned SSRF guard for outbound relay webhooks.
 *
 * Blocks all non-HTTPS schemes, embedded credentials, non-public IPv4/IPv6
 * literals and DNS answers (loopback, RFC1918, link-local / cloud metadata,
 * CGNAT / Tailscale 100.64.0.0/10, multicast, ULA, IPv4-mapped IPv6), and
 * pins the socket to the validated address with redirects disabled.
 */
import dns from "node:dns/promises";
import https from "node:https";
import net from "node:net";

function parseIpv4(ip) {
  const parts = String(ip).split(".");
  if (parts.length !== 4) return null;
  const nums = parts.map((p) => (/^\d{1,3}$/.test(p) ? Number(p) : -1));
  if (nums.some((n) => n < 0 || n > 255)) return null;
  return nums;
}

export function isPublicIpv4(ip) {
  const p = parseIpv4(ip);
  if (!p) return false;
  const [a, b, c] = p;
  if (a === 0) return false; // 0.0.0.0/8 current network
  if (a === 10) return false; // 10.0.0.0/8 RFC1918
  if (a === 100 && b >= 64 && b <= 127) return false; // 100.64.0.0/10 CGNAT / Tailscale
  if (a === 127) return false; // 127.0.0.0/8 loopback
  if (a === 169 && b === 254) return false; // 169.254.0.0/16 link-local & cloud metadata
  if (a === 172 && b >= 16 && b <= 31) return false; // 172.16.0.0/12 RFC1918
  if (a === 192 && b === 0 && c === 0) return false; // 192.0.0.0/24 IETF protocol
  if (a === 192 && b === 0 && c === 2) return false; // 192.0.2.0/24 TEST-NET-1
  if (a === 192 && b === 88 && c === 99) return false; // 192.88.99.0/24 6to4 relay
  if (a === 192 && b === 168) return false; // 192.168.0.0/16 RFC1918
  if (a === 198 && (b === 18 || b === 19)) return false; // 198.18.0.0/15 benchmarking
  if (a === 198 && b === 51 && c === 100) return false; // 198.51.100.0/24 TEST-NET-2
  if (a === 203 && b === 0 && c === 113) return false; // 203.0.113.0/24 TEST-NET-3
  if (a >= 224) return false; // 224.0.0.0/4 multicast & 240.0.0.0/4 reserved
  return true;
}

function expandIpv6(raw) {
  const addr = String(raw || "").toLowerCase().replace(/^\[|\]$/g, "").split("%")[0];
  if (!net.isIPv6(addr)) return null;
  let normalized = addr;
  // Convert trailing embedded IPv4 (e.g. ::ffff:127.0.0.1) to two hex groups.
  const v4Match = /:(\d+\.\d+\.\d+\.\d+)$/.exec(normalized);
  if (v4Match) {
    const p = parseIpv4(v4Match[1]);
    if (!p) return null;
    const hi = ((p[0] << 8) | p[1]).toString(16);
    const lo = ((p[2] << 8) | p[3]).toString(16);
    normalized = normalized.slice(0, v4Match.index + 1) + `${hi}:${lo}`;
  }
  const halves = normalized.split("::");
  if (halves.length > 2) return null;
  const left = halves[0] ? halves[0].split(":").filter(Boolean) : [];
  const right = halves.length === 2 && halves[1] ? halves[1].split(":").filter(Boolean) : [];
  const missing = 8 - (left.length + right.length);
  if (missing < 0 || (halves.length === 1 && missing !== 0)) return null;
  const groups = [...left, ...Array(missing).fill("0"), ...right].map((g) => parseInt(g, 16));
  if (groups.length !== 8 || groups.some((n) => !Number.isInteger(n) || n < 0 || n > 0xffff)) return null;
  return groups;
}

export function isPublicIp(raw) {
  const clean = String(raw || "").trim().replace(/^\[|\]$/g, "");
  if (net.isIPv4(clean)) return isPublicIpv4(clean);
  const g = expandIpv6(clean);
  if (!g) return false;
  // :: (unspecified) and ::1 (loopback)
  const first7Zero = g.slice(0, 7).every((x) => x === 0);
  if (first7Zero && (g[7] === 0 || g[7] === 1)) return false;
  // IPv4-mapped ::ffff:0:0/96 or IPv4-translated / NAT64 64:ff9b::/96
  const isMapped = g[0] === 0 && g[1] === 0 && g[2] === 0 && g[3] === 0 && g[4] === 0 && g[5] === 0xffff;
  const isNat64 = g[0] === 0x0064 && g[1] === 0xff9b && g[2] === 0 && g[3] === 0 && g[4] === 0 && g[5] === 0;
  if (isMapped || isNat64) {
    const v4 = `${g[6] >> 8}.${g[6] & 0xff}.${g[7] >> 8}.${g[7] & 0xff}`;
    return isPublicIpv4(v4);
  }
  // 6to4 2002::/16 — check embedded IPv4
  if (g[0] === 0x2002) {
    const v4 = `${g[1] >> 8}.${g[1] & 0xff}.${g[2] >> 8}.${g[2] & 0xff}`;
    return isPublicIpv4(v4);
  }
  // Unique Local fc00::/7 (includes Tailscale fd7a:115c:a1e0::/48)
  if ((g[0] & 0xfe00) === 0xfc00) return false;
  // Link-local fe80::/10
  if ((g[0] & 0xffc0) === 0xfe80) return false;
  // Multicast ff00::/8
  if ((g[0] & 0xff00) === 0xff00) return false;
  // Documentation 2001:db8::/32
  if (g[0] === 0x2001 && g[1] === 0x0db8) return false;
  // Only allow global unicast 2000::/3
  if ((g[0] & 0xe000) !== 0x2000) return false;
  return true;
}

const BLOCKED_HOST_SUFFIXES = [".localhost", ".local", ".internal", ".arpa"];
const BLOCKED_HOSTS = new Set(["localhost", "localhost.localdomain", "metadata.google.internal", "metadata"]);

export function parsePublicWebhookUrl(raw) {
  let parsed;
  try {
    parsed = new URL(String(raw || ""));
  } catch {
    throw Object.assign(new Error("valid https webhook url is required"), { status: 400 });
  }
  if (parsed.protocol !== "https:" || parsed.username || parsed.password || parsed.hash || String(raw).length > 2048) {
    throw Object.assign(new Error("webhook url must be a public https address"), { status: 400 });
  }
  const host = parsed.hostname.toLowerCase().replace(/\.$/, "");
  const unbracketed = host.replace(/^\[|\]$/g, "");
  if (!unbracketed || BLOCKED_HOSTS.has(unbracketed) || BLOCKED_HOST_SUFFIXES.some((s) => unbracketed.endsWith(s))) {
    throw Object.assign(new Error("webhook url must be a public https address"), { status: 400 });
  }
  if (net.isIP(unbracketed) && !isPublicIp(unbracketed)) {
    throw Object.assign(new Error("webhook url must be a public https address"), { status: 400 });
  }
  return parsed;
}

export async function resolvePublicWebhook(raw, { lookup = dns.lookup, timeoutMs = 4000 } = {}) {
  const parsed = parsePublicWebhookUrl(raw);
  const host = parsed.hostname.replace(/^\[|\]$/g, "");
  if (net.isIP(host)) {
    return { url: parsed, address: { address: host, family: net.isIPv6(host) ? 6 : 4 } };
  }
  let timer;
  const rows = await Promise.race([
    lookup(host, { all: true }),
    new Promise((_, reject) => {
      timer = setTimeout(() => reject(Object.assign(new Error("webhook dns lookup timed out"), { status: 400 })), timeoutMs);
    }),
  ]).finally(() => clearTimeout(timer));
  if (!Array.isArray(rows) || !rows.length || rows.some((r) => !isPublicIp(r.address))) {
    throw Object.assign(new Error("webhook url must resolve to a public address"), { status: 400 });
  }
  return { url: parsed, address: rows[0] };
}

export async function deliverWebhook(rawUrl, { headers = {}, body = "", timeoutMs = 5000, lookup = dns.lookup } = {}) {
  const { url, address } = await resolvePublicWebhook(rawUrl, { lookup, timeoutMs: Math.min(timeoutMs, 3000) });
  const payload = Buffer.from(body, "utf8");
  return new Promise((resolve, reject) => {
    const req = https.request(
      url,
      {
        method: "POST",
        headers: { ...headers, "content-length": payload.length },
        lookup: (_host, opts, cb) => (opts?.all ? cb(null, [address]) : cb(null, address.address, address.family)),
      },
      (res) => {
        res.resume();
        resolve({ status: res.statusCode || 0 });
      },
    );
    const timer = setTimeout(() => req.destroy(new Error("webhook delivery timed out")), timeoutMs);
    req.on("close", () => clearTimeout(timer));
    req.on("error", reject);
    req.end(payload);
  });
}
