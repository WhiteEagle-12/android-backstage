/**
 * Free Cloudflare Worker cron monitor for Android Backstage.
 * Checks both VM 1 (/health) and VM 2 (/work/health) every 5 minutes and sends
 * Resend email alerts on UP -> DOWN and DOWN -> UP transitions (no spam).
 */
const TARGETS = [
  { id: "relay", label: "VM 1 Relay (/health)", url: "https://relay.android-backstage.com/health" },
  { id: "work", label: "VM 2 Gateway (/work/health)", url: "https://relay.android-backstage.com/work/health" },
];

const STATE_KEY = "monitor/uptime-state.json";

async function probeOnce(url) {
  const start = Date.now();
  try {
    const res = await fetch(url, {
      headers: { "user-agent": "backstage-uptime-monitor/1.0" },
      signal: AbortSignal.timeout(10_000),
    });
    const ms = Date.now() - start;
    const body = await res.json().catch(() => null);
    if (res.ok && body?.ok === true) return { ok: true, status: res.status, ms };
    return { ok: false, status: res.status, ms, error: `HTTP ${res.status}` };
  } catch (err) {
    return { ok: false, status: 0, ms: Date.now() - start, error: String(err?.message || err) };
  }
}

async function probeWithRetry(url) {
  const first = await probeOnce(url);
  if (first.ok) return first;
  await new Promise((r) => setTimeout(r, 4000));
  return probeOnce(url);
}

async function sendAlert(env, subject, text) {
  if (!env.RESEND_API_KEY || !env.ALERT_TO) return false;
  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: {
      authorization: `Bearer ${env.RESEND_API_KEY}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      from: env.ALERT_FROM || "Android Backstage Monitor <alerts@android-backstage.com>",
      to: [env.ALERT_TO],
      subject,
      text,
    }),
  });
  return res.ok;
}

async function runCheck(env, notify = true) {
  const checks = {};
  for (const t of TARGETS) {
    checks[t.id] = await probeWithRetry(t.url);
  }
  const nowIso = new Date().toISOString();
  const prevObj = env.STATE_BUCKET ? await env.STATE_BUCKET.get(STATE_KEY) : null;
  const prev = prevObj ? await prevObj.json().catch(() => ({})) : {};
  const next = { checkedAt: nowIso, services: {} };
  const downNow = [];
  const recovered = [];

  for (const t of TARGETS) {
    const cur = checks[t.id];
    const wasDown = prev?.services?.[t.id]?.ok === false;
    next.services[t.id] = { ...cur, since: wasDown === !cur.ok && prev?.services?.[t.id]?.since ? prev.services[t.id].since : nowIso };
    if (!cur.ok && !wasDown) downNow.push({ target: t, cur });
    if (cur.ok && wasDown) recovered.push({ target: t, cur, since: prev?.services?.[t.id]?.since });
  }

  if (env.STATE_BUCKET) {
    await env.STATE_BUCKET.put(STATE_KEY, JSON.stringify(next, null, 2), {
      httpMetadata: { contentType: "application/json" },
    });
  }

  if (notify && downNow.length) {
    const lines = downNow.map(({ target, cur }) => `- ${target.label}: ${cur.error || "failed"} (${cur.ms}ms) — ${target.url}`);
    await sendAlert(
      env,
      `[DOWN] Android Backstage alert (${downNow.map((d) => d.target.id).join(", ")})`,
      `Detected at ${nowIso}:\n\n${lines.join("\n")}\n\nRetried once after 4s before alerting.`
    );
  }
  if (notify && recovered.length) {
    const lines = recovered.map(({ target, cur, since }) => `- ${target.label}: back UP (${cur.ms}ms; down since ${since || "unknown"})`);
    await sendAlert(
      env,
      `[RECOVERED] Android Backstage back online (${recovered.map((r) => r.target.id).join(", ")})`,
      `Recovered at ${nowIso}:\n\n${lines.join("\n")}`
    );
  }

  return { ok: Object.values(checks).every((c) => c.ok), checkedAt: nowIso, checks };
}

export default {
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(runCheck(env, true));
  },
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === "/state" && env.STATE_BUCKET) {
      const obj = await env.STATE_BUCKET.get(STATE_KEY);
      if (obj) return new Response(obj.body, { headers: { "content-type": "application/json", "cache-control": "no-store" } });
    }
    const result = await runCheck(env, false);
    return new Response(JSON.stringify(result, null, 2), {
      status: result.ok ? 200 : 503,
      headers: { "content-type": "application/json", "cache-control": "no-store" },
    });
  },
};
