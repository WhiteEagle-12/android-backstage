# Where phone traffic is readable — decision record

Status: **decision required before the boundary moves again.** This note records what
is true today, what each option costs, and what the customer-facing text must say.

## What is true today

Two paths reach a phone. They have different privacy boundaries.

| Path | Who holds the link key | Who can read commands and results |
|---|---|---|
| **Local CLI / kit** (`cli/backstage.mjs` in an MCP stdio process on the user's own computer) | the user's computer and the phone | the user's computer, the phone |
| **Hosted gateway** (`gateway/`, the remote MCP server at `relay.android-backstage.com/work/mcp`) | the gateway host and the phone | the gateway host, the phone, and the assistant vendor that receives the tool result |

The blind relay (`relay/`) still forwards only ciphertext on `/v1` and stores only
ciphertext files. That has not changed. What changed with the shared remote
connection is that the *client* of the relay for hosted assistants is no longer the
user's own machine: it is the gateway process on the operator's machine. That
process holds each device's link key and connection credential, encrypts every
command, decrypts every result, and returns the plaintext to the assistant over
HTTPS (terminated by Cloudflare). The gateway operator can therefore read phone
content that hosted assistants request. Today the operator is the same person as the
only user, so this is a trust statement about the future product, not a present breach.

Anything that says the hosted setup hides phone content from the gateway is wrong and
has been removed. The relay claim ("the relay cannot read a command") stays, scoped to
the relay.

## Options

1. **Keep the gateway as the trusted endpoint (current).**
   Hosted ChatGPT, Codex Cloud, Cursor Cloud and similar clients can only speak
   HTTP MCP with OAuth; none can run the SPAKE2/AES client. A server somewhere must
   hold the link key. Cost: the operator is in the trust boundary; user-facing text
   must say so; the host needs hardening and a data-handling policy. Benefit: one
   enrollment per device, one authorization per assistant, works with every hosted
   assistant, multi-device access control lives in one place.

2. **Client-held keys for local assistants, gateway for hosted ones (hybrid).**
   Ship the kit CLI again for Codex CLI, Cursor desktop, Claude Code, etc., so that
   local assistants keep end-to-end encryption to the phone, while hosted assistants
   use the gateway. Cost: two setup paths again (the thing the redesign removed), per-
   machine link state, per-VM pairing for cloud runs of "local" tools, two places to
   revoke. Benefit: users who only run local assistants never expose plaintext to the
   operator.

3. **User-operated gateway.**
   Package the gateway (Docker/one-file) so the user runs it on their own always-on
   box or a personal VPS behind their own hostname. Cost: hosting, DNS/TLS, backups,
   uptime become the user's job; OAuth client registrations point at the user's URL;
   the public website can no longer be the single connection point. Benefit: the
   operator drops out of the trust boundary entirely while hosted assistants keep
   working. This is the natural long-term answer for privacy-sensitive users and the
   only option that satisfies both "hosted assistants" and "no third party reads".

4. **Encrypt results to the assistant vendor rather than the gateway.**
   Not possible: the assistant must see plaintext results to act on them. The best
   achievable boundary for hosted assistants is option 3.

## Decision

Do not move the boundary as part of this fix. Keep option 1 as the default hosted
path and make every disclosure match it. Keep the kit CLI available as a documented
alternative (option 2) for people who run local assistants and want the operator out
of the loop; it already works. Treat option 3 as the next architecture step and scope
it separately (Docker image of `gateway/front.mjs` + `gateway/server.mjs`, config for
the OAuth issuer URL, a migration script for devices/grants).

## Required disclosures (kept in sync with this design)

- Website and account pages: "The Backstage server relays your commands. It can see
  the commands your assistant sends and the results your phone returns. It stores the
  phone link for each device. The relay component and Cloudflare cannot read the
  encrypted phone envelopes; the server that talks to your assistant can."
- MCP server instructions: same statement in one sentence (in `gateway/server.mjs`).
- README / SECURITY / LANDING: scope the end-to-end claim to the CLI path; describe the
  gateway path separately.
- Never describe artifact uploads as end-to-end: the gateway receives the plaintext
  build, verifies its digest, then encrypts it for the phone.

## Operator obligations under option 1 (present)

- Audit rows hold tool names, device, timing and outcome only; no arguments or results.
- Artifacts expire after 45 minutes and are stored under the private state directory.
- Link files and grants stay `0600` in the private state directory; backups are local.
- Any future multi-user offering needs a written retention/deletion policy before
  onboarding anyone other than the operator.
