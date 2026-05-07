#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="${1:-0.1.0}"
PACK_DIR="$ROOT/agent-pack/java-tuning-agent"
OUT_DIR="$ROOT/target/agent-pack"
STAGE_DIR="$OUT_DIR/stage"
ZIP="$OUT_DIR/java-tuning-agent-agent-pack-$VERSION.zip"

"$ROOT/scripts/validate-agent-pack.sh"

rm -rf "$OUT_DIR"
mkdir -p "$STAGE_DIR"
cp -R "$PACK_DIR" "$STAGE_DIR/"

find "$STAGE_DIR/java-tuning-agent" -type f \
  \( -name '*.md' -o -name '*.json' -o -name '*.toml' -o -name '*.sh' -o -name '*.yaml' \) \
  -exec perl -0pi -e \
  "s/java-tuning-agent-0\\.1\\.0\\.jar/java-tuning-agent-$VERSION.jar/g; s/\"version\": \"0\\.1\\.0\"/\"version\": \"$VERSION\"/g" {} +

(
  cd "$STAGE_DIR"
  zip -qr "$ZIP" java-tuning-agent
)

echo "$ZIP"
