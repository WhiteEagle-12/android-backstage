# Android Backstage

Let an AI agent operate your real Android phone on a hidden virtual display while you keep using it.

Android Backstage is one APK and one lightweight Node relay. The APK pairs once with your phone's own Wireless debugging and runs a `shell`-user (`uid 2000`) daemon that drives UIs on a **private headless virtual display**, reads compact accessibility trees, records MP4 clips, installs APKs, and enforces an on-device **Privacy Fence** so any sensitive apps you block (banking, password vaults, galleries, etc.) stay off-limits to agents.

No root. No Shizuku. No USB cable. No PC required. Works over Wi-Fi and 5G cellular.

```text
Hosted OAuth MCP:   agent (ChatGPT / Claude / Cursor) ─ HTTPS ─► gateway ─ E2EE ─► relay ◄─ outbound HTTPS ─ phone daemon
Zero-Knowledge E2EE: agent (Claude Code / Cursor CLI)  ─ stdio ─► local CLI ─ E2EE ─► relay (or direct LAN) ◄─ phone daemon
```

- **Website & setup:** [android-backstage.com](https://android-backstage.com/)
- **Live benchmarks:** [android-backstage.com/benchmarks](https://android-backstage.com/benchmarks/)
- **Privacy boundary:** [`docs/PRIVACY-BOUNDARY.md`](docs/PRIVACY-BOUNDARY.md) · [`docs/SECURITY.md`](docs/SECURITY.md)

## Two Ways to Connect

### 1. Hosted OAuth MCP (1-click, works with cloud & desktop agents)

1. **Phone:** Install the latest APK from [android-backstage.com](https://android-backstage.com/) or [GitHub Releases](https://github.com/WhiteEagle-12/android-backstage/releases), sign in, and approve Wireless debugging once.
2. **Agent:** Add the MCP server URL:

```text
https://relay.android-backstage.com/work/mcp
```

[<img src="https://img.shields.io/badge/Add_to-Claude-000000?style=flat-square&logo=claude&logoColor=white" alt="Add to Claude">](https://claude.ai/customize/connectors?modal=add-custom-connector&connectorName=Android%20Backstage&connectorUrl=https%3A%2F%2Frelay.android-backstage.com%2Fwork%2Fmcp)
[<img src="https://img.shields.io/badge/Add_to-Cursor-000000?style=flat-square&logo=cursor" alt="Add to Cursor">](cursor://anysphere.cursor-deeplink/mcp/install?name=Android%20Backstage&config=eyJ1cmwiOiJodHRwczovL3JlbGF5LmFuZHJvaWQtYmFja3N0YWdlLmNvbS93b3JrL21jcCJ9)
[<img src="https://img.shields.io/badge/Add_to-VS_Code-0098FF?style=flat-square&logo=visualstudiocode&logoColor=white" alt="Add to VS Code">](https://vscode.dev/redirect/mcp/install?name=android-backstage&config=%7B%22type%22%3A%22http%22%2C%22url%22%3A%22https%3A%2F%2Frelay.android-backstage.com%2Fwork%2Fmcp%22%7D)

```bash
claude mcp add --transport http --scope user android-backstage https://relay.android-backstage.com/work/mcp
codex mcp add android-backstage --url https://relay.android-backstage.com/work/mcp && codex mcp login android-backstage
gemini mcp add --transport http android-backstage https://relay.android-backstage.com/work/mcp
```

3. **Approve:** Enter your Backstage email, check the digits on your phone, and tap **Allow**.

> **Privacy note:** On the hosted MCP path, the gateway holds vault-sealed device link keys and decrypts commands/results in memory to forward them to cloud assistants (Cloudflare terminates TLS on assistant↔gateway traffic, and the blind relay forwards opaque ciphertext envelopes for phone calls). For zero-knowledge E2EE where only your machine and phone hold keys, use the Local E2EE Bridge below. On-device blocked apps are always enforced inside the phone daemon (note that enabling optional developer shell access bypasses Blocked apps).

### 2. Self-Hosted Relay & Zero-Knowledge Local Bridge

Want the entire stack on your own hardware so our hosted servers are never in the path? Run your own source-available relay (`relay/server.mjs`) and use the local stdio E2EE bridge (SPAKE2 + AES-256-GCM; link keys stay strictly on your machine and phone):

```bash
RELAY_ALLOW_PUBLIC_PAIR=1 node relay/server.mjs
node cli/backstage.mjs pair --relay-url https://your-relay.example --name phone
./scripts/backstage-e2ee-mcp.sh --device phone --print-config
```

See [`docs/SETUP.md`](docs/SETUP.md) and [`docs/E2EE-BRIDGE.md`](docs/E2EE-BRIDGE.md) for self-hosting instructions.

## Key Capabilities

- **Hidden Virtual Display + Handoff:** Agents open, read, tap, type, scroll, and record MP4 clips on a private display while your physical screen stays untouched. Hand off to the real screen only when you want.
- **Fast & Token-Light:** Consolidated 23-tool MCP surface (~2.1k upfront tokens, including opt-in on-device `android_shell`), Binder IPC + pooled `UiAutomation`, compact 1-line screen output with auto-diff, and scaled WebP screenshots.
- **On-Device Privacy Fence & Live Peek:** Hard-block sensitive apps (banking, vaults, gallery) directly inside the phone daemon; glance at backstage activity live, approve sensitive actions with Prepare & Confirm, or pause/revoke any agent in one tap.
- **Real-Device Dev & QA Loop:** Build APKs, install onto real hardware without a USB cable, launch backstage, pull crash logs/ANRs/frame-jank stats, and grab MP4 failure recordings in a single call.
- **Self-Healing Connectivity:** Auto-approves saved password-protected Wi-Fi access points, survives Wi-Fi → 5G handoffs in under a second, and works outbound-only with no open ports.

## Repository Layout

| Path | What |
| --- | --- |
| `app/` | Android app + `uid 2000` daemon (`scripts/build-debug.sh`) |
| `app/hosttest/` | Host-JDK self-tests for the embedded ADB & SPAKE2 client (`bash app/hosttest/run.sh`) |
| `relay/` | Zero-dependency Node blind ciphertext relay (`node --test relay/test/*.test.mjs`) |
| `gateway/` | Multi-user OAuth & Streamable HTTP MCP gateway (`node --test gateway/test/*.test.mjs`) |
| `cli/` | `android-backstage` CLI & zero-knowledge E2EE stdio MCP bridge (`node --test cli/test/*.test.mjs`) |
| `deploy/` | Docker Compose + zero-extra-cost GCP GCE / Cloudflare R2 deployment templates |
| `docs/` | `ARCHITECTURE.md`, `SECURITY.md`, `PRIVACY-BOUNDARY.md`, `SETUP.md`, `E2EE-BRIDGE.md`, `TOOL-CONTRACT.md` |

## Requirements & License

- **Phone:** Android 11+ (Wireless debugging pairing).
- **Relay / Gateway / CLI:** Node.js 22+ (or Docker).
- **License:** [FSL-1.1-ALv2](LICENSE) (Source-available — free to inspect, modify, and self-host for personal or internal company use; competing commercial offerings prohibited; each release converts to Apache-2.0 after 2 years).
