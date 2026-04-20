# Memory/GC Diagnosis Agent Design

Date: 2026-04-11

## 1. Goal

Build the first practical version of a Java tuning diagnosis agent that can automatically produce reasonably trustworthy memory and GC conclusions.

The first version should:

- Focus on memory and GC diagnosis first.
- Default to lightweight readonly evidence collection.
- Escalate to medium-cost diagnostics only when needed and only with explicit confirmation.
- Produce code-level suspicion points when source is available.
- Fall back to configuration-level advice when source is unavailable.

The first version should not:

- Automatically apply JVM flag changes.
- Automatically edit production code.
- Automatically collect heap dumps by default.
- Attempt broad non-memory diagnostics such as full thread/lock diagnosis as a primary goal.

## 2. Product Outcome

The agent should help replace part of the traditional "collect dump, inspect manually, correlate with code" workflow by turning runtime evidence plus optional source context into a structured diagnosis report.

For the first version:

- If source is visible, aim for:
  - diagnosis conclusion
  - evidence summary
  - configuration recommendations
  - suspected code hotspots
- If source is not visible, still provide:
  - diagnosis conclusion
  - evidence summary
  - configuration recommendations
  - missing data and next-step guidance

## 3. Scope

### 3.1 In Scope

- Local JVM discovery
- Structured runtime collection for memory and GC
- Controlled evidence upgrades using class histogram and optional thread dump support
- Rule-based diagnosis for memory and GC issues
- Confidence scoring and explicit confidence reasons
- Source-aware correlation when local source is available
- Source-summary fallback when only external code context is available

### 3.2 Out of Scope for First Version

- Automatic heap dump workflows
- JFR-first diagnosis flows
- Automated code or config modification
- Remote host diagnosis
- Full container and distributed system diagnosis
- First-class thread/lock performance diagnosis beyond memory-supporting evidence

## 4. Core Principles

1. Evidence before conclusion.
2. Structured data before model reasoning.
3. Safe by default, explicit upgrade for heavier diagnostics.
4. Honest downgrade when evidence is insufficient.
5. Source-aware when possible, source-agnostic when necessary.

## 5. Architecture

The system should evolve from the current "discovery + raw snapshot + thin advice" model into a diagnosis pipeline with clearer boundaries:

1. Process Discovery
2. Structured Runtime Collector
3. Evidence Pack Builder
4. Memory/GC Diagnosis Engine
5. Code Correlation Layer
6. Diagnosis Workflow Service
7. MCP Tool Surface

### 5.1 Process Discovery

Responsibilities:

- Discover Java applications visible to the current user.
- Produce stable application descriptors for later diagnosis.
- Improve process hints for source correlation and environment understanding.

Expected output fields:

- `pid`
- `displayName`
- `mainClassOrJar`
- `commandLine`
- `workDirHint`
- `userHint`
- `jvmVersionHint`
- `applicationTypeHint`
- `springBootHint`
- `profilesHint`
- `portHints`
- `discoverySource`
- `discoveryConfidence`

### 5.2 Structured Runtime Collector

Responsibilities:

- Run safe readonly commands by default.
- Parse raw command output into structured fields instead of passing raw strings through the pipeline.
- Record collection metadata and warnings.

Default data sources:

- `jcmd <pid> VM.version`
- `jcmd <pid> VM.flags`
- `jcmd <pid> GC.heap_info`
- `jstat` memory and GC counters needed for lightweight diagnosis

Structured snapshot fields should include:

- `process`
- `jvm`
- `memory`
- `gc`
- `threads`
- `classLoading`
- `vmFlags`
- `collectionMeta`
- `warnings`

Important structured fields for first version:

- `heapUsedBytes`
- `heapCommittedBytes`
- `heapMaxBytes`
- `oldGenUsedBytes`
- `oldGenCommittedBytes`
- `metaspaceUsedBytes`
- `youngGcCount`
- `youngGcTimeMs`
- `fullGcCount`
- `fullGcTimeMs`
- `threadCount`
- `collector`
- `xmsBytes`
- `xmxBytes`

