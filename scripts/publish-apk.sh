#!/usr/bin/env bash
# Offer an APK to every phone: the Backstage app sees it and updates in one tap.
#   scripts/publish-apk.sh [--url https://...] [--github-release] [path/to/app-release.apk]
#
# With --github-release, creates/updates GitHub Release v<versionName> with the
# APK + SHA256SUMS.txt and writes its zero-egress download URL into latest.json.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
external_url=""
github_release=0
apk=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url) external_url="${2:-}"; shift 2 ;;
    --url=*) external_url="${1#--url=}"; shift ;;
    --github-release) github_release=1; shift ;;
    -h|--help)
      echo "usage: scripts/publish-apk.sh [--url https://...] [--github-release] [path/to/app-release.apk]"
      exit 0 ;;
    *) apk="$1"; shift ;;
  esac
done

apk="${apk:-$repo_dir/app/app/build/outputs/apk/release/app-release.apk}"
dir="${BACKSTAGE_APP_RELEASES:-$HOME/.local/share/android-backstage/app-releases}"
[[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 2; }
aapt="$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/aapt2 | sort -V | tail -1)"
all="$("$aapt" dump badging "$apk")"
badging="$(head -1 <<<"$all")"
code="$(sed -E "s/.*versionCode='([0-9]+)'.*/\1/" <<<"$badging")"
name="$(sed -E "s/.*versionName='([^']+)'.*/\1/" <<<"$badging")"
package="$(sed -E "s/^package: name='([^']+)'.*/\1/" <<<"$badging")"
[[ "$package" == com.androidbackstage.app ]] || { echo "Not the Backstage app: $package" >&2; exit 2; }
if grep -q "application-debuggable" <<<"$all"; then echo "Refusing to publish a debuggable build" >&2; exit 2; fi

mkdir -p "$dir"; chmod 700 "$dir"
file="android-backstage-$name.apk"
install -m 0644 "$apk" "$dir/$file"
sha="$(sha256sum "$dir/$file" | cut -d' ' -f1)"
size="$(stat -c %s "$dir/$file")"

if [[ "$github_release" == "1" ]]; then
  tag="v$name"
  sums="$(mktemp "$dir/.sums.XXXXXX")"
  printf '%s  %s\n' "$sha" "$file" >"$sums"
  repo_slug="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
  if gh release view "$tag" >/dev/null 2>&1; then
    gh release upload "$tag" "$dir/$file" "$sums#SHA256SUMS.txt" --clobber
  else
    gh release create "$tag" "$dir/$file" "$sums#SHA256SUMS.txt" --title "Android Backstage $name" --notes "Signed release APK (versionCode $code). SHA-256: \`$sha\`"
  fi
  rm -f "$sums"
  external_url="https://github.com/$repo_slug/releases/download/$tag/$file"
fi

if [[ -n "$external_url" && "$external_url" != https://* ]]; then
  echo "External update URL must start with https://" >&2; exit 2
fi

tmp="$(mktemp "$dir/.latest.XXXXXX")"
if [[ -n "$external_url" ]]; then
  printf '{"versionCode":%s,"versionName":"%s","file":"%s","sha256":"%s","size":%s,"url":"%s"}\n' \
    "$code" "$name" "$file" "$sha" "$size" "$external_url" >"$tmp"
else
  printf '{"versionCode":%s,"versionName":"%s","file":"%s","sha256":"%s","size":%s}\n' \
    "$code" "$name" "$file" "$sha" "$size" >"$tmp"
fi
chmod 0644 "$tmp"; mv "$tmp" "$dir/latest.json"
ls -t "$dir"/*backstage-*.apk | tail -n +4 | xargs -r rm -f
echo "Published $name ($code) $sha${external_url:+ -> $external_url}"
