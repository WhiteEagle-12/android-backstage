#!/usr/bin/env bash
# Thin wrapper over cli/backstage.mjs install / self-update.
#
#   ./scripts/ship-apk.sh path/to/app.apk
#   ./scripts/ship-apk.sh --device phone path/to/app.apk
#   ./scripts/ship-apk.sh --self [apk]
#   ./scripts/ship-apk.sh --launch com.example.app app.apk
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

DEVICE=""
SELF=0
LAUNCH=""
APK=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --device|-d) DEVICE="${2:-}"; shift 2 ;;
    --device=*|-d=*) DEVICE="${1#*=}"; shift ;;
    --self) SELF=1; shift ;;
    --launch) LAUNCH="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    -*) echo "unknown flag: $1" >&2; exit 2 ;;
    *) APK="$1"; shift ;;
  esac
done

if [[ -z "$APK" && "$SELF" == 1 ]]; then
  APK="$ROOT/app/app/build/outputs/apk/release/app-release.apk"
fi
if [[ -z "$APK" || ! -f "$APK" ]]; then
  echo "APK not found: ${APK:-<none>}" >&2
  exit 2
fi
APK="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"

CLI=(node "$ROOT/cli/backstage.mjs")
if [[ -n "$DEVICE" ]]; then
  CLI+=(--device "$DEVICE")
fi

if [[ "$SELF" == 1 ]]; then
  exec "${CLI[@]}" self-update "$APK"
fi

if [[ -n "$LAUNCH" ]]; then
  exec "${CLI[@]}" install "$APK" --launch "$LAUNCH"
fi
exec "${CLI[@]}" install "$APK"
