# Relay dashboard

Open https://relay.android-backstage.com/dashboard/ (or /dashboard/ on your own relay).
The root relay address redirects here. Sign in with a relay account API key.
The page keeps it in memory only, sends it as a Bearer header to its own origin,
and clears it on sign-out or reload. Phone tokens cannot open the dashboard.

The account-scoped /v1/dashboard endpoint returns device metadata, the latest
300 job summaries, the latest 500 recorded HTTP events, active requests, process
memory, uptime and version. It never returns command envelopes, result payloads,
raw error text, file download URLs, pairing codes or auth headers. Complete means
response delivered, not necessarily tool success. Unknown means inspect before
retrying a side effect.

Controls: live refresh every 5 seconds, pause updates, source/search filters,
export visible activity, rename/remove a device, and cancel a queued request.
Mutations use the existing account-authorized endpoints. Removing a device
revokes all client access to that enrollment; it is not an individual phone
permission control. Those remain in the Android app.

History is capped at 2,000 events across accounts and 24 hours, and saved to
RELAY_DATA_DIR/dashboard-activity.json with mode 0600. Dashboard reads are not
logged as phone traffic. Counts cover retained history, not all-time totals.
Body byte counts exclude HTTP headers and transport overhead. No request bodies,
IPs, query strings or raw unknown route paths are stored. This covers the blind
relay's /v1 traffic, not the Work gateway's OAuth/browser traffic or Cloudflare
analytics. Work-to-phone calls enter the relay as normal client traffic.

Tests: node --test relay/test/*.test.mjs. Dashboard-specific tests cover auth,
account isolation, secret/payload exclusion, request/response byte counts, static
security headers and private restart persistence. Browser controls were checked
against a separate localhost fixture; no real phone was paired for those checks.