### 5.3 Evidence Pack Builder

Responsibilities:

- Group all evidence used for one diagnosis run into a single bundle.
- Support both lightweight and upgraded evidence.
- Preserve enough context to explain confidence and missing data later.

The first version evidence pack should support:

- structured lightweight snapshot
- optional class histogram summary
- optional thread dump summary
- collection metadata
- warnings
- missing data markers

### 5.4 Memory/GC Diagnosis Engine

Responsibilities:

- Evaluate structured evidence.
- Produce diagnosis findings, recommendations, confidence, and next steps.
- Separate hard evidence from inferred reasoning.

Internal shape:

- `DiagnosisRule` interface
- several memory/GC rule implementations
- confidence evaluator
- evidence upgrade decision helper

### 5.5 Code Correlation Layer

Responsibilities:

- Use local source when available.
- Fall back to externally provided code summaries when local source is unavailable.
- Map suspicious runtime evidence to packages, classes, beans, configs, and likely ownership areas.

First version correlation targets:

- suspicious classes from histogram
- Spring cache or singleton components
- static collections or stores
- memory-heavy pipelines or batch handlers
- JVM and Spring configuration relevant to memory pressure

### 5.6 Diagnosis Workflow Service

Responsibilities:

- Orchestrate lightweight collection, diagnosis, evidence upgrade decisions, and final report generation.
- Avoid mixing collection logic with diagnosis logic.

Planned flow:

1. Collect lightweight structured runtime snapshot.
2. Run first-pass memory/GC diagnosis.
3. If evidence is insufficient for a trustworthy conclusion, recommend or request upgraded evidence.
4. Re-run diagnosis with upgraded evidence if available.
5. Apply code correlation if source or source summary exists.
6. Produce final structured diagnosis report.

## 6. MCP Surface

Keep the current tool names where possible, but upgrade their semantics.

### 6.1 `listJavaApps`

Purpose:

- Discover candidate JVMs and return stronger application hints.

### 6.2 `inspectJvmRuntime`

Purpose:

- Return a structured lightweight runtime snapshot suitable for diagnosis, not only raw command output.

### 6.3 `collectMemoryGcEvidence`

New tool.

Purpose:

- Collect medium-cost diagnostic evidence when lightweight evidence is not enough.

Supported evidence kinds for first version:

- `classHistogram`
- `threadDump`
- `gcSummary` (conceptual; in the current implementation there is no separate `gcSummary` payload—GC counters are already part of the lightweight structured snapshot and histogram adds retention-oriented evidence)

This tool should require explicit confirmation for privileged or heavier evidence collection.

**Implementation note:** See §16 for current behavior (histogram supported; thread dump flag gated but collection may still be incomplete).

### 6.4 `generateTuningAdvice`

Purpose:

- Generate a structured diagnosis report from runtime evidence plus optional code context.

Input (`GenerateTuningAdviceParams` / MCP flattened fields):

- **`CodeContextSummary`** (including optional **`sourceRoots`**)
- **`pid`**, **`environment`**, **`optimizationGoal`**
- **`collectClassHistogram`** / **`collectThreadDump`** plus **`confirmationToken`** when privileged collection is requested (histogram path runs `collectMemoryGcEvidence` before diagnosis)

Supports both local-source-aware agents and callers without direct source access.

## 7. Diagnosis Targets for First Version

The first version should focus on five memory/GC diagnosis categories.

### 7.1 High Heap Pressure / Heap Sizing Mismatch

Evidence examples:

- high heap utilization ratio
- old generation occupancy stays high
- full GC exists or is close

Output:

- finding about heap pressure
- config-level JVM tuning suggestions
- source-aware hints for retained caches, aggregations, or long-lived collections

