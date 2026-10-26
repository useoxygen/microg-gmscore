#!/bin/sh
# SPDX-FileCopyrightText: 2026 Cyclon
# SPDX-License-Identifier: Apache-2.0
set -eu
out=${1:?usage: build.sh /absolute/output/DctPeer.app}
case "$out" in /*.app) ;; *) echo 'Output must be an absolute .app directory' >&2; exit 2;; esac
mkdir -p "$out/Contents/MacOS"
cat > "$out/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>ai.cyclon.lab.dctpeer</string>
<key>CFBundleExecutable</key><string>DctPeer</string>
<key>CFBundleName</key><string>Cyclon DCT test peer</string>
<key>CFBundleVersion</key><string>1</string>
<key>NSBluetoothAlwaysUsageDescription</key><string>Observe a specified dev-phone DCT test advertisement and optionally test its L2CAP channel.</string>
</dict></plist>
PLIST
swiftc "$(dirname "$0")/main.swift" -framework CoreBluetooth -o "$out/Contents/MacOS/DctPeer"
codesign --force --sign - "$out"
