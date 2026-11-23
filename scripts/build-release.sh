#!/usr/bin/env bash
# Production release build. Requires BACKSTAGE_KEYSTORE, BACKSTAGE_STORE_PASSWORD,
# BACKSTAGE_KEY_ALIAS, and BACKSTAGE_KEY_PASSWORD (never falls back to development.jks).
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
[[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] || { echo 'Set ANDROID_HOME to the installed Android SDK.' >&2; exit 1; }
for v in BACKSTAGE_KEYSTORE BACKSTAGE_STORE_PASSWORD BACKSTAGE_KEY_ALIAS BACKSTAGE_KEY_PASSWORD; do
  [[ -n "${!v:-}" ]] || { echo "Missing required release signing env var: $v (use scripts/build-debug.sh for debug builds)." >&2; exit 1; }
done
bash "$repo_dir/app/hosttest/run.sh"
cd "$repo_dir/app"
./gradlew :app:testReleaseUnitTest :app:assembleRelease --no-daemon
echo "$repo_dir/app/app/build/outputs/apk/release/app-release.apk"
