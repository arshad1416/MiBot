#!/bin/sh
# Builds MiBot.app into mac/build/ from the Swift package: compile, wrap in an app bundle, sign for this Mac.
#   mac/scripts/build-app.sh            release build
#   mac/scripts/build-app.sh --debug    debug build
# Needs Xcode or the Command Line Tools (Swift 5.10 or later). Nothing is installed; copy build/MiBot.app to
# /Applications yourself if you want it there.
set -eu
cd "$(dirname "$0")/.."
CONFIG=release
[ "${1:-}" = "--debug" ] && CONFIG=debug

swift build -c "$CONFIG" --product MiBot
BIN="$(swift build -c "$CONFIG" --show-bin-path)/MiBot"
VERSION="$(node -p "require('../package.json').version" 2>/dev/null || echo 0.1.0)"

APP=build/MiBot.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/MiBot"
[ -f Resources/AppIcon.icns ] && cp Resources/AppIcon.icns "$APP/Contents/Resources/"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleIdentifier</key><string>app.mibot.mac</string>
  <key>CFBundleName</key><string>MiBot</string>
  <key>CFBundleDisplayName</key><string>MiBot</string>
  <key>CFBundleExecutable</key><string>MiBot</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$VERSION</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <!-- The hub is usually reached over Tailscale by plain http (http://mac-mini:8787). -->
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsArbitraryLoads</key><true/></dict>
</dict>
</plist>
PLIST

# Ad-hoc signature: enough for this Mac to run it and show its notifications.
codesign --force --sign - "$APP"
echo "Built $(pwd)/$APP"
