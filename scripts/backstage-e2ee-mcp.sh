#!/usr/bin/env bash
# One-command Zero-Knowledge E2EE Local MCP Bridge for Android Backstage.
# All commands and replies are encrypted end-to-end (SPAKE2 + AES-256-GCM)
# between this machine and your phone; the relay sees only opaque ciphertext.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
exec node "$repo_dir/cli/backstage.mjs" mcp-bridge "$@"
