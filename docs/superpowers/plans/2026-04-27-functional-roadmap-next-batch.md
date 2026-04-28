# Functional Roadmap Next Batch (Feature-First)

## Scope

This batch focuses on practical production value first, without adding process-heavy work:

1. Differential diagnostics baseline (`baseline vs current`)
2. NMT differential analysis (`summary` / `summary.diff`)
3. JFR signal integration into rule-based diagnosis

## Ticket 1: Differential Diagnostics Baseline

### Why

Single snapshots are often insufficient in production. Teams need deltas to validate regressions after deploys or config changes.

### Key implementation targets

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowService.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java`

### Minimal deliverable

- Add optional baseline evidence attachment (same schema as current evidence).
- Add deterministic delta section in final report (`Key Deltas`).
- Include at least heap, GC, and native deltas.

### Acceptance

- Existing requests without baseline continue to work unchanged.
- With baseline provided, report includes explicit delta findings.

## Ticket 2: NMT Differential Analysis

### Why

Native memory growth is a major source of production incidents. Absolute NMT values are less useful than growth per category.

### Key implementation targets

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/NativeMemorySummaryParser.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineEvidenceAssembler.java`

### Minimal deliverable

- Parse `VM.native_memory summary` and `summary.diff` when available.
- Compute per-category growth (`reserved` and `committed`).
- Emit standardized degrade warnings when NMT is unavailable.

### Acceptance

- Live and offline both produce category-level NMT growth when inputs exist.
- Missing NMT never aborts diagnosis; it only adds `missingData` + warning.

## Ticket 3: JFR Insights Rule Integration

### Why

JFR already has high-value signals (allocation, lock contention, execution samples), but without engine integration those signals do not drive recommendations.

### Key implementation targets

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummaryParser.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/JfrInsightsRule.java` (new)

### Minimal deliverable

- Add JFR-derived evidence block into shared evidence model.
- Add deterministic JFR rule producing findings from:
  - top allocation events
  - monitor/park contention
  - hottest execution samples

### Acceptance

- When JFR summary is present, report includes JFR-based findings and next steps.
- When absent, no behavior regression in existing report paths.

## Out of Scope (for this batch)

- Evidence score numeric model
- Full case/session persistence
- Multi-tenant platform controls

## Suggested execution order

1. Ticket 1 (baseline scaffold)
2. Ticket 2 (NMT diff)
3. Ticket 3 (JFR rule integration)

This order minimizes refactor churn because Ticket 1 creates the delta/report skeleton reused by Ticket 2 and Ticket 3.