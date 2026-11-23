# Hosted Gateway vs. Zero-Knowledge E2EE Local Bridge

Android Backstage supports two ways to connect MCP clients (Claude, Cursor, Codex, etc.):

| Mode | Best for | Who sees plaintext? | Setup |
| --- | --- | --- | --- |
| **Hosted MCP Gateway** (`/work/mcp`) | Zero-install OAuth from any cloud or desktop client | Phone + hosted gateway | Sign in in Backstage app and approve the agent |
| **Zero-Knowledge E2EE Local Bridge** (`mcp-bridge`) | Privacy-strict local setups where the relay must never see plaintext | **Only your local machine and your phone** (SPAKE2 + AES-256-GCM) | Pair once with CLI, run one command |

## Quick Start: E2EE Local Bridge

1. Pair your local machine directly with the phone (one time):

```bash
node cli/backstage.mjs pair --name phone
```

Enter the printed link code in the Android Backstage app on your phone.

2. Print a ready-to-paste MCP client config:

```bash
./scripts/backstage-e2ee-mcp.sh --device phone --print-config
```

3. Or run the stdio MCP bridge directly:

```bash
./scripts/backstage-e2ee-mcp.sh --device phone
```

When on the same LAN or Tailnet, the bridge uses the direct encrypted route automatically and falls back to the opaque ciphertext relay whenever away from home.
