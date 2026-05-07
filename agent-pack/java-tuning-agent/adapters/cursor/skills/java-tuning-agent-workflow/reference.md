# java-tuning-agent — tool arguments reference

Server name in config is often **`java-tuning-agent`**; Cursor may expose it as **`user-java-tuning-agent`**. Use the MCP tools exposed by that server.

**Workflow:** See **SKILL.md** — `collectMemoryGcEvidence` requires the **mandatory step-3 scope gate** first (AskQuestion or explicit chat choice), including **snapshot-only**; the agent must not silently use all-`false` without user selection.

**Public surface:** Current schema export is **13 tools** (7 live JVM + 6 offline/import). Top-level tool descriptions should stay bilingual (English + 中文) in Java annotations and checked-in `mcps/user-java-tuning-agent/tools/*.json` so both MCP clients and Chinese troubleshooting guides remain understandable.

**Evidence reuse:** When an existing `MemoryGcEvidencePack` is available, preserve optional fields such as `baselineEvidence`, `jfrSummary`, `repeatedSamplingResult`, `nativeMemorySummary`, `resourceBudgetEvidence`, `heapShallowSummary`, `heapRetentionAnalysis`, `gcLogSummary`, and `diagnosisWindow`; call `generateTuningAdviceFromEvidence` rather than recollecting.

## 1. `listJavaApps`

Arguments: empty object `{}`.

## 2. `inspectJvmRuntime`

```json
{ "pid": 12345 }
```

`pid` must appear in the latest `listJavaApps` result.

## 2b. `inspectJvmRuntimeRepeated`

```json
{
  "request": {
    "pid": 12345,
    "sampleCount": 3,
    "intervalMillis": 10000,
    "includeThreadCount": true,
    "includeClassCount": true,
    "confirmationToken": ""
  }
}
```

This safe read-only P0 repeated mode does not require `confirmationToken`.

## 2c. `recordJvmFlightRecording`

Use this only after explicit approval for short JFR profiling.

```json
{
  "request": {
    "pid": 12345,
    "durationSeconds": 30,
    "settings": "profile",
    "jfrOutputPath": "C:/tmp/app-profile.jfr",
    "maxSummaryEvents": 200000,
    "confirmationToken": "user-approved"
  }
}
```

`jfrOutputPath` must be absolute, end in `.jfr`, and point to a file that does not already exist. Use `settings: "profile"` for more profiling signal or `settings: "default"` for lower overhead.

Result fields to inspect: `jfrPath`, `fileSizeBytes`, `summary.eventCounts`, `summary.gcSummary`, `summary.allocationSummary`, `summary.threadSummary`, `summary.executionSampleSummary`, `warnings`, and `missingData`.

## 3. `collectMemoryGcEvidence`

Wrapper key is **`request`**. Only `pid` is unconditionally required by schema; omit optional fields or use `false` / `""` when not using a privileged option.

**Lightweight (no extra jcmd cost):**

```json
{
  "request": {
    "pid": 12345,
    "includeClassHistogram": false,
    "includeThreadDump": false,
    "includeHeapDump": false,
    "heapDumpOutputPath": "",
    "confirmationToken": ""
  }
}
```

**With histogram (needs explicit consent + non-blank token):**

```json
{
  "request": {
    "pid": 12345,
    "includeClassHistogram": true,
    "includeThreadDump": false,
    "includeHeapDump": false,
    "heapDumpOutputPath": "",
    "confirmationToken": "java-tuning-agent:ui-approval:v1:pid=12345:scopes=classHistogram"
  }
}
```

**`confirmationToken` — two valid sources:**

1. **Structured UI (preferred):** After checkbox-style multi-select (e.g. AskQuestion `allow_multiple: true`), set flags from selections and use the **canonical token** (no user typing required):

   ```text
   java-tuning-agent:ui-approval:v1:pid=<decimalPid>:scopes=<sorted-comma-list>
   ```

   Scopes are literal names: `classHistogram`, `heapDump`, `threadDump`, sorted alphabetically, comma-separated, no spaces. Example all three:

   `java-tuning-agent:ui-approval:v1:pid=12345:scopes=classHistogram,heapDump,threadDump`

