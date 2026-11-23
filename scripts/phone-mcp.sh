#!/usr/bin/env bash
# Thin wrapper: phone-mcp.sh [--device NAME] <tool> [json-args]
#   ./scripts/phone-mcp.sh --device phone android.shell '{"command":"id"}'
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

DEVICE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --device|-d)
      DEVICE="${2:-}"
      shift 2
      ;;
    --device=*|-d=*)
      DEVICE="${1#*=}"
      shift
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "unknown flag: $1" >&2
      echo "usage: phone-mcp.sh [--device NAME] <tool> [json-args]" >&2
      exit 2
      ;;
    *)
      break
      ;;
  esac
done

if [[ $# -lt 1 ]]; then
  echo "usage: phone-mcp.sh [--device NAME] <tool> [json-args]" >&2
  echo "example: phone-mcp.sh --device phone android.shell '{\"command\":\"id\"}'" >&2
  exit 2
fi

TOOL="$1"
JSON="${2-}"

if [[ -n "$DEVICE" ]]; then
  if [[ -n "$JSON" ]]; then
    exec node "$ROOT/cli/backstage.mjs" --device "$DEVICE" call "$TOOL" "$JSON"
  else
    exec node "$ROOT/cli/backstage.mjs" --device "$DEVICE" call "$TOOL"
  fi
fi

if [[ -n "$JSON" ]]; then
  exec node "$ROOT/cli/backstage.mjs" call "$TOOL" "$JSON"
fi
exec node "$ROOT/cli/backstage.mjs" call "$TOOL"
