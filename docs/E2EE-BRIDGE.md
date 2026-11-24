# Hosted Gateway vs. Zero-Knowledge E2EE Local Bridge

Android Backstage supports two ways to connect MCP clients (Claude, Cursor, Codex, etc.):

| Mode | Best for | Who sees plaintext? | Setup |
| --- | --- | --- | --- |
| **Hosted MCP Gateway** (`/work/mcp`) | Zero-install OAuth from any cloud or desktop client | Phone + hosted gateway (zero call-history retention) | Sign in in Backstage app and approve the agent |
| **Self-Hosted Relay + Local E2EE Bridge** (`mcp-bridge`) | Privacy-strict setups where our hosted servers are never in the path | **Only your machine and your phone** (SPAKE2 + AES-256-GCM) | Run your own `relay/server.mjs`, pair once with CLI, run one command |

> **Note:** Our hosted relay at `relay.android-backstage.com` runs with `RELAY_ALLOW_PUBLIC_PAIR=0` (it serves signed-in hosted accounts only). To use the local E2EE CLI bridge, run your own open-source relay (`relay/server.mjs`) with `RELAY_ALLOW_PUBLIC_PAIR=1` (or with your own relay's API key from `RELAY_DATA_DIR/first-api-key.txt`).

## Quick Start: Self-Hosted E2EE Local Bridge

1. Start your own relay (see [`docs/SETUP.md`](SETUP.md)):

```bash
RELAY_ALLOW_PUBLIC_PAIR=1 node relay/server.mjs
```

2. Pair your local machine directly with the phone through your relay (one time):

```bash
node cli/backstage.mjs pair --relay-url https://your-relay.example --name phone
```

Enter the printed link code in the Android Backstage app on your phone (choose **Self-hosted relay**).

3. Print a ready-to-paste MCP client config:

```bash
./scripts/backstage-e2ee-mcp.sh --device phone --print-config
```

4. Or run the stdio MCP bridge directly:

```bash
./scripts/backstage-e2ee-mcp.sh --device phone
```

When on the same LAN or Tailnet, the bridge uses the direct encrypted route automatically and falls back to the opaque ciphertext relay whenever away from home.