2. **Chat text:** Copy the user’s **verbatim** approval phrase as `confirmationToken`, or normalize an unambiguous list of scopes into the canonical form above.

**Heap dump:** set `includeHeapDump: true`, `heapDumpOutputPath` to an **absolute** `.hprof` path whose parent directory exists and target file does not, and non-blank `confirmationToken`.

**Combined approval (one user message):** The user may list class histogram, thread dump, and heap dump in a **single** reply. Set the three `include*` flags to match; use canonical token or verbatim phrase as `confirmationToken`.

**Default heap path:** If the user defers to “default”, resolve the OS temp directory to an absolute path and use a non-existing `java-tuning-agent-heap-{pid}.hprof` path there. State that full path in chat before calling the tool.

**All three privileged (example paths are illustrative — substitute real absolute temp + pid):**

```json
{
  "request": {
    "pid": 12345,
    "includeClassHistogram": true,
    "includeThreadDump": true,
    "includeHeapDump": true,
    "heapDumpOutputPath": "C:/Users/you/AppData/Local/Temp/java-tuning-agent-heap-12345.hprof",
    "confirmationToken": "java-tuning-agent:ui-approval:v1:pid=12345:scopes=classHistogram,heapDump,threadDump"
  }
}
```

## 4. `generateTuningAdviceFromEvidence`

Use this after `collectMemoryGcEvidence` when you want advice from the exact evidence pack that was just collected. It does not collect a second snapshot, histogram, thread dump, or heap dump.

```json
{
  "evidence": {
    "snapshot": { },
    "classHistogram": null,
    "threadDump": null,
    "missingData": [],
    "warnings": [],
    "heapDumpPath": null
  },
  "baselineEvidence": null,
  "jfrSummary": null,
  "repeatedSamplingResult": null,
  "resourceBudgetEvidence": null,
  "environment": "local",
  "optimizationGoal": "reduce GC pause and stable latency",
  "codeContextSummary": {
    "dependencies": [],
    "configuration": {},
    "applicationNames": ["MyApplication"],
    "sourceRoots": ["C:/absolute/path/to/repo"],
    "candidatePackages": ["com.example.app"]
  }
}
```

In practice, set `evidence` to the **full JSON object returned by `collectMemoryGcEvidence`**. Keep optional fields such as `nativeMemorySummary`, `resourceBudgetEvidence`, `heapShallowSummary`, `jfrSummary`, `repeatedSamplingResult`, `baselineEvidence`, and `diagnosisWindow` if present. The top-level optional `baselineEvidence`, `jfrSummary`, `repeatedSamplingResult`, and `resourceBudgetEvidence` fields are merge helpers for separately collected evidence; they fill missing pack fields and are optional.

**Do not** follow `collectMemoryGcEvidence` by calling `generateTuningAdvice` with the same `collectClassHistogram` / `collectThreadDump` / `includeHeapDump` / `heapDumpOutputPath` values. That path collects again.

## 4b. `generateTuningAdvice`

Use this as a one-shot collect-and-advise shortcut only when you have **not** already called `collectMemoryGcEvidence` for the current diagnosis. Existing clients can keep using it unchanged.

**Lightweight advice (no extra collection inside this tool):**

```json
{
  "pid": 12345,
  "environment": "local",
  "optimizationGoal": "reduce GC pause and stable latency",
  "collectClassHistogram": false,
  "collectThreadDump": false,
  "includeHeapDump": false,
  "heapDumpOutputPath": "",
  "confirmationToken": "",
  "codeContextSummary": {
    "dependencies": [],
    "configuration": {},
    "applicationNames": ["MyApplication"],
    "sourceRoots": ["C:/absolute/path/to/repo"],
    "candidatePackages": ["com.example.app"]
  }
}
```