### 7.2 Suspected Memory Leak

Evidence examples:

- old generation grows across repeated snapshots
- post-GC memory remains high
- class histogram shows a small set of growing retained types

Output:

- leak suspicion finding
- explanation of why this looks like retention rather than short-lived allocation pressure
- suspected object types
- suspected code hotspots when source is available

### 7.3 High Allocation Rate / GC Churn

Evidence examples:

- young GC count or time is high
- old gen is not necessarily full
- memory churn suggests short-lived allocation pressure

Output:

- finding pointing to short-lived object pressure
- recommendations for batching, serialization, object creation patterns, or logging pressure

### 7.4 GC Strategy / Parameter Mismatch

Evidence examples:

- collector and workload characteristics appear mismatched
- Xms and Xmx spread is inappropriate
- GC tuning flags conflict with observed behavior

Output:

- configuration-level recommendations
- moderate or high confidence where evidence is direct

### 7.5 Memory Problem but Evidence Insufficient

Evidence examples:

- heap is high but trend data is missing
- class histogram has not been collected
- old generation evidence is incomplete

Output:

- explicit lower-confidence diagnosis
- missing data list
- next-step evidence guidance

## 8. Evidence Upgrade Policy

First version should use a staged evidence model.

### 8.1 Default Lightweight Evidence

- readonly structured runtime snapshot
- low-cost `jcmd` and `jstat` inputs

### 8.2 Confirmation-Gated Medium Evidence

- class histogram
- thread dump

Thread dump should be supported in the first version mainly as supporting evidence for retention analysis, not as a primary thread diagnosis workflow.

### 8.3 Heavy Evidence

Heap dump and JFR should remain outside the primary first-version flow, though the design should not block adding them later.

## 9. Source Handling Model

The system should support both source-visible and source-summary modes.

### 9.1 Source Visible

When local source is available, the agent should:

- inspect local code directly
- map suspicious classes to repository files or packages
- incorporate relevant configuration files
- return suspected code hotspots

### 9.2 Source Summary Only

When only `codeContextSummary` is available, the agent should:

- rely on the supplied summary
- return configuration-level advice
- avoid pretending to have source-level certainty

### 9.3 No Useful Code Context

When neither source nor summary is available, the agent should still diagnose runtime evidence and explicitly lower confidence for code-level conclusions.

## 10. Output Contract

The report should evolve beyond the current thin advice shape.

First-version report should include:

- `findings`
- `recommendations`
- `suspectedCodeHotspots`
- `missingData`
- `nextSteps`
- `confidence`
- `confidenceReasons`

### 10.1 Findings

Each finding should describe:

- title
- severity
- evidence
- reasoning type
- impact

Reasoning type should distinguish at least:

- `rule-based`
- `inferred-from-evidence`

### 10.2 Recommendations

Each recommendation should describe:

- action
- category
- config example
- expected benefit
- risk
- preconditions

### 10.3 Suspected Code Hotspots

Each hotspot should describe:

- class or package hint
- file hint when available
- suspicion reason
- evidence link
- confidence

### 10.4 Confidence

Confidence should not be a bare label only.

The system should provide:

- `confidence`: `high`, `medium`, or `low`
- `confidenceReasons`: why the conclusion earned that level

## 11. Implementation Plan by Phase

### Phase 1: Structured Runtime Foundation

- Extend runtime collection to produce structured memory and GC fields.
- Add parsers for the selected `jcmd` and `jstat` outputs.
- Update tests so diagnosis no longer depends on invented fields.

### Phase 2: Evidence Pack and Upgraded Collection

- Add evidence pack types.
- Implement class histogram collection behind policy checks.
- Add thread dump support as optional supporting evidence.

### Phase 3: Memory/GC Diagnosis Engine

- Introduce diagnosis rules and confidence evaluation.
- Replace the current thin rule-based advisor flow with a real memory/GC diagnosis workflow.
- Generate findings, recommendations, missing data, and next steps from evidence.

