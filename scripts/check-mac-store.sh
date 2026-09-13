#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p mac/.build
./scripts/swiftc.sh -O mac/Sources/Zap/Crypto.swift mac/Sources/Zap/Enrollment.swift mac/Sources/Zap/Store.swift mac/Tests/StoreCheck.swift -o mac/.build/store-check -lsqlite3
mac/.build/store-check