**With histogram inside generateTuningAdvice:** `collectClassHistogram: true` and non-blank `confirmationToken`. This performs a fresh collection inside the tool. For source file hints, set **`sourceRoots`** to absolute roots.

**codeContextSummary fields:**

| Field | Use |
|-------|-----|
| `applicationNames` | Simple names of `@SpringBootApplication` or main classes |
| `sourceRoots` | Absolute paths to source trees (empty if unknown) |
| `candidatePackages` | Package prefixes to bias hotspot ranking |
| `dependencies` | Optional Maven/Gradle coordinates strings |
| `configuration` | Optional key/value map (pools, profiles, etc.) |

When unknown, use `[]` or `{}` as appropriate; the schema requires all five keys present.

**Evidence reuse:** If you already ran `collectMemoryGcEvidence` with histogram/thread/heap, call `generateTuningAdviceFromEvidence` with the returned pack instead. Do not mirror step-3 collection flags into `generateTuningAdvice`.

**Response shape:** `TuningAdviceReport` includes **`formattedSummary`**: Markdown with fixed section order (`##` / `###`, lists, fenced `text` code blocks for evidence). **Show it in the chat as renderable Markdown** — paste the string into the message body **without** wrapping the whole thing in an outer code fence (that hides formatting). Add a short preamble above if needed. Do not paraphrase away `suspectedCodeHotspots` or other sections.

**Heap dump indexing:** When `includeHeapDump` produced a file and **`java-tuning-agent.heap-summary.auto-enabled`** is `true`, the returned `MemoryGcEvidencePack` includes **`heapShallowSummary`** (Shark shallow-by-class stats). The report’s `formattedSummary` may end with an appended **heap summary** section (or a failure message).

---

## 5. Offline / imported bundle tools

Use when there is **no** local target PID: the user provides exported `jcmd`/`jstat` text, class histogram, thread dump, and/or a **local** `.hprof` path (or uses chunk upload + finalize). **Six** tools: `validateOfflineAnalysisDraft`, `submitOfflineHeapDumpChunk`, `finalizeOfflineHeapDump`, `generateOfflineTuningAdvice`, `summarizeOfflineHeapDumpFile`, and `analyzeOfflineHeapRetention`. **Consent:** same `confirmationToken` rules as online when the draft includes histogram, thread dump, or heap path (non-blank token required).

**5.1 `validateOfflineAnalysisDraft`**

```json
{
  "draft": { },
  "proceedWithMissingRequired": false
}
```

`draft` is a full `OfflineBundleDraft` (B1–B6, optional R1–R3 “absent” flags, `heapDumpAbsolutePath`, `backgroundNotes`, etc.). Set `proceedWithMissingRequired: true` only after the user explicitly accepts degraded analysis.

Validation also performs structural target checks. If B1 JVM identity, B3 runtime snapshot, B4 class histogram, or B5 thread dump expose conflicting PID markers, the result stays structurally usable but includes degradation warnings. When later calling `generateOfflineTuningAdvice` with a `CodeContextSummary`, the server compares imported `java_command` against `applicationNames` and `candidatePackages`; a mismatch is surfaced as a warning and `offlineTargetConsistency` missing-data marker.

**OfflineBundleDraft field shapes that agents must not guess:**

- `jvmIdentityText`, `jdkInfoText`, `runtimeSnapshotText`: plain strings
- `classHistogram`, `threadDump`: `OfflineArtifactSource` objects, not bare strings
- `nativeMemorySummary`, `directBufferEvidence`, `metaspaceEvidence`: optional `OfflineArtifactSource` objects; primary native/direct/metaspace rules consume structured `nativeMemorySummary`
- `heapDumpAbsolutePath`: plain string path
- `gcLogPathOrText`: plain string path or inline JDK unified GC log text; parsed into `gcLogSummary` when present
- `repeatedSamplesPathOrText`: plain string path or inline `inspectJvmRuntimeRepeated` JSON; parsed into `repeatedSamplingResult` when present
- `backgroundNotes.resourceBudget`: key=value resource budget text (`containerMemoryLimitBytes`, `processRssBytes`, `cpuQuotaCores`, optional `estimatedThreadStackBytes`); malformed values degrade to missing budget fields