### Phase 4: Source Correlation

- Add local-source-aware correlation.
- Support external source summaries as fallback input.
- Produce suspected code hotspots when possible.

## 12. Validation Strategy

The first version should be validated at three levels.

### 12.1 Unit Tests

- runtime parsers
- evidence pack construction
- diagnosis rules
- confidence calculation
- source correlation helpers

### 12.2 Integration Tests

- MCP tool to workflow interactions
- evidence upgrade flows
- structured report output shape

### 12.3 Compatibility Scenario

Use `compat/memory-leak-demo` to validate:

- leak suspicion detection
- high heap pressure detection
- evidence upgrade behavior
- source-aware hotspot reporting

## 13. Key Risks

1. Raw diagnostic output differs across JDK versions and platforms.
2. Lightweight evidence may be insufficient for confident leak diagnosis.
3. Source correlation may overstate certainty if not carefully constrained.
4. MCP input models may become awkward if runtime evidence and source context are not separated clearly.

## 14. Decisions Captured

- First version optimizes for memory/GC diagnosis.
- Default collection remains lightweight and readonly.
- Medium-cost evidence requires explicit confirmation.
- Source-aware diagnosis is supported when possible.
- Source-summary fallback remains supported.
- Goal is trustworthy diagnosis, not just recommendation generation.

## 15. Success Criteria

The first version is successful if it can:

- produce structured memory/GC findings from real runtime evidence
- explain why confidence is high, medium, or low
- request upgraded evidence instead of guessing when data is insufficient
- provide configuration-level recommendations without source
- provide suspected code hotspots when source is available

## 16. Implementation status (repository sync)

This section tracks what the **current codebase** does relative to §5–§10. Update it when behavior changes.

### 16.1 Layers (packages)

| Layer | Location | Notes |
|-------|----------|--------|
| Structured runtime | `runtime.*` | `JvmRuntimeSnapshot` (includes `jvmVersion`, `threadCount` from `PerfCounter.print`’s `java.threads.live`, `loadedClassCount` from `jstat -class`), `JvmMemorySnapshot` (incl. optional `oldGenCommittedBytes`), `JvmGcSnapshot`, parsers (`GcHeapInfoParser`, `JstatGcUtilParser`, `ClassHistogramParser`, `ThreadDumpParser`, …), `SafeJvmRuntimeCollector` (default commands: `VM.flags`, **`VM.version`**, `GC.heap_info`, `jstat -gcutil`, **`jstat -class`**, **`PerfCounter.print`**). |
| Evidence pack | `runtime.*` | `MemoryGcEvidencePack`, `MemoryGcEvidenceRequest`, optional `ThreadDumpSummary`; histogram + thread dump behind policy + `confirmationToken`. When a **`.hprof`** file exists and **`java-tuning-agent.heap-summary.auto-enabled`** is `true`, the pack may include **`heapShallowSummary`** (Shark shallow-by-class totals + bounded Markdown). |
| Diagnosis | `advice.*` | `MemoryGcDiagnosisEngine`, `DiagnosisRule` implementations (`HighHeapPressureRule`, `SuspectedLeakRule`, `HeapDumpShallowDominanceRule`, `ThreadDumpInsightsRule`, `AllocationChurnRule`, `GcStrategyMismatchRule`, `EvidenceGapRule`), `DiagnosisConfidenceEvaluator`. |
| Source correlation | `source.*` | `LocalSourceHotspotFinder` maps histogram FQCNs to `.java` paths under `CodeContextSummary.sourceRoots`. |
| Workflow | `agent.*` | `JavaTuningWorkflowService` orchestrates diagnosis + hotspot attachment. |
| Discovery | `discovery.*` | `JavaApplicationDescriptor` adds **`workDirHint`** (`-Duser.dir`), **`applicationTypeHint`**, **`portHints`** (`-Dserver.port` / `--server.port`), **`discoveryConfidence`**; **`userHint`** / **`jvmVersionHint`** remain empty on jps-only discovery unless extended later. |
| MCP | `mcp.*` | `JavaTuningMcpTools` exposes four **live JVM** tools; `OfflineMcpTools` exposes five **offline/import** tools (validate, heap chunk submit/finalize, offline advice, optional heap summarize). See repository `README.md` for the full table. |

