#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p mac/.build
./scripts/swiftc.sh mac/Sources/Zap/ClipboardImage.swift mac/Tests/ClipboardCheck.swift -o mac/.build/clipboard-check
mac/.build/clipboard-check
