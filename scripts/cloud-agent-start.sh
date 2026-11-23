#!/usr/bin/env bash
# Cloud Agent start step. Nothing runs locally: the phone talks to the relay,
# and so does this VM. This only checks that the repo knows which relay to use
# and prints the linked devices so the agent's first tool call is the action.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ -f /opt/android-sdk/env.sh ]]; then
  # shellcheck disable=SC1091
  source /opt/android-sdk/env.sh
fi

if [[ -z "${BACKSTAGE_RELAY_URL:-}" && ! -f "$ROOT/config/backstage.json" ]]; then
  cat <<'EOF'
Android Backstage: no relay configured.
  Either commit config/backstage.json  ({"relayUrl": "...", "apiKey": "bsk_...", "device": "phone"})
  or set Cloud Agent secrets BACKSTAGE_RELAY_URL and BACKSTAGE_API_KEY.
  See docs/SETUP.md.
EOF
  exit 0
fi

node "$ROOT/cli/backstage.mjs" devices 2>&1 | head -n 40 || true
echo "Android Backstage ready: ./scripts/phone-mcp.sh --device <name> android.shell '{\"command\":\"id\"}'"
