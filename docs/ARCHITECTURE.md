# Android Backstage 1.1 alpha

The agent-side CLI runs an MCP stdio server. It encrypts tool calls locally,
sends envelopes through the relay, and decrypts returned envelopes. The relay
has no plaintext MCP endpoint. The Android app handles local setup and
permissions; its embedded ADB client starts a separate daemon as uid 2000.
That daemon owns the virtual display, tool execution and outbound polling.

```text
MCP client → local CLI → encrypted jobs → relay ← phone daemon ← Android app
                              or CLI → encrypted /call over private LAN
```

## Relay API

- `POST /v1/pair/session`, exchange `/agent`, `/device`, `/device-confirm`
  and `/confirm`: SPAKE2 rendezvous and mutual confirmation. A previously
  linked phone can prove its existing device token and add another agent
  without creating a duplicate physical device.
- `GET /v1/devices`: the authenticated account's registry.
- `POST /v1/jobs {id,device,envelope,timeoutMs}`: durable, idempotent admission,
  returns 202. `GET /v1/jobs/:id` returns state and any encrypted outcome;
  `DELETE` cancels queued work or requests cancellation of running work.
- `POST /v1/device/hello`, `/next`, `/result`: device-token authenticated
  polling and result delivery. A new device polling generation makes
  previously running work unknown. The device retries a pending encrypted
  result before taking another job.
- `POST /v1/uploads {name,size}`, `GET /v1/uploads/:id`,
  `PUT /v1/uploads/:id` (header `X-Upload-Offset: N`), `POST /v1/uploads/:id/complete`:
  resumable encrypted file transport. Completed responses provide a capability
  download URL.
- `POST /v1/call` remains a compatibility synchronous transport. New clients
  use short job requests to avoid a single HTTP response lasting ten minutes.
- `/health` is public status. `/v1/metrics` requires the operator admin token
  and reports aggregate job counts and published files.

The relay is one Node process with one writer per data directory. Registry,
jobs, upload metadata and published files are durable. Live poll waiters and
rate buckets are in memory. Never run multiple relay replicas sharing this
filesystem. A database/transactional lease implementation is required before
horizontal scaling.

## Phone execution

`ControlServer` distinguishes encrypted LAN calls from loopback-only
administration. `LinkStore` owns per-agent crypto and durable replay state.
`EnvelopeCall` enforces signed deadlines, package permissions and the device
execution lease. `RequestJournal` records intent and response for bounded
call-ID deduplication. `DeviceNode` delivers results and reconnects with
backoff. Execution uncertainty is surfaced instead of retried silently.

The foreground supervisor uses `specialUse` with a declared purpose. It is
started by the user and shows a Stop action. While access remains enabled,
boot and package replacement restart supervision. A stopped service schedules
a retry; the daemon's relay poll also retries after an unexpected failure.
Pause disables these restarts. Wireless debugging is needed to start a lost
shell daemon, and OEM power restrictions remain device-dependent.

## Repeatable QA

`backstage qa <package>` verifies an app stays alive and has no launch crash.
`backstage workflow flow.json '{"app":"com.example.app"}'` executes explicit
steps, assertions and substitutions. A launch can `saveAs: "launch"`, and
later tools can use `"display": "${launch.display}"`. Every run saves a local
receipt. `backstage compile <passed-receipt.json>` preserves the original
parameterized steps and assertions as a reusable routine. Failed runs stop;
side effects are never automatically retried.

See SECURITY.md for the v2 wire format and authority model, DEPLOY.md for
operations, and ROADMAP.md for the product boundary and next experiments.
