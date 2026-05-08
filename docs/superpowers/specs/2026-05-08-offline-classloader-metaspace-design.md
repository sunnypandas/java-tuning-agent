# Offline Classloader Metaspace Evidence Design

## Goal

Turn the existing offline `metaspaceEvidence` field from passive supporting context into structured classloader/metaspace evidence that can improve offline diagnosis of class metadata growth and suspected classloader retention.

## Scope

This P2 slice covers offline/imported bundles only:

- Read `OfflineBundleDraft.metaspaceEvidence` from `filePath` or `inlineText`.
- Parse common classloader statistics text from `jcmd <pid> VM.classloader_stats` and `jmap -clstats <pid>` style exports.
- Attach a compact structured summary to `MemoryGcEvidencePack`.
- Add rule-based findings for classloader concentration, repeated generated/proxy classloader patterns, and high class count/byte ownership by loader type.
- Keep the current online `collectMemoryGcEvidence` behavior unchanged.

This slice does not cover:

- Running `VM.classloader_stats`, `GC.class_stats`, `jmap -clstats`, or any new live JVM command.
- Adding a new MCP tool parameter. The existing `metaspaceEvidence` input is the only entry point.
- Heap-dump retained-size analysis by classloader.
- Definitive leak proof. The report should use “suspected” language unless stronger evidence exists elsewhere.

## Inputs

The preferred input is `OfflineBundleDraft.metaspaceEvidence`:

```json
{
  "metaspaceEvidence": {
    "filePath": "/path/to/classloader-stats.txt"
  }
}
```

or:

```json
{
  "metaspaceEvidence": {
    "inlineText": "jcmd 123 VM.classloader_stats\n..."
  }
}
```

The parser should accept text that contains command banners, PID lines, headers, separators, and unrelated surrounding diagnostic text. Empty input means no classloader metaspace evidence is attached and the current offline path remains unchanged.

## Data Model

Add a compact runtime evidence model under `com.alibaba.cloud.ai.examples.javatuning.runtime`:

- `ClassloaderMetaspaceSummary`
  - `List<ClassloaderMetaspaceEntry> entries`
  - `long totalClassCount`
  - `long totalBytes`
  - `List<String> warnings`
  - derived helpers such as `topByClassCount(int)` and `topByBytes(int)` may be implemented if they keep rules simple.
- `ClassloaderMetaspaceEntry`
  - `String classLoaderName`
  - `String parentClassLoaderName`
  - `Long classCount`
  - `Long bytes`
  - `Boolean alive`
  - `String rawLine`

Attach the summary to `MemoryGcEvidencePack` with a nullable field and a `withClassloaderMetaspaceSummary(...)` method. Existing constructors should remain source-compatible by setting the new field to `null`.

## Parsing Strategy

Create `ClassloaderMetaspaceParser` in the runtime package. It should be permissive and line-oriented:

- Skip blank lines, separator lines, and obvious headers.
- Detect `VM.classloader_stats` rows where class count and byte-like columns appear before or after classloader names.
- Detect `jmap -clstats` rows that include classloader identity, parent identity, alive/dead status, class count, and byte/chunk/block-like sizes.
- Convert decimal numbers with optional commas to `Long`.
- Keep `rawLine` on each parsed entry for report/debug traceability.
- Add warnings when the text is non-blank but no rows can be parsed.

The parser should avoid brittle position-only parsing when headers are present. Header-aware parsing is preferred, with fallback regex parsing for known compact rows.

## Offline Assembly

`OfflineEvidenceAssembler` should load `draft.metaspaceEvidence()` through `OfflineTextLoader`, parse it with `ClassloaderMetaspaceParser`, and attach the result to the returned `MemoryGcEvidencePack`.

Failure behavior:

- Missing or blank `metaspaceEvidence`: no summary, no warning.
- File read failure: add `metaspaceEvidence` to `missingData` and add a warning.
- Non-blank but unparseable text: attach no summary, add `metaspaceEvidence` to `missingData`, add parser warning.
- Partially parseable text: attach parsed entries and include parser warnings.

## Diagnosis Rule

Add a new rule named `ClassloaderMetaspaceRule` after `MetaspacePressureRule` and before `ResourceBudgetPressureRule`.

The rule should inspect `ClassloaderMetaspaceSummary` and produce a medium-severity finding when any of these are true:

- A single classloader owns a high number of classes.
- Many classloader rows share the same generated/proxy/framework pattern.
- Classloader evidence exists together with NMT `Class` growth or high metaspace utilization from the P1 fields.

The finding evidence should include:

- top classloader name
- top class count
- top bytes when available
- repeated loader pattern when detected
- whether NMT class growth or metaspace utilization corroborates the classloader evidence

Recommendations should focus on classloader lifecycle:

- inspect dynamic proxy / CGLIB / ByteBuddy / Groovy / JSP / plugin / hot-deploy loading paths
- ensure custom classloaders are dereferenced when modules or tenants are unloaded
- compare before/after classloader stats across a steady diagnosis window

The rule must not claim a confirmed leak from classloader stats alone.

## Reporting

The existing `TuningAdviceReportFormatter` can render findings and recommendations through the normal report sections. No new report section is required in P2.

If parsed classloader evidence exists but no rule threshold is crossed, the report can remain silent except for confidence/missing-data behavior already handled elsewhere. This keeps reports concise.

## Documentation And Schemas

Update descriptions that currently say `metaspaceEvidence` is retained for future expansion:

- `README.md`
- `docs/offline-mode-spec.md`
- workflow skill copies under `.cursor/` and `agent-pack/`
- MCP schema export descriptions if they are generated from `OfflineBundleDraft`

The wording should say `nativeMemorySummary` remains the primary NMT source, while `metaspaceEvidence` can now carry classloader stats for offline classloader/metaspace attribution.

## Testing

Use TDD for implementation:

- Parser tests for representative `VM.classloader_stats` text.
- Parser tests for representative `jmap -clstats` text.
- Parser test for non-blank unparseable text producing warnings.
- Offline assembler test proving `metaspaceEvidence` is loaded and attached.
- Rule test proving repeated generated/proxy classloader patterns produce a suspected classloader retention finding.
- Rule test proving classloader evidence plus NMT `Class` growth produces stronger evidence text.
- Regression test proving missing `metaspaceEvidence` leaves current offline behavior unchanged.

Focused verification command:

```bash
mvn -q -Dtest=ClassloaderMetaspaceParserTest,OfflineEvidenceAssemblerTest,ClassloaderMetaspaceRuleTest,MemoryGcDiagnosisEngineTest test
```

Full verification command:

```bash
mvn -q test
```
