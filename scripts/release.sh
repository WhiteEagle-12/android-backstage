#!/usr/bin/env bash
# Build, test and sign a production release of the Backstage app from the committed HEAD.
#   scripts/release.sh             build on hx99g, sign here, check the certificate
#   scripts/release.sh --publish   then publish it to GitHub Releases and offer it to every phone
# The signing key stays on this PC in ~/.local/share/android-backstage/keystore (release.jks + release.env).
# Bump versionCode/versionName in app/app/build.gradle and BuildInfo.VERSION first.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
keydir="${BACKSTAGE_KEY_DIR:-$HOME/.local/share/android-backstage/keystore}"
# Certificate of every production install. A different key cannot update an installed app.
cert=a1704d7764be287a1b8cc2c62f9957e19af2e3c2558c8105c0975a7920bd6956
publish=0
case "${1:-}" in --publish) publish=1 ;; "") ;; *) echo "usage: scripts/release.sh [--publish]" >&2; exit 2 ;; esac
cd "$repo"
[[ -f "$keydir/release.env" ]] || { echo "No signing key at $keydir." >&2; exit 2; }
[[ -z "$(git status --porcelain -- app)" ]] || { echo "Commit the app changes first, so the release matches a commit." >&2; exit 2; }
bt="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/ | sort -V | tail -1)"
out="$(mktemp -d)"; trap 'rm -rf "$out"' EXIT

# Tests, lint and an APK signed with the public development key; the production key never leaves this PC.
hxbuild -C "$repo" -a app/app/build/outputs/apk/release/app-release.apk -o "$out" -- bash -c \
  'bash app/hosttest/run.sh && cd app && BACKSTAGE_KEYSTORE="$PWD/keystore/development.jks" BACKSTAGE_STORE_PASSWORD=android BACKSTAGE_KEY_ALIAS=development BACKSTAGE_KEY_PASSWORD=android ./gradlew :app:testReleaseUnitTest :app:lintVitalRelease :app:assembleRelease --no-daemon'
raw="$(find "$out" -name app-release.apk | head -1)"
[[ -f "$raw" ]] || { echo "The build made no APK." >&2; exit 1; }

"$bt/zipalign" -f -p 4 "$raw" "$out/aligned.apk"
( set -a; . "$keydir/release.env"; set +a
  "$bt/apksigner" sign --ks "$BACKSTAGE_KEYSTORE" --ks-pass env:BACKSTAGE_STORE_PASSWORD \
    --ks-key-alias "$BACKSTAGE_KEY_ALIAS" --key-pass env:BACKSTAGE_KEY_PASSWORD --out "$out/signed.apk" "$out/aligned.apk" )
got="$("$bt/apksigner" verify --print-certs "$out/signed.apk" | sed -n 's/.*certificate SHA-256 digest: //p' | sort -u)"
[[ "$got" == "$cert" ]] || { echo "Signed with the wrong key ($got)." >&2; exit 1; }

dest="$repo/app/app/build/outputs/apk/release/app-release.apk"
mkdir -p "$(dirname "$dest")"; cp "$out/signed.apk" "$dest"
"$bt/aapt2" dump badging "$dest" > "$out/badging"
grep -m1 -o "versionCode='[0-9]*' versionName='[^']*'" "$out/badging"
echo "Signed: $dest (sha256 $(sha256sum "$dest" | cut -d' ' -f1), commit $(git rev-parse --short HEAD))"
if [[ "$publish" == 1 ]]; then bash "$repo/scripts/publish-apk.sh" --github-release "$dest"; fi
