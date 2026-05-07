#!/usr/bin/env bash
set -euo pipefail

PACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_PATH="${1:?Usage: scripts/install-codex.sh /absolute/path/to/java-tuning-agent.jar}"
CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH" >&2
  exit 1
fi

mkdir -p "$CODEX_HOME/skills"
rm -rf "$CODEX_HOME/skills/java-tuning-agent-workflow"
cp -R "$PACK_DIR/skills/java-tuning-agent-workflow" "$CODEX_HOME/skills/"

if command -v codex >/dev/null 2>&1; then
  codex mcp remove java-tuning-agent >/dev/null 2>&1 || true
  codex mcp add java-tuning-agent -- java -jar "$JAR_PATH"
  codex mcp list
else
  echo "codex command not found. Add this to $CODEX_HOME/config.toml:"
  echo "[mcp_servers.java-tuning-agent]"
  echo "command = \"java\""
  echo "args = [\"-jar\", \"$JAR_PATH\"]"
fi

echo "Installed Codex skill: $CODEX_HOME/skills/java-tuning-agent-workflow"
