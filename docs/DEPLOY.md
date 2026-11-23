# Deployment and recovery — 1.1 alpha

Start with a private relay and one real phone. This is a single-process alpha;
it is not yet a horizontally scaled hosted service. Use a persistent disk,
keep credentials outside Git, and build a known APK and relay revision together.

## Private Tailscale deployment

Tailscale must be connected on the relay machine and phone. Run the relay
bound to the machine's Tailscale IPv4 address, with that same address in its
public URL. This keeps the HTTP listener off public and ordinary LAN interfaces.

```bash
HOST=<relay-tailscale-ip> PORT=18421 \
RELAY_PUBLIC_URL=http://<relay-tailscale-ip>:18421 \
RELAY_DATA_DIR=/absolute/private/path/backstage-data \
node relay/server.mjs
```

Install the APK, pair the phone's Wireless debugging, and use `backstage pair`
to link the CLI through the Android app. No host ADB is needed for the normal
setup. Tailscale network connectivity and Android shell authorization are
separate: Tailscale alone cannot authorize control of a phone.

Run the relay under systemd or a container supervisor with restart-on-failure.
Use an owner-only data directory, a stable working directory, and a Node 22
runtime. If the machine's Tailscale address changes, update its bind address,
public URL and the phone's relay setting together. Review tailnet access
rules for who can reach the relay and phone ports.

## Public HTTPS deployment

The existing `deploy/docker-compose.yml` runs a relay and Cloudflare Tunnel.
Copy `.env.example` to the ignored `.env`, supply your actual hostname and
tunnel token, and set a random `RELAY_ADMIN_TOKEN` for operator endpoints.
Map that hostname to `http://relay:18421` in the tunnel, then run:

```bash
cd deploy
docker compose config --quiet
docker compose up -d --build
```

The configured domain is an example until you create its DNS/tunnel route.
The relay port is bound only to host loopback. A Caddy/nginx TLS reverse proxy
is also suitable. Keep a single relay process per data directory. Pin the
container images you have validated for each release instead of tracking a
moving `latest` tag in production.

Read the initial API key from the private `first-api-key.txt` file and place
it in an owner-only CLI configuration. First boot also logs that key, so
protect bootstrap logs. The phone's local administration token is never
needed by the relay. Keep encrypted command metadata and uploaded ciphertext
on the persistent volume; do not use an ephemeral serverless filesystem.

## Limits and monitoring

New CLI calls submit jobs and poll short responses, so a ten-minute Android
instrumentation run does not depend on a ten-minute HTTP connection. Uploads
use 8 MiB requests, below Cloudflare's documented 100 MB Free/Pro body limit.
The relay still caps an entire encrypted file at 512 MiB by default.

The queue allows 32 pending jobs per device, 10,000 retained jobs overall,
and uses a 512 MiB admission budget including result reservations. Pairing
allows eight sessions and 25 devices per account. Uploads have an independent
2 GiB reserved-byte limit; published files have another 2 GiB limit and a
256-file cap. Provision at least 8 GiB of disk with free-space alerts for this
configuration. Completed job retention is 24 hours; temporary file retention
is normally 15 minutes. Request limits are per authenticated account/device,
and per source address for unauthenticated requests.

Monitor `/health`, service restarts, disk free space, and authenticated
`GET /v1/metrics` (admin bearer token). Investigate growing unknown-job counts,
queued work with no online device, or persistent upload/storage quota errors.
Metrics expose aggregate counts, not tool arguments or result contents.

## Backup, restore and rollback

1. Stop the relay so registry, job and file state are quiescent.
2. Back up the **entire** data directory, including `state.json`, `jobs/`,
   `uploads/`, and `files/`. Encrypt off-machine backups and restrict access.
3. Restart the relay. Periodically restore a backup to an isolated directory
   and port; verify health and registry without executing phone actions.
4. Keep the previously validated relay revision and APK checksum. Roll back
   code only to a release compatible with the current protocol/state format.
   Do not downgrade this deployment to v1 envelope execution or restore old
   phone permissions/counters as a routine rollback technique.

After restart, queued jobs retain their deadline. Running jobs become
**unknown** until a pending device result resolves them. Use
`backstage job <id>` to inspect the recorded response and current state.
`backstage cancel <id>` prevents queued execution; it cannot guarantee that a
running Android action stops. Inspect the app state before issuing a fresh
side effect after an unknown outcome.

Operator API-key rotation is `POST /v1/accounts/<account-id>/rotate-key`
with the admin bearer token. Store the returned API key securely and update
all agent configurations; the old key stops working immediately. A lost
rotation response can be recovered by repeating rotation with the admin
token. Device tokens are revoked by forgetting the device and re-linking;
revoke an agent's actual command authority with **Forget** in the phone app.

## APK builds and signing

On Linux, `bash scripts/cloud-agent-install.sh` downloads verified toolchains
into the ignored `.toolchains/` directory without changing system directories.
Source `.toolchains/env.sh` afterward. Alternatively, set `JAVA_HOME` to JDK 17
and `ANDROID_HOME` to an SDK containing Android 35
and build-tools 35.0.0. Run `bash scripts/build-debug.sh`. This generates an
ignored local development key, runs the Java host tests, builds the debug
APK and runs Android lint. CI builds the same development artifact.

For a release, provide `BACKSTAGE_KEYSTORE`, `BACKSTAGE_STORE_PASSWORD`,
`BACKSTAGE_KEY_ALIAS`, and `BACKSTAGE_KEY_PASSWORD` through your secret store,
then run `./gradlew :app:assembleRelease :app:lintRelease` in `app/`.
The old repository signing key must be treated as compromised; a new key
cannot update an old-key install without an explicit signing migration or
reinstall. Keep the development key stable locally for repeatable upgrades.

Before expanding beyond the alpha: validate unattended recovery after reboot,
OEM battery restrictions and Wi-Fi changes on every supported device; run an
independent crypto review; decide distribution/signing migration; review the
`specialUse` foreground service declaration for the intended distribution
channel; exercise backup restore and incident key rotation.

Official references: [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[Android 15 service restrictions](https://developer.android.com/about/versions/15/behavior-changes-15),
[Cloudflare request limits](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/4xx-client-error/error-413/),
[connection limits](https://developers.cloudflare.com/fundamentals/reference/connection-limits/).
