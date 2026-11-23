#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
command -v javac >/dev/null || { echo 'Set JAVA_HOME and PATH to JDK 17.' >&2; exit 1; }
[[ -n "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]] || { echo 'Set ANDROID_HOME to the installed Android SDK.' >&2; exit 1; }
umask 077
mkdir -p "$repo_dir/app/keystore"
if [[ ! -f "$repo_dir/app/keystore/development.jks" ]]; then
  keytool -genkeypair -keystore "$repo_dir/app/keystore/development.jks" \
    -alias development -storepass android -keypass android -keyalg RSA -keysize 3072 \
    -validity 3650 -dname 'CN=Backstage Development' -noprompt
fi
bash "$repo_dir/app/hosttest/run.sh"
cd "$repo_dir/app"
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon
