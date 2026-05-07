#!/usr/bin/env bash
set -euo pipefail

PACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="${1:?Usage: scripts/install-cursor.sh /path/to/project /absolute/path/to/java-tuning-agent.jar}"
JAR_PATH="${2:?Usage: scripts/install-cursor.sh /path/to/project /absolute/path/to/java-tuning-agent.jar}"

mkdir -p "$PROJECT_DIR/.cursor/rules" "$PROJECT_DIR/.cursor/skills"
cp "$PACK_DIR/adapters/cursor/rules/java-tuning-agent-mcp.mdc" "$PROJECT_DIR/.cursor/rules/"
rm -rf "$PROJECT_DIR/.cursor/skills/java-tuning-agent-workflow"
cp -R "$PACK_DIR/adapters/cursor/skills/java-tuning-agent-workflow" "$PROJECT_DIR/.cursor/skills/"

cat > "$PROJECT_DIR/.cursor/mcp.json" <<EOF
{
  "mcpServers": {
    "java-tuning-agent": {
      "command": "java",
      "args": ["-jar", "$JAR_PATH"],
      "env": {}
    }
  }
}
EOF

echo "Installed Cursor java-tuning-agent adapter into $PROJECT_DIR"
