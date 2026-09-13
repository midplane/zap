#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."

compiler="$(xcrun --find swiftc)"
sdk="$(xcrun --sdk macosx --show-sdk-path)"
build="$PWD/mac/.build"
mkdir -p "$build/compiler-cache"
args=(-parse-as-library -swift-version 5 -target "$(uname -m)-apple-macosx14.0"
  -sdk "$sdk" -module-cache-path "$build/compiler-cache")

includes="$(dirname "$(dirname "$compiler")")/include/swift"
module_map="$includes/module.modulemap"
bridging_map="$includes/bridging.modulemap"
if [ -f "$module_map" ] && [ -f "$bridging_map" ] &&
  cmp -s <(sed '/^[[:space:]]*\/\//d' "$module_map") <(sed '/^[[:space:]]*\/\//d' "$bridging_map"); then
  printf 'Using a local overlay for duplicate SwiftBridging definitions.\n' >&2
  empty="$build/empty.modulemap"
  overlay="$build/swift-overlay.yaml"
  : > "$empty"
  cat > "$overlay" <<YAML
version: 0
roots:
  - type: file
    name: '${module_map//\'/\'\'}'
    external-contents: '${empty//\'/\'\'}'
YAML
  args+=(-vfsoverlay "$overlay")
fi

exec "$compiler" "${args[@]}" "$@"
