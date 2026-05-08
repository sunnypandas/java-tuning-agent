# Export Classloader Metaspace Evidence Design

## Goal

Make the offline classloader/metaspace analysis usable from exported diagnostic bundles by having the export scripts collect classloader statistics automatically when the target JVM supports it.

## Scope

This slice covers the existing export scripts only:

- `scripts/export-jvm-diagnostics.sh`
- `scripts/export-jvm-diagnostics.ps1`

The scripts should try to collect `jcmd <pid> VM.classloader_stats` into:

```text
optional-metaspace-classloader-stats.txt
```

If unavailable, they should write:

```text
optional-metaspace-classloader-stats-SKIPPED.txt
```

The generated `offline-draft-template.json` should set:

```json
"metaspaceEvidence": {
  "filePath": "<export-dir>/optional-metaspace-classloader-stats.txt",
  "inlineText": ""
}
```

only when the collected file exists.

## Non-Goals

- Do not add a new MCP tool or live diagnosis flag.
- Do not make classloader stats mandatory.
- Do not fail the export if `VM.classloader_stats` is unsupported, unavailable, blocked by permissions, or returns blank output.
- Do not run `jmap -clstats` as fallback in this slice. The parser supports jmap text for user-provided offline evidence, but the export script keeps using the same `jcmd` tool family already required by the project.

## Behavior

The shell and PowerShell scripts should behave like `optional-native-memory-summary`:

- Attempt collection after repeated samples and before the offline draft template is written.
- Prefix the collected output with `VM.classloader_stats` so parser inputs are self-describing.
- Treat non-zero exit code or blank output as skipped.
- Include a manual command in the skipped file:

```text
jcmd <pid> VM.classloader_stats
```

The export summary already lists all files in the export directory, so no extra summary section is needed.

## Offline Draft Template

Both scripts already build `offline-draft-template.json`. They should discover the optional classloader stats file just like the native memory summary file and include it as `draft.metaspaceEvidence`.

When collection is skipped, `metaspaceEvidence` should be an empty artifact source:

```json
{"filePath": "", "inlineText": ""}
```

This keeps the draft compatible with `OfflineBundleDraft` and avoids forcing users to remove missing paths by hand.

## Testing

Use TDD around the scripts:

- Add or update script tests to assert the exported directory includes `optional-metaspace-classloader-stats.txt` when `jcmd VM.classloader_stats` succeeds.
- Assert the offline draft template contains `draft.metaspaceEvidence.filePath`.
- Assert skipped collection writes `optional-metaspace-classloader-stats-SKIPPED.txt` and leaves `metaspaceEvidence.filePath` empty.
- Cover both shell and PowerShell behavior where existing test harnesses make that practical.

Focused verification:

```bash
mvn -q -Dtest=OfflineEvidenceAssemblerTest,ClassloaderMetaspaceParserTest test
```

Script smoke verification:

```bash
mvn -q -Dtest=SafeJvmRuntimeCollectorTest test
```

Full verification:

```bash
mvn -q test
```
