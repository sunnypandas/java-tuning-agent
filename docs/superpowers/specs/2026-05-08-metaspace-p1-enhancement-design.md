# Metaspace P1 Enhancement Design

## Goal

Upgrade the current metaspace/class metadata analysis from simple high-watermark detection to capacity-aware and trend-aware diagnosis, while keeping the change small enough to land safely in the existing rule-based pipeline.

## Scope

This P1 enhancement covers:

- Parse and carry metaspace `used`, `committed`, and `reserved` bytes from `jcmd GC.heap_info`.
- Parse and carry `jstat -gcutil` metaspace (`M`) and compressed class space (`CCS`) utilization percentages.
- Use NMT `Class` category growth from `VM.native_memory summary.diff` inside `MetaspacePressureRule`.
- Improve findings and next steps so reports distinguish missing NMT evidence, high metaspace capacity, and growing class metadata.
- Keep online and offline evidence paths aligned by reusing the existing `NativeMemorySummary` and snapshot assembly path.

This P1 enhancement does not cover:

- Structured parsing of `jcmd VM.classloader_stats`, `jcmd GC.class_stats`, or `jmap -clstats`.
- Classloader ownership mapping, classloader retention chains, or dependency-level attribution.
- New MCP tool parameters or schema fields for dedicated metaspace evidence beyond the fields that already exist.

## Current Behavior

The agent already parses `Metaspace used` from `GC.heap_info`, captures loaded class count from `jstat -class`, attempts `VM.native_memory summary`, parses the NMT `Class` category, and runs `MetaspacePressureRule`.

The current rule fires when `metaspaceUsedBytes >= 512MB` or NMT `classCommittedBytes >= 512MB`. It also asks for NMT Class evidence when metaspace/classloader/class-count signals exist but NMT is missing.

The main limitation is that the rule does not know metaspace committed/reserved capacity, `M`/`CCS` utilization, or whether NMT `Class` grew during the diagnosis window.

## Architecture

Keep the enhancement inside the existing runtime evidence and rule engine architecture:

- Extend `JvmMemorySnapshot` with nullable metaspace committed/reserved fields and nullable metaspace/class-space utilization percentages.
- Extend `GcHeapInfoParser` to parse committed/reserved from the already matched `Metaspace` line.
- Extend `JstatGcUtilParser` and `JvmGcSnapshot` to expose optional `metaspaceUtilPercent` and `compressedClassSpaceUtilPercent` from `M` and `CCS`.
- Update `OfflineJvmSnapshotAssembler` to populate the expanded snapshot fields from exported runtime text where available.
- Update `MetaspacePressureRule` to evaluate absolute size, capacity pressure, utilization pressure, class-count growth, and NMT Class committed growth.

No separate metaspace analyzer is introduced in P1. The existing rule remains the central decision point, because the current evidence model is still compact and report generation already expects rule findings and next steps.

## Data Flow

Online:

1. `SafeJvmRuntimeCollector.collect` runs `jcmd GC.heap_info` and `jstat -gcutil`.
2. `GcHeapInfoParser` extracts metaspace used/committed/reserved.
3. `JstatGcUtilParser` extracts heap/GC metrics plus optional `M` and `CCS`.
4. `collectMemoryGcEvidence` optionally attaches `NativeMemorySummary`, including `Class` category growth from `summary.diff`.
5. `MetaspacePressureRule` produces findings, recommendations, and next steps.

Offline:

1. `OfflineJvmSnapshotAssembler` parses the exported runtime snapshot text using the same parser types where possible.
2. `OfflineEvidenceAssembler` loads optional `nativeMemorySummary` and merges `summary.diff`.
3. The same `MetaspacePressureRule` runs on the assembled evidence pack.

## Rule Behavior

`MetaspacePressureRule` should add a finding when any of these are true:

- Metaspace used or NMT Class committed is at least 512MB.
- Metaspace committed is at least 512MB and metaspace utilization is high enough to indicate real pressure.
- `jstat -gcutil` reports high `M` or `CCS` utilization.
- NMT `Class` committed growth is significant during the diagnosis window.

The finding evidence text should include the values that triggered the rule, such as:

- `metaspaceUsedMb`
- `metaspaceCommittedMb`
- `metaspaceReservedMb`
- `classCommittedMb`
- `classCommittedGrowthMb`
- `metaspaceUtilPercent`
- `compressedClassSpaceUtilPercent`

If NMT is missing while metaspace, classloader, or loaded-class growth signals exist, the rule should keep the existing next step and make it command-oriented enough for users to act:

`Collect NMT Class category with -XX:NativeMemoryTracking=summary and jcmd <pid> VM.native_memory summary to distinguish class metadata pressure from heap retention.`

## Error Handling And Compatibility

All new fields are nullable or optional so existing JSON payloads remain compatible.

Parser failures should degrade the same way current parsers do:

- Missing `Metaspace` committed/reserved leaves the new fields null.
- Missing `M` or `CCS` columns leaves utilization fields null.
- Missing NMT growth keeps the rule based on absolute snapshot evidence only.

No existing privileged collection semantics change. NMT remains read-only and capability-aware.

## Testing

Use TDD for each behavior:

- Add failing parser tests for metaspace committed/reserved parsing.
- Add failing parser tests for `jstat -gcutil` `M` and `CCS`.
- Add failing rule tests for NMT `Class` committed growth and high `M`/`CCS`.
- Add regression tests proving old minimal parser inputs still work.
- Run the focused suite:

```bash
mvn -q -Dtest=GcHeapInfoParserTest,JstatGcUtilParserTest,MetaspacePressureRuleTest,NativeMemorySummaryParserTest,SafeJvmRuntimeCollectorTest,OfflineJvmSnapshotAssemblerTest test
```

Then run the full test suite if the focused suite passes:

```bash
mvn -q test
```