**OfflineArtifactSource shape:**

```json
{ "filePath": "C:/diag/b4-class-histogram.txt" }
```

or

```json
{ "inlineText": " num     #instances   #bytes  class name..." }
```

**Do not pass a bare string to `OfflineArtifactSource` fields.**

Wrong:

```json
{ "classHistogram": "C:/diag/b4-class-histogram.txt" }
```

Wrong:

```json
{ "classHistogram": "top entries include [B ..." }
```

Prefer `filePath` when the exported file already exists locally.

**5.2 `submitOfflineHeapDumpChunk`**

```json
{
  "uploadId": "",
  "chunkIndex": 0,
  "chunkTotal": 3,
  "chunkBase64": "<base64 bytes>"
}
```

First call: leave `uploadId` empty; reuse the returned `uploadId` for all chunks `0 .. chunkTotal-1`. Creating a new upload also runs opportunistic TTL cleanup for stale incomplete upload directories (`java-tuning-agent.offline.heap-dump-upload.ttl-seconds`, default 86400). Finalized heap dumps are retained by default; set `java-tuning-agent.offline.heap-dump-upload.cleanup-finalized=true` only when repository-managed finalized dumps should expire too.

**5.3 `finalizeOfflineHeapDump`**

```json
{
  "uploadId": "<id from submit>",
  "expectedSha256Hex": "<lowercase or uppercase hex>",
  "expectedSizeBytes": 12345678
}
```

Write the returned path into `draft.heapDumpAbsolutePath` before `generateOfflineTuningAdvice`.

**5.4 `generateOfflineTuningAdvice`**

```json
{
  "codeContextSummary": { },
  "draft": { },
  "environment": "prod",
  "optimizationGoal": "reduce memory growth",
  "analysisDepth": "deep",
  "confirmationToken": "user-verbatim-or-canonical-offline-scopes",
  "proceedWithMissingRequired": false
}
```

When `heapDumpAbsolutePath` is a real file and heap auto-summary is on, the server **automatically** runs Shark and includes the result in the same `TuningAdviceReport` as online (findings from rules, not the LLM).

When `draft.gcLogPathOrText` is present, the server parses JDK unified GC pause lines and uses the resulting `gcLogSummary` for long-pause, Full GC, humongous-allocation, and evacuation-pressure findings.

When `draft.repeatedSamplesPathOrText`, `draft.nativeMemorySummary`, or `draft.backgroundNotes.resourceBudget` is present, the server imports repeated trend, native/NMT, or resource-budget evidence into the same pack. Set `analysisDepth: "deep"` only when holder-oriented heap retention evidence should be attempted and attached; blank uses balanced/default behavior.

**5.5 `summarizeOfflineHeapDumpFile` (optional)**

```json
{
  "heapDumpAbsolutePath": "C:/data/heap.hprof",
  "topClassLimit": 40,
  "maxOutputChars": 32000
}
```

Use for a **standalone** shallow table / `topByShallowBytes` without running the full offline advice. Omit `topClassLimit` / `maxOutputChars` to use server defaults.

**5.6 `analyzeOfflineHeapRetention`**

```json
{
  "heapDumpAbsolutePath": "C:/data/heap.hprof",
  "topObjectLimit": 20,
  "maxOutputChars": 16000,
  "analysisDepth": "deep",
  "focusTypes": [],
  "focusPackages": []
}
```

Use this when you need holder-oriented retention evidence instead of only shallow-by-class output. `analysisDepth` may be `fast`, `balanced`, or `deep`; deep mode attempts a retained-style analysis and falls back honestly if the dump cannot support it.
