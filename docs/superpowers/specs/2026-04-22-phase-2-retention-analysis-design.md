# Phase 2 Retention Analysis Design

**Date:** 2026-04-22  
**Status:** Proposed and approved for planning  
**Audience:** implementers of `java-tuning-agent` offline heap analysis and advice integration

---

## 1. Context

Phase 1 introduced an independent retention-oriented MCP tool, `analyzeOfflineHeapRetention`, with a Shark-backed implementation that produces:

- holder hints
- representative reference chains
- GC-root hints
- bounded `reachableSubgraphBytesApprox`

Phase 1 intentionally did **not** do two things:

1. produce stronger retained / dominator-style evidence
2. integrate retention evidence into `generateOfflineTuningAdvice` / `MemoryGcEvidencePack`

This phase addresses both gaps while preserving the stable external contract introduced in phase 1.

---

## 2. Goals

- Keep `analyzeOfflineHeapRetention` as the single retention MCP entry point.
- Preserve phase-1 behavior for normal requests.
- Add a heavier engine path that is closer to dominator / retained semantics than the Shark path.
- Trigger the heavier path only when the caller explicitly asks for deeper analysis.
- Reuse the same retention orchestration from both:
  - `analyzeOfflineHeapRetention`
  - `generateOfflineTuningAdvice`
- Allow deep analysis to fail or degrade safely without breaking advice generation.

---

## 3. Non-Goals

- Do not create a second public MCP tool for heavyweight retention analysis.
- Do not add a new top-level API parameter such as `engine`, `mode`, or `retentionSemantics`.
- Do not change the default semantics of `fast` / `balanced` requests.
- Do not require deep retention analysis for all `.hprof` advice flows.
- Do not promise exact MAT-equivalent output in all cases.

---

## 4. Chosen Architecture

### 4.1 Summary

Phase 2 adopts an **orchestrated dual-engine design**:

- `SharkHeapRetentionAnalyzer` remains the default lightweight engine.
- A new heavyweight retained-style engine is added behind a shared interface.
- A new orchestration layer decides which engine to run based on the request.
- The orchestration layer is reused by both the MCP tool and offline advice generation.

### 4.2 Why this design

This keeps the phase-1 external contract stable while creating room for a stronger engine:

- callers still use the same tool and same result model
- deep behavior is explicit rather than surprising
- engine selection and fallback logic live in one place instead of being duplicated in MCP and advice code
- future engines can be added without changing the public contract again

---

## 5. Engine Model

### 5.1 Engine roles

Two engine classes exist conceptually:

1. **Lightweight engine**
   - current Shark-backed path
   - optimized for quick holder/path hints
   - always safe as the fallback path

2. **Heavyweight engine**
   - new retained/dominator-style path
   - may use heavier libraries and slower analysis
   - intended only for explicit deep requests

### 5.2 Shared engine contract

A shared internal contract should normalize engine output into the existing retention model:

- `HeapRetentionAnalysisResult`
- `HeapRetentionSummary`
- `RetainedTypeSummary`
- `SuspectedHolderSummary`
- `RetentionChainSummary`
- `HeapRetentionConfidence`

No phase-2 API change is required for callers.

### 5.3 Heavy engine recommendation

The recommended phase-2 direction is to introduce a **heavier retained-style engine backed by a dominator-capable library**, with Eclipse MAT-style analysis as the target semantic benchmark.

The implementation may use a MAT-compatible library stack or another retained-graph implementation, but the phase-2 design assumes:

- it can produce stronger retained-style values than phase 1
- it may be slower and more memory-intensive
- it will not always succeed on every heap

The design deliberately avoids promising exact MAT parity; it only requires that the engine be materially closer to dominator / retained relationships than the Shark path.

---

## 6. Orchestration Layer

### 6.1 New shared component

Introduce a shared orchestration service in the offline analysis domain, for example:

- `HeapRetentionAnalysisOrchestrator`

Responsibilities:

- inspect request parameters
- decide whether deep analysis is allowed
- run the appropriate engine
- handle fallback
- normalize warnings and confidence text

### 6.2 Callers

The following flows reuse the orchestrator:

1. `OfflineMcpTools.analyzeOfflineHeapRetention`
2. `generateOfflineTuningAdvice` offline workflow

This keeps engine selection out of MCP tool glue and out of advice formatting logic.

---

## 7. `analysisDepth` Semantics

Phase 2 keeps the existing public parameter and strengthens its meaning:

- `fast`
  - Shark only
  - no heavyweight attempt

- `balanced`
  - Shark only
  - preserves phase-1 default behavior

- `deep`
  - explicit permission to attempt heavyweight retained-style analysis
  - if heavyweight analysis fails, fall back to Shark

This means:

- there is **no automatic heavyweight upgrade** for `balanced`
- deep analysis is opt-in
- the public contract remains stable

---

## 8. Fallback Rules

### 8.1 Deep request flow

For `analysisDepth=deep`, orchestration should behave as follows:

1. try heavyweight engine first
2. if it succeeds, return heavyweight-backed retention evidence
3. if it fails, times out, exceeds resource limits, or returns unusable evidence, fall back to Shark

### 8.2 Fallback behavior requirements

If fallback occurs:

- the overall request may still return success
- the returned result must clearly disclose that heavyweight analysis did not complete
- wording must revert to the safer phase-1 style when retained-style claims are not defensible

### 8.3 Where fallback is surfaced

