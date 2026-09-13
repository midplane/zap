#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p mac/.build
./scripts/swiftc.sh mac/Sources/Zap/Crypto.swift mac/Tests/CryptoCheck.swift -o mac/.build/crypto-check
mac/.build/crypto-check protocol/crypto-vector.json
