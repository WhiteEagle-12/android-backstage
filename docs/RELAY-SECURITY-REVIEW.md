# Relay hosting security review (2026-09-12)

Question asked: is hosting the Backstage server at `relay.android-backstage.com`, a subdomain of the public website, risky?

Short answer: the subdomain itself is not the risk. The website (`android-backstage.com`, static files on OpenAI Sites) and the server (`relay.android-backstage.com`, a Cloudflare Tunnel to this machine) are different origins on different hosts. Browsers do not share cookies, passkeys or scripts between them. What matters is how the relay hostname is exposed and what each party can see. That is reviewed below, with what was changed.

## How the server is exposed

- Nothing listens on a public port. Relay, front and gateway bind to `127.0.0.1` only (`ss -ltnp`: 18420, 18421, 18424). `cloudflared` opens an outbound connection to Cloudflare; Cloudflare forwards requests for the hostname down that connection to the loopback front on 18421.
- The tunnel token lives in `~/.config/android-backstage/cloudflare-tunnel.token` (mode 0600). Anyone with that token could point the hostname at another machine, so it is as sensitive as the DNS account.
- Cloudflare terminates TLS. Cloudflare therefore sees assistant ↔ gateway traffic in plaintext (OAuth tokens, tool calls, results). The gateway ↔ phone envelopes are encrypted end to end and Cloudflare cannot read them. This is the same position as any Cloudflare-fronted API and is documented on the site. The alternatives (a VPS you rent, or opening a port on your home router with your own certificate) trade this for exposing a public IP without DDoS protection. Recommendation: keep Cloudflare for now.

## Findings and fixes

| # | Finding | Risk | Change |
|---|---------|------|--------|
| 1 | Cloudflare answered `http://relay.android-backstage.com/...` with 200 (zone lacks “Always Use HTTPS”). A misconfigured client could send a bearer token unencrypted to Cloudflare. | Medium | `gateway/front.mjs` now redirects plain-HTTP GET/HEAD to HTTPS (308) and refuses every other plain-HTTP request with `https_required`. Also enable **Always Use HTTPS** and **Minimum TLS 1.2** in the Cloudflare dashboard (cannot be done from this machine). |
| 2 | No `Strict-Transport-Security`, `X-Frame-Options`, `Permissions-Policy` or `Cross-Origin-Opener-Policy` headers; caching left to defaults. | Low | The front adds these to every response and defaults to `Cache-Control: no-store`. CSP and `Referrer-Policy: no-referrer` were already present. |
| 3 | The relay’s per-IP rate limits (pairing: 120/min, API: 3000/min) keyed on the socket address, which behind the tunnel is always `127.0.0.1`. All visitors shared one bucket: one flood blocked everyone’s pairing. | Medium | The relay now uses the `X-Forwarded-For` value that the front derives from Cloudflare’s `CF-Connecting-IP` (only when the socket is loopback, so a direct client cannot spoof it). The gateway already used `trust proxy loopback` with the same header. |
| 4 | OAuth approvals were capped at 128 pending requests globally. Anyone could register a client (open dynamic registration, 60/min) and park 128 approval pages, blocking real connections for ten minutes at a time. | Medium | Cap is now 8 pending per client IP and 512 overall. |
| 5 | New phone-management API (`/work/phone/*`). | — | Designed with: HMAC challenge over the device’s existing link key (no new secret on the phone), 12-hour in-memory sessions, per-IP limits on challenge/session, per-owner limits on approval-code attempts (20 per 10 min), 8-character codes from a 32-symbol alphabet (40 bits) that expire in ten minutes and work once. A phone cannot remove itself. |

## Checked and left as is

- Passkeys: RP ID is `relay.android-backstage.com`; expected origin is the relay origin; user verification required. A compromised website at the apex cannot use or register these credentials.
- Owner session cookie: `Secure; HttpOnly; SameSite=Lax`, host-only (no `Domain=` attribute), so it is never sent to `android-backstage.com`.
- State-changing browser endpoints check the `Origin` header against the relay origin or a registered OAuth callback origin (`invalid_origin` otherwise).
- OAuth: PKCE S256 required, redirect URIs bound to registered clients, authorization codes are 32 random bytes and expire in two minutes, access tokens 1 h, refresh tokens 30 days, all stored hashed.
- Relay administration (`/v1/metrics`, etc.) requires the admin token; unauthenticated call returns 401.
- Artifact uploads require the per-artifact `artup_…` token, are size-declared and digest-checked before staging, and expire.
- Backups are encrypted with a key that never leaves `~/.config/android-backstage/backup.key` (0600).

## Operator recommendations

1. Cloudflare dashboard for `android-backstage.com`: turn on **Always Use HTTPS**, set **Minimum TLS version 1.2**, enable **Bot Fight Mode**, and add a **rate-limiting rule** for `relay.android-backstage.com/work/authorize` and `/work/register` (for example 30 requests per minute per IP). Protect the Cloudflare and registrar accounts with hardware-key or app 2FA; DNS control is the real shared surface between site and server.
2. Copy `~/.config/android-backstage/backup.key` and `cloudflare-tunnel.token` to a password manager. Losing the first makes backups unreadable; leaking the second lets someone impersonate the hostname.
3. Optional later: move the server to its own registrable domain only if you ever want to host the marketing site on the same Cloudflare zone with third-party scripts. Today there is no technical benefit.
