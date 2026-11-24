# Running your own relay

Most people use the hosted server; see the README. To run your own, you need three parts: a relay you can reach over HTTPS, the app on the phone, and this
repo (or just the CLI) wherever your agent runs. No Shizuku, no VPN, no
computer with ADB.

## 1. Relay

The relay is one Node process with no dependencies. Run it anywhere that has
a public HTTPS URL (any small VPS, Fly.io, Render, Railway, a home server
behind a reverse proxy). It stores its registry, job ledger and file uploads on a persistent filesystem.

```bash
# plain node (>= 20)
RELAY_DATA_DIR=/var/lib/backstage node relay/server.mjs

# or docker
docker build -t backstage-relay relay/
docker run -d --name backstage-relay -p 18421:18421 -v backstage-data:/data backstage-relay
```

On the first start it creates one account and prints its API key once
(also saved to `RELAY_DATA_DIR/first-api-key.txt`). Keep it: that key is what
your agents use. Put HTTPS in front (Caddy, nginx, your host's TLS) and note
the public URL, e.g. `https://backstage.example.com`.

Environment variables: see `relay/README.md`.

## 2. Phone

1. Install the APK (`app/app/build/outputs/apk/release/app-release.apk`, or
   your externally signed release). For development, run `scripts/build-debug.sh`
   and use `app/app/build/outputs/apk/debug/app-debug.apk`. Open **Android Backstage**.
2. **Wireless debugging.** Tap *Open developer options*, turn on **Wireless
   debugging**, then tap **Pair device with pairing code**. The app notices the
   pairing service and posts a notification: type the 6-digit code there (or
   in the app's field if you use split screen). Pairing is stored; you do this
   once per phone.
3. **Link to the relay.** On your computer:

   ```bash
   # Configure BACKSTAGE_RELAY_URL and BACKSTAGE_API_KEY securely first.
   node cli/backstage.mjs pair --name phone
   ```

   prints a link code like `7K2Q-M8ZT4C` and waits. In the app, enter the relay
   URL and that code, tap **Link**. The code is generated on your computer and
   never sent to the relay: both ends run a password-authenticated key exchange
   through it and come out with a shared link key the relay does not have. From
   then on every command and result is encrypted end to end
   (`docs/SECURITY.md`). The CLI saves the link into `config/backstage.json`.
4. Tap **Start**. The app connects to the phone's own ADB over TLS, starts the
   Backstage daemon as the `shell` user, and shows *daemon: ok, relay:
   connected*. The daemon keeps running with the screen off and the phone
   locked; the app only supervises it.

After a reboot: Wireless debugging is off again on most phones. The app posts a
notification; open the app, enable Wireless debugging and tap Start.

Alternative if pairing is impossible on your ROM: the app writes
`/sdcard/Android/data/com.androidbackstage.app/files/start.sh`; run
`adb shell sh /sdcard/Android/data/com.androidbackstage.app/files/start.sh`
once from any computer and the daemon starts exactly the same way.

## 3. Repo / agent

`backstage pair` wrote `config/backstage.json` for you:

```json
{ "relayUrl": "https://backstage.example.com", "apiKey": "bsk_...", "agentId": "agt_...",
  "device": "phone", "links": { "phone": { "deviceId": "dev_...", "linkKey": "..." } } }
```

That file is the agent's identity: whoever has it can drive the phone, and
nobody without it can (the relay included). Keep it out of Git, including private repositories. Transfer it through a
secret store, or pair a separate agent identity on another machine. Then:

```bash
node cli/backstage.mjs devices                      # phone: online
node cli/backstage.mjs shell 'getprop ro.product.model'
node cli/backstage.mjs mcp-config > .cursor/mcp.json # MCP server per device (stdio)
```

`.cursor/mcp.json` runs `node cli/backstage.mjs mcp --device phone` as a local
MCP server; Cursor talks to it on the machine where the agent runs, and it
encrypts every call before the relay sees it. A Cloud Agent that receives this
repo reads `AGENTS.md` and is ready to work on the phone. Desktop Cursor uses
the same file.

### LAN only (no relay)

On the same Wi-Fi the CLI can talk to the daemon's `:18422` directly with the
same encrypted envelopes: `node cli/backstage.mjs --direct http://<phone-ip>:18422 shell id`.
The link is still created with `backstage pair` through a relay once; after
that the relay is optional on the LAN.

### Cellular use and restart limits

Wireless debugging starts the privileged daemon; it is not the transport for
Backstage commands. Once the daemon is running, the relay can be reached over
cellular data, including a private Tailscale connection. The supervisor does
not demand Wireless debugging while a healthy daemon is already running.
OEM process killing can still interrupt it. This behavior must be verified
on each supported device and across its network transitions.

On an unrooted phone, a reboot or loss of all shell processes requires another
privileged bootstrap: Wireless debugging on Wi-Fi, or an authorized USB host.
A rooted-device startup service could remove this bootstrap dependency; root
startup is not implemented in this alpha. An accessibility-only companion is
another possible mode but cannot provide the present shell privileges and
trusted headless-display feature set. Tailscale does not change these Android
privilege boundaries.

### Device selection

Pass `--device phone` (or the exact name you paired) for a task. Each MCP
session is locked to its selected device. A missing selection, an unknown
name or an offline device produces an error; another online phone is never
chosen automatically. The paired device ID is checked by the relay and is
authenticated inside each encrypted call, so reassigning a name cannot
silently retarget existing clients. Duplicate aliases in old registry state
are rejected. Debugging discovery accepts only addresses belonging to the
phone itself, ignoring other phones' mDNS advertisements.

## Cursor Cloud Agents with this repo

`.cursor/environment.json` installs a JDK and the Android SDK so the agent can
build APKs and ship them to the phone; controlling the phone needs only Node.
`.cursor/mcp.json` exposes each device as an MCP server; regenerate it with
`node cli/backstage.mjs mcp-config` after linking devices.

