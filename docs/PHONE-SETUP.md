# Phone setup

1. Install Backstage. Choose **Create account**, or **Sign in** with an existing Backstage passkey. If this phone cannot offer your saved passkey, tap **Use a connection code**. On a phone already linked to your account, tap **Add a phone or tablet** and enter its code on the new phone.
2. After sign-in, Backstage shows **Allow phone access** until Android pairing finishes. Open Wireless debugging and enter Android's code in the Backstage notification. If its reply field does not work, tap **Enter in app** on the notification; Backstage fills the pairing port. Wi-Fi is needed for this step; mobile data works once Backstage is running. If phone access drops later, the main screen shows **Open Wireless debugging** (or **Open About phone** when Developer options are off). Enabled access restarts after a reboot or helper failure; a deliberate pause remains paused.
3. Tap **Connect an agent** and choose ChatGPT, Cursor, Codex, Claude Code or Another agent. Cursor, Codex and Claude Code receive a host-specific setup message. ChatGPT receives its connection address and a separately copied connection code. Compare the six digits and approve in Backstage. The invitation gives access to this phone only; additional device access can be changed in the app.

Android Settings and passkey prompts are system permission screens. They cannot be silently approved by Backstage. Some agent hosts also require the user to add a custom tool connection or press Connect. Hosts without custom tool support cannot connect.

The old account-page enrollment code also works under **Use a connection code**.

## Implementation and security

- AndroidX Credential Manager 1.6.0 handles the native passkey prompt, including providers on Android 11–13. The APK is larger because it now includes these libraries.
- `/.well-known/assetlinks.json` binds the distributed app's signing certificate to the existing passkey domain. Web and Android origins use distinct challenge channels. Native login accepts only the pinned Android signing origin; a client cannot choose an origin. Existing web passkeys recover the same owner.
- Native enrollment tickets last ten minutes. Requests are separated by an installation identifier; a new sign-in replaces only that installation's unfinished request. The app saves an unfinished ticket privately and resumes after recreation. Expired tickets require sign-in again. Confirmed links are saved before the enrollment UI finishes.
- Phone-created invitations use 144 random bits, expire after ten minutes, and work for one claim. An invitation is a request to connect, not an access credential. The phone must approve the identified request and compare six check digits. Issued permission is limited to the originating phone.
- Cloud clients keep standard OAuth callbacks, state, PKCE, token refresh and revocation. Even an existing browser account session defaults to phone approval. The old browser consent flow remains an explicit fallback.
- Local agents may use the standalone account connector. It keeps access and refresh tokens in a private file and exposes tools over standard input/output. Generated settings contain paths, not credentials. Refresh is serialized across connector processes sharing the file. The agent installs these settings with its host's supported tools.
- The server validates phone management sessions against the current phone link on every request so removing the phone also invalidates an existing management session.
- Setup requires a live Backstage service. Hosting still runs on the current server; this release does not move hosting onto the phone.

## Verification

80 Node checks pass, including real signed passkey fixtures for app and browser origins, enrollment retries, invitation ownership/device bounds, denial, expiry, replay, local credential storage, and a full cloud OAuth authorization/token/refresh exchange. Java host checks and Android build/lint pass.

A separate unlinked test application passed 11 screen checks on Redmagic's private display. It covered sign-in, manual enrollment fallback, invalid-code handling, help, Android access instructions and review-only deep links. The custom instrumentation stream reports success and Android's success result code (-1); the connector's generic test parser does not recognize this custom runner and reports `ran:false`. No production phone link was cleared.

Real biometric/passkey-provider confirmation is not automated. The signed APK and live app-to-domain association must be available before the owner tries that prompt. Synthetic signature tests do not prove a particular phone's credential provider works.


## Alpha9 setup and recovery

- The app keeps the pending invitation in its private settings across screen closure and recreation. Explicit cancellation revokes it; expiry offers a fresh start. Phone approval still belongs to the owner.
- The local installer supports Cursor, Codex and Claude Code. It preserves unrelated connections, backs up Cursor settings, uses the native Codex/Claude CLI where required, and keeps access credentials outside host configuration. After an interrupted claim, `connect --resume FILE --host HOST` uses the same saved proof. After approval, `install --account FILE --host HOST` and `verify --account FILE` retry without asking for another invitation.
- A setup model read proves the phone transport is working. The final agent instruction also requires a successful read through that host's own newly loaded tool. Config installation alone is not a completed host integration.
- Retry of an invitation claim is accepted only for the same original OAuth request or same local proof and name. It does not issue a second grant or allow another claimant.
- A foreign local service (including Device Agent 0.4.5 on port 18422) no longer counts as an outdated Backstage. The supervisor chooses a free port without shutting down the other app. Restart checks the service identity before sending shutdown. A failed or timed-out health check cannot authorize shutdown of an unknown service.
- The supplied icon is used in the app header, launcher foreground and plugin asset. The original is retained under assets/brand.

Verification: all 43 gateway tests passed, including a lost-claim response and same-proof resume, permission isolation and rejection, installer preservation and model-check failure cases. Host Java tests cover a real conflicting HTTP listener and free-port selection. Android build and lint passed. The unlinked test application passed 26 UI checks on Nova's private display; its temporary package is removed after QA. No real account approval or biometric prompt was automated. Real clean-account installation in each external host remains a separate acceptance check; host installation commands were tested with isolated fixtures, not falsely presented as live accounts.
