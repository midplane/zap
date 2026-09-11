#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

./scripts/build-mac.sh
(cd android && ./gradlew :app:assembleDebug)

staging=$(mktemp -d "${TMPDIR:-/tmp}/zap-package.XXXXXX")
trap 'rm -rf "$staging"' EXIT
ditto dist/Zap.app "$staging/Zap.app"
ln -s /Applications "$staging/Applications"

dmg="dist/Zap-macOS-$(uname -m).dmg"
hdiutil create -volname Zap -srcfolder "$staging" -format UDZO -ov "$dmg"
hdiutil verify "$dmg"
cp android/app/build/outputs/apk/debug/app-debug.apk dist/Zap-android-debug.apk
printf '\nPackaged %s and dist/Zap-android-debug.apk\n' "$dmg"
