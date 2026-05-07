#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACK="$ROOT/agent-pack/java-tuning-agent"

fail() {
  echo "validate-agent-pack: $*" >&2
  exit 1
}

expected_tools=(
  listJavaApps
  inspectJvmRuntime
  inspectJvmRuntimeRepeated
  recordJvmFlightRecording
  collectMemoryGcEvidence
  generateTuningAdvice
  generateTuningAdviceFromEvidence
  validateOfflineAnalysisDraft
  submitOfflineHeapDumpChunk
  finalizeOfflineHeapDump
  generateOfflineTuningAdvice
  summarizeOfflineHeapDumpFile
  analyzeOfflineHeapRetention
)

[[ -f "$PACK/.codex-plugin/plugin.json" ]] || fail "missing Codex plugin manifest"
[[ -f "$PACK/README.md" ]] || fail "missing pack README"
[[ -f "$PACK/INSTALL.md" ]] || fail "missing pack install guide"
[[ -f "$PACK/skills/java-tuning-agent-workflow/SKILL.md" ]] || fail "missing Codex skill"
[[ -f "$PACK/skills/java-tuning-agent-workflow/agents/openai.yaml" ]] || fail "missing Codex skill metadata"
[[ -f "$PACK/skills/java-tuning-agent-workflow/reference.md" ]] || fail "missing tool reference"
[[ -f "$PACK/adapters/cursor/rules/java-tuning-agent-mcp.mdc" ]] || fail "missing Cursor rule"
[[ -f "$PACK/adapters/cursor/skills/java-tuning-agent-workflow/SKILL.md" ]] || fail "missing Cursor skill"
[[ -f "$PACK/adapters/copilot/copilot-instructions.md" ]] || fail "missing Copilot instructions"

schema_dir="$ROOT/mcps/user-java-tuning-agent/tools"
actual_count="$(find "$schema_dir" -maxdepth 1 -name '*.json' | wc -l | tr -d ' ')"
[[ "$actual_count" == "13" ]] || fail "expected 13 tool schemas, found $actual_count"

for tool in "${expected_tools[@]}"; do
  [[ -f "$schema_dir/$tool.json" ]] || fail "missing tool schema: $tool"
  grep -q "$tool" "$PACK/skills/java-tuning-agent-workflow/SKILL.md" || fail "skill does not mention $tool"
  grep -q "$tool" "$PACK/skills/java-tuning-agent-workflow/reference.md" || fail "reference does not mention $tool"
done

cmp -s "$PACK/skills/java-tuning-agent-workflow/SKILL.md" "$PACK/adapters/cursor/skills/java-tuning-agent-workflow/SKILL.md" \
  || fail "Cursor skill must match Codex skill"
cmp -s "$PACK/skills/java-tuning-agent-workflow/reference.md" "$PACK/adapters/cursor/skills/java-tuning-agent-workflow/reference.md" \
  || fail "Cursor reference must match Codex reference"

skill_description_len="$(python3 - "$PACK/skills/java-tuning-agent-workflow/SKILL.md" <<'PY'
from pathlib import Path
import sys

text = Path(sys.argv[1]).read_text(encoding="utf-8")
frontmatter = text.split("---", 2)[1]
lines = frontmatter.splitlines()
desc_lines = []
in_desc = False
for line in lines:
    if line.startswith("description:"):
        in_desc = True
        value = line.split(":", 1)[1].strip()
        if value and value != ">-":
            desc_lines.append(value)
        continue
    if in_desc:
        if line.startswith("  ") or not line.strip():
            stripped = line.strip()
            if stripped:
                desc_lines.append(stripped)
        else:
            break
print(len(" ".join(desc_lines)))
PY
)"
[[ "$skill_description_len" -le 1024 ]] || fail "Codex skill description exceeds 1024 characters: $skill_description_len"

for json in "$PACK/.codex-plugin/plugin.json" "$PACK"/mcp/release/*.json "$PACK"/mcp/dev/*.json; do
  python3 -m json.tool "$json" >/dev/null || fail "invalid JSON: $json"
done

for config in "$PACK"/mcp/release/*.json; do
  grep -q '"command": "java"' "$config" || fail "$config must use java"
  grep -q -- '-jar' "$config" || fail "$config must launch a jar"
  ! grep -q 'mvn' "$config" || fail "$config must not use Maven"
done

grep -q 'command = "java"' "$PACK/mcp/release/codex-config.toml" || fail "Codex release config must use java"
grep -q -- '-jar' "$PACK/mcp/release/codex-config.toml" || fail "Codex release config must launch a jar"
! grep -q 'mvn' "$PACK/mcp/release/codex-config.toml" || fail "Codex release config must not use Maven"

for config in "$PACK"/mcp/dev/*.json; do
  grep -q '"command": "mvn"' "$config" || fail "$config must use Maven for dev"
  grep -q -- '"-q"' "$config" || fail "$config must pass -q for stdio MCP"
  grep -q 'spring-boot:run' "$config" || fail "$config must run spring-boot:run"
done

grep -q 'command = "mvn"' "$PACK/mcp/dev/codex-config.toml" || fail "Codex dev config must use Maven"
grep -q -- '"-q"' "$PACK/mcp/dev/codex-config.toml" || fail "Codex dev config must pass -q"
grep -q 'spring-boot:run' "$PACK/mcp/dev/codex-config.toml" || fail "Codex dev config must run spring-boot:run"

for script in "$PACK"/scripts/*.sh; do
  [[ -x "$script" ]] || fail "script is not executable: $script"
  bash -n "$script" || fail "script syntax error: $script"
done

expected_ps1=(
  install-codex.ps1
  install-codex-dev.ps1
  install-cursor.ps1
  install-cursor-dev.ps1
  install-copilot.ps1
)

for script_name in "${expected_ps1[@]}"; do
  script="$PACK/scripts/$script_name"
  [[ -f "$script" ]] || fail "missing PowerShell installer: $script_name"
  grep -q 'Set-StrictMode' "$script" || fail "$script_name must enable strict mode"
done

expected_sh=(
  install-codex.sh
  install-codex-dev.sh
  install-cursor.sh
  install-cursor-dev.sh
  install-copilot.sh
)

for script_name in "${expected_sh[@]}"; do
  script="$PACK/scripts/$script_name"
  [[ -x "$script" ]] || fail "missing executable installer: $script_name"
done

[[ -f "$PACK/mcp/dev/cursor-mcp.json" ]] || fail "missing Cursor dev MCP template"
grep -q '"java-tuning-agent"' "$PACK/mcp/dev/cursor-mcp.json" \
  || fail "Cursor dev MCP template must keep server name java-tuning-agent"
grep -q '"command": "mvn"' "$PACK/mcp/dev/cursor-mcp.json" \
  || fail "Cursor dev MCP template must use Maven"
grep -q -- '"-q"' "$PACK/mcp/dev/cursor-mcp.json" \
  || fail "Cursor dev MCP template must pass -q"

if command -v pwsh >/dev/null 2>&1; then
  for script in "$PACK"/scripts/*.ps1; do
    pwsh -NoProfile -Command "\$tokens = \$null; \$errors = \$null; \$null = [System.Management.Automation.Language.Parser]::ParseFile('$script', [ref]\$tokens, [ref]\$errors); if (\$errors.Count -gt 0) { \$errors | ForEach-Object { Write-Error \$_ }; exit 1 }" \
      || fail "PowerShell syntax error: $script"
  done
else
  echo "pwsh not found; skipped PowerShell syntax parse."
fi

echo "Agent pack validation passed."
