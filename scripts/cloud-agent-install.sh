#!/usr/bin/env bash
# User-owned, repeatable Linux build toolchain. Device control uses the relay,
# never host adb. Requires Node, curl, unzip and tar; does not change /etc.
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
node -e 'if(Number(process.versions.node.split(".")[0])<20)process.exit(1)' || { echo 'Node 20+ is required.' >&2; exit 1; }
[[ "${BACKSTAGE_SKIP_SDK:-0}" == 1 ]] && exit 0
for dependency in curl unzip tar; do command -v "$dependency" >/dev/null || { echo "$dependency is required" >&2; exit 1; }; done
[[ "$(uname -s)" == Linux ]] || { echo 'This installer targets Linux. Set JAVA_HOME and ANDROID_HOME for your platform.' >&2; exit 1; }
tools_dir="${BACKSTAGE_TOOLS_DIR:-$repo_dir/.toolchains}"
mkdir -p "$tools_dir"
tools_dir="$(cd "$tools_dir" && pwd)"
sdk_dir="$tools_dir/android-sdk"
scratch_dir="$(mktemp -d)"
trap 'rm -rf "$scratch_dir"' EXIT
umask 022

jdk_dir="${JAVA_HOME:-$tools_dir/jdk-17}"
if [[ ! -x "$jdk_dir/bin/javac" ]]; then
  case "$(uname -m)" in x86_64) arch=x64;; aarch64) arch=aarch64;; *) echo 'Unsupported JDK architecture' >&2; exit 1;; esac
  curl -fsSL --retry 3 "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=$arch&image_type=jdk&os=linux&vendor=eclipse" -o "$scratch_dir/jdk.json"
  mapfile -t jdk_package < <(node --input-type=module - "$scratch_dir/jdk.json" <<'JS'
import fs from 'node:fs';
const pkg=JSON.parse(fs.readFileSync(process.argv[2],'utf8'))[0]?.binary?.package;
if(!/^https:\/\/[^\s]+$/.test(pkg?.link||'')||!/^[a-f0-9]{64}$/.test(pkg?.checksum||''))throw new Error('Invalid Adoptium package metadata');
console.log(pkg.link);console.log(pkg.checksum);
JS
  )
  [[ "${#jdk_package[@]}" == 2 ]] || exit 1
  curl -fsSL --retry 3 "${jdk_package[0]}" -o "$scratch_dir/jdk.tar.gz"
  printf '%s  %s\n' "${jdk_package[1]}" "$scratch_dir/jdk.tar.gz" | sha256sum -c -
  mkdir -p "$jdk_dir"
  tar -xzf "$scratch_dir/jdk.tar.gz" --strip-components=1 -C "$jdk_dir"
fi
export JAVA_HOME="$jdk_dir"
export PATH="$JAVA_HOME/bin:$PATH"

sdkmanager="$sdk_dir/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$sdkmanager" ]]; then
  zip=commandlinetools-linux-15859902_latest.zip
  curl -fsSL --retry 3 "https://dl.google.com/android/repository/$zip" -o "$scratch_dir/sdk.zip"
  printf '%s  %s\n' 4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583 "$scratch_dir/sdk.zip" | sha256sum -c -
  unzip -q "$scratch_dir/sdk.zip" -d "$scratch_dir/extracted"
  mkdir -p "$sdk_dir/cmdline-tools"
  mv "$scratch_dir/extracted/cmdline-tools" "$sdk_dir/cmdline-tools/latest"
fi
if [[ ! -d "$sdk_dir/platforms/android-35" || ! -d "$sdk_dir/build-tools/35.0.0" || ! -d "$sdk_dir/platform-tools" ]]; then
  # Installing the SDK includes accepting its standard SDK package licenses.
  set +o pipefail
  yes | "$sdkmanager" --sdk_root="$sdk_dir" --licenses >/dev/null
  license_status="${PIPESTATUS[1]}"
  set -o pipefail
  [[ "$license_status" == 0 ]] || exit "$license_status"
  "$sdkmanager" --sdk_root="$sdk_dir" 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
fi
{
  printf 'export JAVA_HOME=%q\n' "$jdk_dir"
  printf 'export ANDROID_HOME=%q\n' "$sdk_dir"
  printf 'export ANDROID_SDK_ROOT=%q\n' "$sdk_dir"
  printf 'export GRADLE_USER_HOME=%q\n' "$tools_dir/gradle"
  printf 'export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"\n'
} > "$tools_dir/env.sh"
"$JAVA_HOME/bin/javac" -version
printf 'Toolchain ready. Source %s/env.sh, then run scripts/build-debug.sh.\n' "$tools_dir"
