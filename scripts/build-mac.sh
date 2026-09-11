#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "${ZAP_USE_PREBUILT:-0}" != "1" ]; then
  swift build --package-path mac -c release
fi
app="dist/Zap.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "${ZAP_BINARY:-mac/.build/release/Zap}" "$app/Contents/MacOS/Zap"
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>dev.zap.mac</string>
<key>CFBundleName</key><string>Zap</string>
<key>CFBundleExecutable</key><string>Zap</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.1.0</string>
<key>CFBundleVersion</key><string>1</string>
<key>LSMinimumSystemVersion</key><string>14.0</string>
<key>LSUIElement</key><true/>
<key>NSHighResolutionCapable</key><true/>
<key>NSPasteboardUsageDescription</key><string>Zap remembers copied text and images in your private clipboard history.</string>
</dict></plist>
PLIST
codesign --force --sign "${ZAP_SIGN_IDENTITY:--}" "$app"
printf 'Built %s\n' "$app"
