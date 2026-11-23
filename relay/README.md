# Android Backstage relay

Zero-dependency Node 20+ process. The relay is a **blind forwarder**: it
stores and copies encrypted envelopes and pairing bytes it cannot read.
It never sees a tool name, argument, result, or file plaintext.

Phones long-poll over HTTPS; the agent CLI encrypts each call and POSTs
`/v1/jobs` and polls short status requests. Pairing is SPAKE2 through `/v1/pair/session/*` (the password
never reaches the relay).

## Run with Node

```bash
cd relay
node server.mjs
```

On first start with an empty data dir the relay creates account `default`,
prints the API key once, and writes it to `$RELAY_DATA_DIR/first-api-key.txt`
(mode 0600).

```bash
export RELAY_DATA_DIR=./data
export PORT=18421
node server.mjs
# copy the bsk_… key from stdout or data/first-api-key.txt
```

Pair from the repo root (the CLI prints a `XXXX-YYYYYY` link code):

```bash
node cli/backstage.mjs --relay-url http://127.0.0.1:18421 --api-key bsk_… pair --name phone
```

Type that code in the Android Backstage app.

## Run with Docker

```bash
docker build -t backstage-relay .
docker run --rm -p 18421:18421 -v backstage-data:/data backstage-relay
```

The key is printed in the container logs on first boot and stored at
`/data/first-api-key.txt`.

## Environment

| Var | Default | Meaning |
| --- | --- | --- |
| `PORT` | `18421` | listen port |
| `HOST` | `0.0.0.0` | bind address |
| `RELAY_DATA_DIR` | `./data` | registry, jobs, uploads and published files |
| `RELAY_PUBLIC_URL` | derived from `Host` | absolute URL for file links |
| `RELAY_ADMIN_TOKEN` | unset | enables account creation, key rotation and metrics |
| `RELAY_POLL_MS` | `25000` | long-poll hold time |
| `RELAY_OFFLINE_MS` | `45000` | device considered offline after this |
| `RELAY_MAX_FILE_MB` | `512` | upload cap |

`GET /health` is public. Agent endpoints under `/v1/devices`, `/v1/call`,
`/v1/files` (upload), `/v1/pair/session` need `Authorization: Bearer <api key>`.
Device endpoints use a `bsd_` token. `POST /v1/pair/session/<id>/device` is
unauthenticated (one attempt per session).

## Endpoints

| Method | Path | Auth | Notes |
| --- | --- | --- | --- |
| GET | `/health` | — | `{ ok, service, version, devicesOnline }` |
| POST | `/v1/accounts` | admin | mint an account + API key |
| GET | `/v1/me` | API key | `{ accountId, name }` |
| GET | `/v1/devices` | API key | rows include `agents: [agentId…]` |
| PATCH | `/v1/devices/<name>` | API key | `{name?, aliases?, inboundUrl?}` |
| DELETE | `/v1/devices/<name>` | API key | revokes the device token |
| POST | `/v1/call` | API key | `{ device, envelope, timeoutMs? }` → `{ envelope }` or `{ error }` |
| POST | `/v1/files` | API key | ciphertext upload; relay does not inspect the body |
| GET | `/v1/files/<id>` | — | ciphertext download (single-use + TTL) |
| POST | `/v1/pair/session` | API key | `{ sessionId, name, expiresAt }` |
| PUT | `/v1/pair/session/<id>/agent` | API key | `{ agentId, msg }` |
| POST | `/v1/pair/session/<id>/device` | — | creates device + token; one attempt |
| PUT | `/v1/pair/session/<id>/device-confirm` | device | `{ confirm }` |
| GET | `/v1/pair/session/<id>/device` | API key | long-poll ≤ 25 s → `{ deviceId, msg, confirm }` |
| PUT | `/v1/pair/session/<id>/confirm` | API key | `{ confirm }` |
| GET | `/v1/pair/session/<id>/confirm` | device | long-poll ≤ 25 s → `{ confirm }` |
| DELETE | `/v1/pair/session/<id>` | API key | aborts; revokes the device this session created |
| POST | `/v1/device/hello` | device | `{ ok, name, pollMs }` |
| POST | `/v1/device/next` | device | `{ id, envelope }` or `{ idle: true }` |
| POST | `/v1/device/result` | device | `{ id, envelope }` or `{ id, error: { message } }` |

The current durable-job, upload and recovery contracts are documented in
[ARCHITECTURE.md](../docs/ARCHITECTURE.md). See [DEPLOY.md](../docs/DEPLOY.md)
for limits, backup, rollback and key rotation.
