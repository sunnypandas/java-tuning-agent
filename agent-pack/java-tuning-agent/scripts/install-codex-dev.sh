#!/usr/bin/env bash
set -euo pipefail

PACK_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM_PATH="${1:?Usage: scripts/install-codex-dev.sh /absolute/path/to/java-tuning-agent/pom.xml}"
CODEX_HOME="${CODEX_HOME:-$HOME/.codex}"

if [[ ! -f "$POM_PATH" ]]; then
  echo "pom.xml not found: $POM_PATH" >&2
  exit 1
fi

mkdir -p "$CODEX_HOME/skills"
rm -rf "$CODEX_HOME/skills/java-tuning-agent-workflow"
cp -R "$PACK_DIR/skills/java-tuning-agent-workflow" "$CODEX_HOME/skills/"

if command -v codex >/dev/null 2>&1; then
  codex mcp remove java-tuning-agent-dev >/dev/null 2>&1 || true
  codex mcp add java-tuning-agent-dev -- mvn -q -f "$POM_PATH" -Pstdio-mcp-dev spring-boot:run
  codex mcp list
  echo "Installed Codex dev MCP server: java-tuning-agent-dev"
else
  echo "codex command not found. Skill was installed, but MCP was not registered."
  echo "Add this to $CODEX_HOME/config.toml manually if needed:"
  echo "[mcp_servers.java-tuning-agent-dev]"
  echo "command = \"mvn\""
  echo "args = [\"-q\", \"-f\", \"$POM_PATH\", \"-Pstdio-mcp-dev\", \"spring-boot:run\"]"
fi

echo "Installed Codex skill: $CODEX_HOME/skills/java-tuning-agent-workflow"
