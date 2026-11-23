# Security model — 1.1 alpha

A linked agent can run commands with Android's shell privileges. A full-trust
link is powerful: it can install apps and interact with accounts already
signed in on the phone. Keep agent configuration and device state private.
The relay cannot decrypt command envelopes. Compromise of an endpoint, its
link key, or the Android shell account is outside that protection. The hosted
gateway is such an endpoint: it holds one link per enrolled device and reads
commands and results to serve hosted assistants (see PRIVACY-BOUNDARY.md).

## Pairing and transport

The CLI and app exchange SPAKE2 messages and mutual HMAC confirmations using
a ten-character code. The first four characters identify a public relay
session; the remaining six characters are the secret. Pairing sessions are
single-claim, expire after ten minutes, and have quotas and request limits.
The link key is HKDF-SHA256 with info `backstage link v1`. Agent and device use
names `backstage agent\0` and `backstage device\0` respectively. See the
cross-language vectors and host tests for byte-level details.

Use HTTPS for a publicly accessible relay. HTTP is suitable only inside a
trusted encrypted private network such as Tailscale. Pairing secrets, link
keys, and file keys are never submitted to the relay. Account keys and device
tokens authorize relay bookkeeping and are stored hashed. Relay operators
can see device identity, timing and sizes, and can delay or drop traffic.

## Version 2 envelopes

`{v:2, from, n, r, ct}` uses AES-256-GCM with a random 96-bit nonce `r`, encoded
as unpadded base64url. Direction keys are derived with `backstage call v1` or
`backstage result v1`, then separated from the old protocol with HKDF info
`backstage envelope v2`. Authenticated header bytes are UTF-8
`2:<from>:<n>:<r>`. `n` is a decimal uint64 replay counter. The daemon rejects
version 1 calls; v1 helpers remain only for compatibility vectors.

Random nonces avoid deterministic nonce reuse after restoring a counter
backup. Replay counters are still required: phone state is synchronously
fsynced, and CLI clients sharing a configuration serialize the complete
exchange and reload state under a lock. Never copy one live configuration to
multiple machines; pair a separate identity on each machine. A dead process's
lock requires checking the owner before removing that lock directory.

Calls contain `{id,deviceId,name,arguments,timeoutMs,expiresAt}` inside the encrypted
plaintext. The phone refuses missing or mismatched target IDs before dispatch,
including for direct LAN calls. The CLI also sends `expectedDeviceId` to the
relay, which rejects a name reassigned to different hardware before queueing.
Ambiguous names and aliases fail closed. Update the CLI and APK together;
older calls without a target ID are rejected.

The phone checks the deadline before executing, including after
waiting for the device lease. Results bind to that call ID and device ID.
An expired action never starts, but an already-running action may complete.
Startup retries retain the last good counter/revocation snapshot until it is
merged successfully; a failed merge prevents startup instead of discarding
the recovery state.

## Recovery and execution

The relay persists queued jobs before acknowledgment and results before
returning them. Ownership is checked on result submission and status reads.
Repeated job IDs must match the original device, owner and envelope. Queued
cancellation removes executable work; running cancellation records a request
and does not promise to undo or interrupt a side effect.

The device writes an execution receipt before invoking a tool and caches its
response before delivery. Reusing a call ID with changed arguments is
rejected. After an interrupted execution, its state is **unknown**, and the
same ID is not executed again. Completed responses are retained for 24 hours,
subject to a 16,384-receipt / 128 MiB cap (the oldest completed receipts are
evicted first; receipts of running or interrupted writes never are) and 8 MiB per result.
This is bounded deduplication, not a claim of exactly-once execution across
arbitrary failures. Pending encrypted responses retry delivery on reconnect.

## Local authority

LAN `POST /call` accepts authenticated encrypted envelopes. `/configure`,
`/session` and `/shutdown` require both a loopback connection and the local
administration token. A leaked old relay copy of that token cannot administer
the phone remotely. The app rotates its legacy local token on migration;
old relay token fields are removed on relay startup. `/health` exposes status.

Forgetting an agent requires acknowledgment from the daemon. Revoked key
fingerprints survive stale `.prev` configuration recovery. New pairing uses a
new key. Linked agents may receive full access, be paused, or get a temporary
QA permit restricted to one exact package. QA permits allow launch, package
information, crashes and filtered logs; they exclude arbitrary shell and UI
input. Permit changes are made locally in the app, persist across restarts,
and fail closed on write errors.

## Files and releases

Files use the BSF1 format with a fresh 32-byte key per file, 1 MiB authenticated
encryption chunks, and plaintext SHA-256 verification on the phone. Transport
uploads resume in 8 MiB requests. Partial and published files expire after
15 minutes. Spool and published storage each have a 2 GiB ceiling; published
files are capped at 256, uploads at 128 total / 8 per account. The relay's
single-file default is 512 MiB. Keep relay storage on a disk with headroom for
both pools, queued envelopes and receipts.

The old checked-in signing key is compromised because it remains recoverable
from Git history. It is removed from the current tree. Debug builds generate
an ignored development key; release builds require externally supplied
signing credentials. Existing installs signed with the old key need an
explicit migration plan; deleting the repository file does not revoke it.

Local workflow receipts contain decrypted arguments and results, may include
sensitive app data, and are written with owner-only permissions. Do not commit
or upload them without reviewing their contents. This alpha has not undergone
an independent cryptographic audit or Play distribution review.
