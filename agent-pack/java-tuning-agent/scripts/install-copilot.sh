#!/usr/bin/env bash
set -euo pipefail

PACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_DIR="${1:?Usage: scripts/install-copilot.sh /path/to/project /absolute/path/to/java-tuning-agent.jar}"
JAR_PATH="${2:?Usage: scripts/install-copilot.sh /path/to/project /absolute/path/to/java-tuning-agent.jar}"

mkdir -p "$PROJECT_DIR/.github"
cp "$PACK_DIR/adapters/copilot/copilot-instructions.md" "$PROJECT_DIR/.github/copilot-instructions.md"

cat > "$PROJECT_DIR/.github/mcp.json" <<EOF
{
  "mcpServers": {
    "java-tuning-agent": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "$JAR_PATH"],
      "tools": ["*"],
      "timeout": 600000
    }
  }
}
EOF

cat > "$PROJECT_DIR/.mcp.json" <<EOF
{
  "mcpServers": {
    "java-tuning-agent": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "$JAR_PATH"],
      "tools": ["*"],
      "timeout": 600000
    }
  }
}
EOF

echo "Installed GitHub Copilot java-tuning-agent adapter into $PROJECT_DIR"