Fallback must be reflected in:

- `engine`
- `warnings`
- `confidenceAndLimits.limitations`
- Markdown summary

Because phase 2 intentionally avoids adding a new explicit semantics field, these existing channels carry the meaning.

---

## 9. Result Semantics

The user explicitly chose **not** to add a new structured field such as `retentionSemantics`.

Therefore phase 2 keeps the current model and conveys semantic strength through:

- engine identity
- limitation text
- confidence reasons
- Markdown wording

Required wording rules:

- if heavyweight retained-style evidence is available, the summary may use stronger retained/dominator-style language
- if heavyweight analysis is unavailable and the result comes from Shark or fallback, the summary must continue using conservative wording such as:
  - `retention hint`
  - `reachable subgraph`
  - `not full dominator retained-size`

This preserves honesty without changing the JSON contract again.

---

## 10. Advice Integration

### 10.1 Default behavior stays unchanged

When only a heap dump path is present, offline advice continues to auto-consume only:

- `heapDumpPath`
- `heapShallowSummary`

This preserves the phase-1 default and keeps ordinary advice calls fast and predictable.

### 10.2 Deep advice path

When the caller explicitly requests deep analysis, the offline advice workflow should:

1. invoke the shared retention orchestrator
2. receive a normal `HeapRetentionAnalysisResult`
3. translate the result into advice-facing evidence and findings

### 10.3 What advice should consume

Advice should use retention results for higher-value conclusions such as:

- stronger holder identification
- more specific root/chain explanation
- better explanation of why growth looks retained rather than churn
- better next steps when retained-style evidence is available

Advice should not consume engine-specific internals directly.

### 10.4 If deep analysis degrades

If heavyweight analysis fails and Shark fallback succeeds:

- advice generation should still continue
- confidence should be reduced appropriately
- report text should explain that stronger retained-style evidence was unavailable

---

## 11. Shared Evidence-Pack Integration

Phase 2 should begin shared integration, but in a bounded form.

Recommended approach:

- extend the offline advice assembly path so retention results can be passed as supplementary structured evidence
- avoid forcing the entire existing `MemoryGcEvidencePack` contract to fully absorb engine-specific internals

In other words:

- shared workflows should be able to **consume** retention evidence
- they should not become tightly coupled to one specific engine implementation

This keeps the shared advice path reusable while still moving beyond phase-1 isolation.

---

## 12. Suggested File Responsibilities

Expected new or changed responsibilities in phase 2:

- `src/main/java/.../offline/HeapRetentionAnalysisOrchestrator.java`
  - engine selection, fallback, and request normalization

- `src/main/java/.../offline/HeavyHeapRetentionAnalyzer.java`
  - interface or implementation boundary for heavyweight analysis

- `src/main/java/.../offline/<heavy-engine impl>.java`
  - retained/dominator-style implementation

- `src/main/java/.../mcp/OfflineMcpTools.java`
  - continue exposing the same tool, now delegating to the orchestrator

- `src/main/java/.../agent/JavaTuningWorkflowService.java`
  - deep advice flow consumption of orchestrated retention evidence

- `src/main/java/.../offline/OfflineEvidenceAssembler.java`
  - bounded translation of retention result into advice-consumable evidence

- `src/main/java/.../runtime/* retention model`
  - keep stable unless a truly necessary contract correction is discovered

---

## 13. Testing Strategy

Phase 2 should test both routing and semantic honesty.

### 13.1 Orchestration tests

- `fast` routes to Shark only
- `balanced` routes to Shark only
- `deep` attempts heavyweight analysis first
- heavyweight failure falls back to Shark

### 13.2 Result wording tests

- heavyweight success allows stronger retained-style wording
- fallback path restores conservative wording
- limitations/warnings mention degraded deep analysis

### 13.3 Advice integration tests

- default offline advice path still uses shallow summary only
- explicit deep advice path includes retention evidence
- degraded deep advice still produces a valid report with reduced confidence

### 13.4 Real heap tests

Retain the real `.hprof` test style introduced in phase 1 and add at least one fixture that demonstrates why the heavyweight path is stronger than the Shark path.

---

## 14. Risks

### 14.1 Resource cost

Heavyweight analysis may be slow or memory-intensive.

Mitigation:

- only attempt it for `analysisDepth=deep`
- keep Shark as a reliable fallback
- surface degradation honestly

### 14.2 Semantic overclaim

The main product risk is presenting approximate fallback output as true retained/dominator evidence.

Mitigation:

- preserve conservative fallback wording
- route all wording through shared orchestration rules
- keep confidence/limitations explicit

### 14.3 Advice coupling

If advice starts depending on engine-specific details, future engine evolution becomes painful.

Mitigation:

- translate through a normalized retention result
- keep engine internals behind the orchestration boundary

---

## 15. Rollout Summary

Phase 2 should deliver:

- a shared retention orchestration layer
- a new heavyweight retained-style engine
- `analysisDepth=deep` as the only trigger for heavyweight attempts
- safe fallback to Shark
- deep retention integration into offline advice only when explicitly requested

This keeps phase-1 defaults stable, improves deep-analysis capability, and moves retention evidence closer to shared advice workflows without introducing another public MCP surface.

---

## 16. Revision History

| Date | Change |
|------|--------|
| 2026-04-22 | Initial phase-2 design after phase-1 merge: dual-engine orchestration, deep-only heavyweight trigger, and bounded advice integration. |
