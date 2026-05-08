# Export Classloader Metaspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Have diagnostic export scripts collect optional classloader stats and wire the result into `offline-draft-template.json` as `metaspaceEvidence`.

**Architecture:** Add a best-effort `VM.classloader_stats` collection helper to both shell and PowerShell export scripts. The helper writes `optional-metaspace-classloader-stats.txt` when available or a `*-SKIPPED.txt` note when unavailable, and the draft template references only existing collected files.

**Tech Stack:** Bash, PowerShell, existing `jcmd`/`jstat` export flow, Maven fixture JVM for smoke verification.

---

## Task 1: Shell Export Script

**Files:**
- Modify: `scripts/export-jvm-diagnostics.sh`

- [ ] **Step 1: Verify current shell script lacks metaspace evidence output**

Run the fixture export smoke command with `--skip-heap-dump --sample-count 1 --sample-interval-seconds 0`, then inspect `offline-draft-template.json`.

Expected before implementation: no `optional-metaspace-classloader-stats.txt` file and no `draft.metaspaceEvidence.filePath`.

- [ ] **Step 2: Add shell helper**

Add `collect_classloader_stats()` near `collect_native_memory_summary()`:

```bash
collect_classloader_stats() {
  local output_file="$1"
  local skipped_file="$2"
  local stats_data stats_code stats_text
  stats_data="$(run_capture_raw "${jcmd}" "${resolved_pid}" VM.classloader_stats)"
  stats_code="$(printf '%s\n' "${stats_data}" | sed -n '1p')"
  stats_text="$(printf '%s\n' "${stats_data}" | sed '1d')"
  if [[ "${stats_code}" != "0" || -z "${stats_text//[[:space:]]/}" ]]; then
    {
      echo "VM.classloader_stats was not available."
      echo "Manual: jcmd ${resolved_pid} VM.classloader_stats"
      echo
      echo "jcmd output:"
      printf '%s\n' "${stats_text}"
    } >"${skipped_file}"
    return 1
  fi
  {
    echo "VM.classloader_stats"
    printf '%s\n' "${stats_text}"
  } >"${output_file}"
  return 0
}
```

- [ ] **Step 3: Add shell template field**

In `write_offline_draft_template`, discover `optional-metaspace-classloader-stats.txt`, pass it into the Python template generator, and add:

```python
"metaspaceEvidence": source(metaspace_path),
```

- [ ] **Step 4: Call shell helper before writing template**

Add:

```bash
collect_classloader_stats "${root}/optional-metaspace-classloader-stats.txt" "${root}/optional-metaspace-classloader-stats-SKIPPED.txt" || true
```

before `write_offline_draft_template`.

- [ ] **Step 5: Verify shell export smoke**

Run the fixture export smoke command again.

Expected after implementation: either `optional-metaspace-classloader-stats.txt` with `VM.classloader_stats` and a draft `metaspaceEvidence.filePath`, or `optional-metaspace-classloader-stats-SKIPPED.txt` with an empty draft `metaspaceEvidence.filePath` if the local JVM does not support the command.

## Task 2: PowerShell Export Script

**Files:**
- Modify: `scripts/export-jvm-diagnostics.ps1`

- [ ] **Step 1: Add PowerShell helper**

Add `Export-ClassloaderStats` near `Export-NativeMemorySummary`:

```powershell
function Export-ClassloaderStats {
    param(
        [Parameter(Mandatory = $true)][string]$OutFile,
        [Parameter(Mandatory = $true)][string]$SkippedFile
    )
    $stats = Invoke-ToolTextRaw -ExePath $jcmd -ToolArgs @($pidStr, 'VM.classloader_stats')
    if ($stats.Code -ne 0 -or [string]::IsNullOrWhiteSpace($stats.Text)) {
        @(
            'VM.classloader_stats was not available.',
            "Manual: jcmd $pidStr VM.classloader_stats",
            '',
            'jcmd output:',
            $stats.Text
        ) | Set-Content -LiteralPath $SkippedFile -Encoding utf8
        return $false
    }
    @('VM.classloader_stats', $stats.Text.TrimEnd()) | Set-Content -LiteralPath $OutFile -Encoding utf8
    return $true
}
```

- [ ] **Step 2: Add PowerShell template field**

In `Write-OfflineDraftTemplate`, discover `optional-metaspace-classloader-stats.txt` and add:

```powershell
metaspaceEvidence = New-ArtifactSource $metaspacePath
```

- [ ] **Step 3: Call PowerShell helper before writing template**

Add:

```powershell
[void](Export-ClassloaderStats -OutFile (Join-Path $root 'optional-metaspace-classloader-stats.txt') -SkippedFile (Join-Path $root 'optional-metaspace-classloader-stats-SKIPPED.txt'))
```

before `Write-OfflineDraftTemplate`.

- [ ] **Step 4: Static sanity check**

Run:

```bash
pwsh -NoProfile -Command '$null = [scriptblock]::Create((Get-Content -Raw scripts/export-jvm-diagnostics.ps1)); "ok"'
```

Expected: prints `ok` if `pwsh` is available. If `pwsh` is unavailable, note that PowerShell static verification could not be run.

## Task 3: Verification And Commit

**Files:**
- Modify: `docs/mcp-jvm-tuning-demo-walkthrough.md`

- [ ] **Step 1: Update demo walkthrough file list**

Add `optional-metaspace-classloader-stats.txt` or `optional-metaspace-classloader-stats-SKIPPED.txt` to the enhanced evidence list near the export walkthrough.

- [ ] **Step 2: Run focused parser/offline tests**

Run:

```bash
mvn -q -Dtest=ClassloaderMetaspaceParserTest,OfflineEvidenceAssemblerTest test
```

Expected: PASS.

- [ ] **Step 3: Run full tests**

Run:

```bash
mvn -q test
```

Expected: exit code 0.

- [ ] **Step 4: Check diff**

Run:

```bash
git diff --check
git diff --stat
```

Expected: no whitespace errors; diff limited to export scripts, docs, and implementation plan.

- [ ] **Step 5: Commit**

Run:

```bash
git add scripts/export-jvm-diagnostics.sh scripts/export-jvm-diagnostics.ps1 docs/mcp-jvm-tuning-demo-walkthrough.md docs/superpowers/plans/2026-05-08-export-classloader-metaspace.md
git commit -m "feat: export classloader metaspace evidence"
```
