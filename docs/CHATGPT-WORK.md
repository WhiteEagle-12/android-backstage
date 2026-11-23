# ChatGPT and native OAuth connections

The gateway at `https://relay.android-backstage.com/work/mcp` keeps one account per passkey owner with any number of named devices. A new OAuth grant is authorized for chosen devices (or all); enrolling a device happens once from the account page, not per assistant. The user opens the native connection flow, scans the Backstage QR code or opens its phone link, and approves the assistant on their phone. No operator approval code or agent-controlled browser sign-in is needed.

Install pinned dependencies with `npm ci --prefix gateway`. Set `BACKSTAGE_WORK_STATE` to a private directory and `BACKSTAGE_RELAY_URL` to the public HTTPS relay origin, then run `node gateway/server.mjs`. It binds to loopback port 18424. `gateway/front.mjs` preserves the existing relay and OAuth routes.

Do not start the new gateway against old OAuth state without migrating existing grants. With the old gateway stopped, run `node gateway/migrate-legacy.mjs --gateway-stopped` with the existing `BACKSTAGE_WORK_STATE` and `BACKSTAGE_WORK_CONFIG`. The migration references the existing private link instead of copying its identity or counters.

New access and refresh tokens carry an opaque grant ID. Every tool takes a `device` name or ID, and `android_devices` lists what that grant may use; the gateway never substitutes a device that is offline, paused, busy or unauthorized. Host environment credentials cannot select another phone. Files built on the assistant's machine reach a phone through `android_artifact_begin` plus an authenticated upload (`PUT /work/artifacts/:id`), verified by SHA-256 before the gateway encrypts them for the device. The phone still enforces its own pause, removal and app restrictions. Results pass through the gateway to the assistant, so the gateway operator can read commands and results; the relay retains only encrypted envelopes (see docs/PRIVACY-BOUNDARY.md).

The plugin package lives in `integrations/android-backstage/`. Publishing a directory entry is a separate OpenAI review step, described in `integrations/CHATGPT-SUBMISSION.md`. Do not promise a one-click ChatGPT install before that entry exists. For an existing custom connector, native OAuth uses the same server URL.

Release checks, migration, trust boundaries and rollout are in `review/connection-fix/RELEASE.md`. Historical personal-gateway verification is retained in `review/connection-fix/PREVIOUS-WORK-SETUP.md`; it does not verify this new release.
