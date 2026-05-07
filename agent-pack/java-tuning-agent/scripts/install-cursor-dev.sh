#!/usr/bin/env bash
set -euo pipefail

PACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="${1:?Usage: scripts/install-cursor-dev.sh /path/to/project /absolute/path/to/java-tuning-agent/pom.xml}"
POM_PATH="${2:?Usage: scripts/install-cursor-dev.sh /path/to/project /absolute/path/to/java-tuning-agent/pom.xml}"

if [[ ! -f "$POM_PATH" ]]; then
  echo "pom.xml not found: $POM_PATH" >&2
  exit 1
fi

mkdir -p "$PROJECT_DIR/.cursor/rules" "$PROJECT_DIR/.cursor/skills"
cp "$PACK_DIR/adapters/cursor/rules/java-tuning-agent-mcp.mdc" "$PROJECT_DIR/.cursor/rules/"
rm -rf "$PROJECT_DIR/.cursor/skills/java-tuning-agent-workflow"
cp -R "$PACK_DIR/adapters/cursor/skills/java-tuning-agent-workflow" "$PROJECT_DIR/.cursor/skills/"

cat > "$PROJECT_DIR/.cursor/mcp.json" <<EOF
{
  "mcpServers": {
    "java-tuning-agent": {
      "command": "mvn",
      "args": ["-q", "-f", "$POM_PATH", "-Pstdio-mcp-dev", "spring-boot:run"],
      "env": {}
    }
  }
}
EOF

echo "Installed Cursor java-tuning-agent dev adapter into $PROJECT_DIR"
