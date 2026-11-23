# Android Backstage CLI

Zero-dependency Node ≥ 20 script used by Cloud Agents and by
`scripts/phone-mcp.sh` / `scripts/ship-apk.sh`. Encrypts every tool call
before it reaches the relay.

```bash
node cli/backstage.mjs --relay-url http://127.0.0.1:18421 --api-key bsk_… health
node cli/backstage.mjs pair --name phone
node cli/backstage.mjs devices
node cli/backstage.mjs -d phone call android.shell '{"requestId":"shell-001","command":"id"}'
node cli/backstage.mjs -d phone install app.apk --launch com.example.app
node cli/backstage.mjs -d phone pull /sdcard/Download/video.mp4 ./video.mp4
node cli/backstage.mjs mcp --device phone
```

Config, first match wins: flags → `BACKSTAGE_RELAY_URL` / `BACKSTAGE_API_KEY` /
`BACKSTAGE_DEVICE` / `BACKSTAGE_CONFIG` → `config/backstage.json` next to the
repo root.

`pair` prints a `XXXX-YYYYYY` code. The app types that; SPAKE2 builds a
link key that never leaves the CLI and the phone.

Exit 1 on a tool error, 2 on usage. Timeouts come from the local tool
budget table. Install / push / self-update encrypt the file (BSF1), upload
ciphertext, then call the URL-based tool with `fileKey` and `sha256`.
`mcp` is a stdio MCP server. `mcp-config` prints the Cursor stdio block.

The current API is documented in [the tool contract](../docs/TOOL-CONTRACT.md). Set `BACKSTAGE_DEVELOPER_TOOLS=1` to include developer and maintenance tools in the local MCP list.

`pull` verifies and saves chunks up to 1 MiB. Run the same command to resume an interrupted download. It checks the phone file version and saved bytes before continuing and never overwrites an existing local file. A changed source requires a new local path. The adjacent `.backstage-part` and `.backstage-download.json` files hold progress until completion; preserve both to resume. If a process was killed while holding its lock, inspect the reported process before removing that lock directory.
