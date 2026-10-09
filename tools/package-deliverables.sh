#!/usr/bin/env bash
# Builds, tests and packages the deliverables into dist/:
#   dist/FlowForge-debug.apk     signed debug APK
#   dist/FlowForge-source.zip    source of the current commit (git archive: tracked files only,
#                                so no build outputs, local.properties or keystore.properties)
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew check assembleDebug --console=plain
mkdir -p dist
cp app/build/outputs/apk/debug/FlowForge-debug.apk dist/FlowForge-debug.apk
git archive --format=zip --prefix=FlowForge/ -o dist/FlowForge-source.zip HEAD
if [ -n "$(git status --porcelain)" ]; then
  echo "WARNING: uncommitted changes are NOT in FlowForge-source.zip (it contains HEAD only)." >&2
fi
sha256sum dist/FlowForge-debug.apk dist/FlowForge-source.zip
