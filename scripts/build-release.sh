#!/usr/bin/env bash
# Non-debuggable build for daily use. Signs with BACKSTAGE_KEYSTORE when set,
# otherwise with the development key that installed phones already trust, so the
# update installs in place and passkey sign-in keeps working.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
[[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] || { echo 'Set ANDROID_HOME to the installed Android SDK.' >&2; exit 1; }
export BACKSTAGE_KEYSTORE="${BACKSTAGE_KEYSTORE:-$repo_dir/app/keystore/development.jks}"
export BACKSTAGE_STORE_PASSWORD="${BACKSTAGE_STORE_PASSWORD:-android}"
export BACKSTAGE_KEY_ALIAS="${BACKSTAGE_KEY_ALIAS:-development}"
export BACKSTAGE_KEY_PASSWORD="${BACKSTAGE_KEY_PASSWORD:-android}"
bash "$repo_dir/app/hosttest/run.sh"
cd "$repo_dir/app"
./gradlew :app:testReleaseUnitTest :app:assembleRelease --no-daemon
echo "$repo_dir/app/app/build/outputs/apk/release/app-release.apk"
