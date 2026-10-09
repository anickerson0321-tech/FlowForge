#!/usr/bin/env bash
# Installs the "bootstrap" Android build toolchain used when Google's SDK hosts
# (dl.google.com / maven.google.com) are unreachable.
#
# Provides: aapt2 (Android 14 build), dx, zipalign, apksigner from the Ubuntu archive.
# The Android framework jars (API 36 for compiling, API 34 for resource linking) are
# downloaded by Gradle from Maven Central (org.robolectric:android-all, built from AOSP tags).
#
# If you have the official Android SDK (platforms/android-36 + build-tools 36.x), you do not
# need this script: set sdk.dir in local.properties or ANDROID_HOME and Gradle uses it instead.
set -euo pipefail

PKGS=(aapt dalvik-exchange apksigner zipalign)

if ! command -v apt-get >/dev/null 2>&1; then
  echo "apt-get not available. Install the official Android SDK instead (see BUILD_INSTRUCTIONS.md)." >&2
  exit 1
fi

SUDO=""
if [ "$(id -u)" -ne 0 ]; then SUDO="sudo"; fi

missing=()
for p in "${PKGS[@]}"; do
  dpkg -s "$p" >/dev/null 2>&1 || missing+=("$p")
done

if [ ${#missing[@]} -gt 0 ]; then
  echo "Installing: ${missing[*]}"
  $SUDO apt-get update -qq
  DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq "${missing[@]}"
fi

echo "Toolchain check:"
for t in /usr/bin/aapt2 /usr/lib/android-sdk/build-tools/debian/dx /usr/bin/zipalign /usr/bin/apksigner; do
  if [ -x "$t" ]; then echo "  ok   $t"; else echo "  MISSING $t" >&2; exit 1; fi
done
aapt2 version 2>&1 | sed 's/^/  /'
echo "Done. Build with: ./gradlew assembleDebug"