### 16.2 MCP tools (actual)

- **`listJavaApps`** — unchanged intent (discovery).
- **`inspectJvmRuntime`** — structured lightweight snapshot as in §5.2 / §6.2.
- **`collectMemoryGcEvidence`** — implements **class histogram** when `includeClassHistogram` and **`confirmationToken`** are set. **`includeThreadDump`**: runs **`jcmd Thread.print`**, parses a **`ThreadDumpSummary`** (thread count, counts by `java.lang.Thread.State`, optional deadlock hint lines). Blank or unparseable output yields **`missingData: threadDump`** and warnings. **`includeHeapDump`**: after **`GC.heap_dump`**, if the target file exists and heap-summary auto-mode is enabled, **`heapShallowSummary`** is populated via **Shark** (shallow totals only; not MAT dominator analysis).
- **`generateTuningAdvice`** — runs **`MemoryGcDiagnosisEngine`** on a **new** collection for the PID: default lightweight snapshot, or **histogram-inclusive** when **`collectClassHistogram`** and a non-blank **`confirmationToken`** are set (internally **`collectMemoryGcEvidence`**). Returns **`TuningAdviceReport`** with **`confidenceReasons`** and **`suspectedCodeHotspots`** when histogram + `sourceRoots` are present.

Design §6.3 listed `gcSummary` as an evidence kind; there is **no separate gcSummary artifact** beyond the structured snapshot and histogram—the lightweight snapshot already carries GC counter fields from `jstat`.

### 16.3 Java API vs MCP (histogram + advice)

- **`TuningAdviceRequest`** supports an optional **`classHistogramHint`**: when non-null, **`generateAdvice`** builds a **`MemoryGcEvidencePack`** that includes this histogram for **diagnosis** and **hotspot** correlation without re-running `jcmd`.
- **MCP** `generateTuningAdvice` can request **`collectClassHistogram`** (+ **`confirmationToken`**) for a single end-to-end histogram + report flow, or callers may still chain **`collectMemoryGcEvidence`** and interpret the pack manually.

### 16.4 Diagnosis rules (first version)

Rough mapping to §7:

| §7 topic | Rule / behavior |
|----------|-----------------|
| High heap pressure | `HighHeapPressureRule` (heap utilization vs max; falls back to **old-gen %** from `jstat` when heap-used parsing is missing). |
| Suspected leak | `SuspectedLeakRule` (histogram: dominant **application-relevant** type share; **`byte[]` / `[B`** counted—not treated as opaque “JVM internal”). |
| Heap dump shallow leaders | `HeapDumpShallowDominanceRule` (requires successful **`heapShallowSummary`** from Shark indexing; dominant non-JDK type shallow share threshold—complements histogram timing). |
| Allocation churn | `AllocationChurnRule` (high young GC counts/time without extreme heap fill). |
| GC / heap parameter mismatch | `GcStrategyMismatchRule` (Xms vs Xmx spread). |
| Insufficient evidence | `EvidenceGapRule` (heap **or** old-gen pressure without histogram; merge pack `missingData`); confidence via `DiagnosisConfidenceEvaluator`. |

### 16.5 Tests and demo

- Unit tests under `src/test/java/.../runtime`, `advice`, `source`, `agent`, `mcp`.  
- Spring Boot test asserts MCP tool names include **`collectMemoryGcEvidence`**.  
- **`compat/memory-leak-demo`** remains the recommended manual compatibility target (§12.3).
