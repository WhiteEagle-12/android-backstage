# Android Backstage

*Give your AI agent a real Android phone. Keep using yours.*

Android Backstage is a zero-dependency toolkit that lets cloud agents (Cursor,
Claude Code, any MCP client) operate real Android devices: run shell commands,
read the UI tree, tap, type, install apps, take screenshots. All of it happens
on a hidden second screen, so the phone in your hand stays yours. It works with
the screen off and the phone locked.

**Ship faster.** Have your cloud agent build on its VM, install straight onto
your device, launch it, and read the crash log back, without you touching a
cable. Prototyping, debugging, and QA on real hardware become part of the
agent's loop instead of your afternoon.

**Unlock Android-only apps.** Some workflows only exist on a phone. Give your
agents 24/7 access to those apps and the context inside them, from anywhere.

**Setup in three steps.** Install the app. Point your agent at the repo. Type
the link code. Done.

**Encrypted to your phone.** Commands and results are encrypted between the
Backstage client and your phone; the relay only forwards ciphertext and can't
read or issue a command. With the local CLI that client is your own computer.
With the hosted connection used by ChatGPT, Codex, Cursor and other assistants,
that client is the Backstage server, which therefore can see what your assistant
asks and what your phone answers. Self-host the whole thing, or use ours. Every
line is source-available (`FSL-1.1-ALv2`, converting to Apache-2.0 after 2 years).

## Pricing

| | Personal | Pro | Team |
| --- | --- | --- | --- |
| Devices on `relay.android-backstage.com` | 1 | 5 | 25 (more on request) |
| Self-hosted relay | unlimited | unlimited | unlimited |
| Parallel virtual displays | 1 | Multi | Multi |
| Price | $0 | $9 / month ($7/mo yearly) | $39 / month |

See `android-backstage.com/#pricing` for current hosted plan details and limits.

## What people ask first

**Does it need root, Shizuku, or a computer?** No. The app pairs with your
phone's own Wireless debugging once and starts its own helper. Nothing else is
installed.

**Does it take over my phone?** No. The agent works on a headless virtual
display. You keep scrolling. Audio still plays from the phone, so "play this
song" works without you seeing anything.

**Can the relay (or you) see my commands?** With the Local E2EE Bridge or CLI, no: your own computer holds the link key from SPAKE2 pairing, and the relay forwards opaque AES-256-GCM ciphertext only (seeing device names, timing, and sizes). With the hosted MCP server (`relay.android-backstage.com/work/mcp`) used by cloud assistants like ChatGPT and Claude, the hosted gateway holds the sealed link key and terminates the envelope on your behalf with zero data retention (no persistent tool history; temporary files live in RAM/short-lived storage). See `docs/PRIVACY-BOUNDARY.md`.

**What Android version?** 11 or newer.

**Can I run the relay myself?** Yes. One Node process or one Docker container.
`docs/DEPLOY.md`.
