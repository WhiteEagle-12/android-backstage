# Self-hosted operations guide

The public MCP URL stays https://relay.android-backstage.com/work/mcp. The existing named Cloudflare tunnel forwards HTTPS to the loopback-only front service. The front dispatches encrypted relay requests and MCP/account requests to their respective processes.

The user services android-backstage-relay, android-backstage-work and android-backstage-tunnel are enabled. User lingering is enabled, so services start without an interactive login. systemd restarts failed processes. The machine must stay powered, connected and awake; no hosting subscription is used.

## State and deployment

- Relay state: ~/.local/share/android-backstage/data
- Gateway state: ~/.config/android-backstage/work
- Owner records: owners.json; only passkey public keys and hashed session tokens
- OAuth clients and hashed tokens: oauth.json
- Device links: connections/phones/phone_<id>.json; the file name is the device ID
- Device registry per owner: connections/devices.json (names, IDs, model, timestamps)
- Assistant grants: connections/grant_*.json with `access` = 'all' or a list of device IDs; legacy grants with `phoneId` are read as 'all'
- Staged uploads: artifacts/ (verified by SHA-256, expire after 45 minutes)
- Releases: ~/.local/share/android-backstage/releases/<commit>
- Private rollback receipts: ~/.local/share/android-backstage/rollbacks/<commit>
- Private pre-deployment backups: ~/.local/share/android-backstage/backups

Run the gateway, relay and CLI tests and Android build for changed components, commit, then run scripts/deploy-host.py from the checkout. It installs locked dependencies before stopping services, snapshots both stores with writers stopped, changes the service release, checks health and restores the previous release on failure. It never resets pairing or imports someone else's phone credentials. The snapshot contains secrets and stays outside source and public downloads.

For rollback after a successful deployment, stop both writers and select the previous release using the saved drop-ins, then restart. Verify compatibility of new state before rollback. Never restore an old phone counter file while its phone link is active: counters and revocations can have advanced. Disaster recovery from an old backup requires fresh authorization and phone enrollment for affected links. Do not restore stale permissions silently.

Backups on this machine protect against bad releases, not loss of the machine or its disk. scripts/backup-host.sh writes an AES-256 encrypted snapshot of the gateway and relay state to BACKSTAGE_BACKUP_DEST from ~/.config/android-backstage/backup.env (a mounted drive or an rsync-over-ssh target); `--install-timer` runs it daily. The passphrase is ~/.config/android-backstage/backup.key and must be kept apart from the backups. Do not copy these records into the website, a plugin or an agent workspace.

## Access

The phone app manages the account through `/work/phone/*`: it answers a nonce challenge with HMAC-SHA256 over its link key (`backstage owner v1\n` + nonce) and gets a 12-hour in-memory session bound to the device's owner. OAuth `/work/authorize` without an owner cookie shows an 8-character approval code (10-minute, single use) that the app redeems with a device choice; `?passkey=1` forces the browser passkey path. The front refuses plain HTTP and adds HSTS and related headers; the relay reads the client IP from the front's X-Forwarded-For.

A Backstage owner saves a discoverable passkey once. A synced passkey or another registered passkey allows sign-in on other browsers. Each OAuth client asks for consent and the owner picks which devices it may use (or all devices, including future ones). Grants never receive the device link; the gateway resolves `device` per call and refuses to guess when several devices are accessible. Access tokens last one hour; refresh tokens rotate and last 30 days of inactivity. Browser sessions last 30 days. Expired sessions require passkey sign-in, not phone enrollment.

Removing one assistant deletes its grant and leaves the devices and other grants intact. Removing a device deletes its link file and drops it from every grant; renaming keeps the ID. Reconnect (or Replace link) enrolls a phone again behind the same device ID, so assistants keep working after the app was reset or the phone replaced. The phone app can pause its Backstage link to stop every assistant on that device. Existing direct links remain usable and retain their old state; they are not silently claimed by a new owner.

The gateway holds a device against other grants while a UI tool runs (about two minutes, renewed per call) and for an explicit `android_reserve` of up to 30 minutes; holds are saved in holds.json so a restart keeps them until they expire. An optional `holder` label on any call lets two chats of one assistant hold separately. Relay probes are cached for four seconds. Different devices never block each other. `android_vd_handoff` is the only tool that touches the main display and requires `confirmed:true`.

The gateway decrypts commands and results to relay them; the relay and Cloudflare see only ciphertext. docs/PRIVACY-BOUNDARY.md records that decision and the wording every disclosure must keep.

## External acceptance

### Refresh tool metadata after a gateway release

Hosted MCP tool definitions can remain cached after the server changes. For a developer-mode ChatGPT connection, open Android Backstage at https://chatgpt.com/plugins, select **Refresh**, review the discovered tools, then start a new conversation with the connection enabled. Published plugins require a new reviewed metadata snapshot instead. See [OpenAI's refresh procedure](https://developers.openai.com/plugins/deploy/connect-chatgpt#refresh-metadata).

Call `android_help` to read the running gateway's rules and tool names without contacting a phone. Compare those names with the tools the client exposes. A fresh connection must expose `android_vd_handoff` with required `confirmed`, plus the device-selection and transfer tools. A stale list can omit the handoff even though the running gateway implements it. Never work around a missing handoff with shell commands that move tasks to display 0.

Hosted UI tools require an explicit positive virtual display ID. Their help must not promise an optional display or a default of 0. The direct CLI has a different interface; do not append its device-locking or foreground defaults to hosted MCP instructions.

Automated tests use cryptographic phone fixtures and real signed WebAuthn responses. They do not prove native ChatGPT attachment or a real phone approval. Record each real client result separately in review/connection-fix/RESULTS.md. ChatGPT publishing also requires the publisher's verified account, support and legal details, domain challenge, dedicated review phone and OpenAI approval. Private custom connections can be installed before publication where account policy permits them.
