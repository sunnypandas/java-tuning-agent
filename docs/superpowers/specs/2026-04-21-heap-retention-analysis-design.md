# Heap Retention Analysis Design

**Date:** 2026-04-21  
**Status:** Draft for review  
**Audience:** implementers of `java-tuning-agent` offline and shared heap-analysis capabilities

---

## 1. Context

The current heap-dump integration parses `.hprof` files with Shark and produces a bounded shallow-by-class summary:

- `HeapDumpShallowSummary`
- `HeapShallowClassEntry`
- `summarizeOfflineHeapDumpFile`

This is useful for answering:

- which types dominate shallow bytes
- whether arrays such as `byte[]` are unusually large
- whether a heap dump is worth deeper inspection

It is not sufficient for answering:

- which holder is retaining those objects
- which field or container path keeps them alive
- which GC-root category is involved
- whether a large shallow leader is also a retained leader

The current reports and rules already acknowledge this limitation and send users to MAT / VisualVM dominator analysis for deeper inspection.

The next step is to add a retention-oriented capability that remains compatible with the current shallow path, but produces structured evidence closer to real holder / dominator relationships.

---

## 2. Goals

### 2.1 Product goals

- Add a retention-oriented heap analysis capability for `.hprof` files.
- Keep current Shark shallow summary behavior intact and semantically unchanged.
- Produce structured JSON evidence first, with Markdown as a rendered view.
- Design the model so offline analysis and future shared evidence packs can reuse the same schema.
- Prefer results that are closer to real holder / retained relationships, even if analysis is slower.

### 2.2 Non-goals for the first version

- Exact source line allocation attribution.
- Full Eclipse MAT feature parity.
- Full GC-roots explorer UI.
- Automatic inclusion into all existing advice flows on day one.
- Framework-specific leak detectors beyond a small set of stable holder categories.

---

## 3. Recommended approach

### 3.1 Layered capability model

Use two explicit layers instead of overloading the current heap summary:

1. `shallow summary`
2. `retention analysis`

The existing shallow path remains:

- fast
- bounded
- easy to explain
- clearly non-dominator

The new retention path is a separate capability:

- slower but more diagnostic
- holder-oriented
- closer to retained / dominator semantics
- allowed to degrade when deep analysis is incomplete

### 3.2 Architecture choice

Use a **hybrid** design:

- Keep Shark for heap opening, object access, shallow statistics, and path extraction helpers.
- Add a heavier retention-analysis path for holder / dominator-style reasoning.
- Normalize both paths into one shared evidence model.

This keeps the current investment in Shark while creating room for more faithful retention analysis.

---

## 4. Shared evidence model

### 4.1 Top-level model

Introduce a shared heap-analysis evidence object that can be reused by offline analysis first and by common evidence packs later.

Suggested top-level shape:

```json
{
  "analysisKind": "combined",
  "source": "offline-hprof",
  "heapDumpPath": "C:/diag/example.hprof",
  "engine": "hybrid",
  "analysisSucceeded": true,
  "warnings": [],
  "errorMessage": "",
  "shallowSummary": {},
  "retentionSummary": {}
}
```

Suggested semantics:

- `analysisKind`: `shallow`, `retention`, or `combined`
- `source`: origin of the heap dump, e.g. offline imported or online captured
- `engine`: `shark`, `hybrid`, or a future heavier engine identifier
- `warnings`: degradations, partial results, skipped passes, or unsupported shapes

### 4.2 Shallow section

Reuse the current shallow summary semantics and naming where practical:

- `topByShallowBytes`
- `totalTrackedShallowBytes`
- `truncated`
- `summaryMarkdown`
- `errorMessage`

This section remains explicitly non-retained and non-dominator.

### 4.3 Retention section

The retention section is the new structured evidence payload.

Suggested fields:

- `dominantRetainedTypes`
- `suspectedHolders`
- `retentionChains`
- `gcRootHints`
- `confidenceAndLimits`
- `summaryMarkdown`
- `analysisSucceeded`
- `warnings`
- `errorMessage`

---

## 5. RetentionSummary detail

### 5.1 dominantRetainedTypes

Purpose: answer "what is being retained".

Suggested item fields:

- `typeName`
- `retainedBytesApprox`
- `objectCountApprox`
- `shareOfTrackedRetainedApprox`
- `terminalShallowBytes`

Notes:

- first version may leave `retainedBytesApprox` empty when the underlying engine cannot justify it
- `terminalShallowBytes` remains a stable fallback signal

### 5.2 suspectedHolders

Purpose: answer "who is holding it".

Suggested item fields:

- `holderType`
- `holderRole`
- `retainedBytesApprox`
- `reachableSubgraphBytesApprox`
- `retainedObjectCountApprox`
- `exampleFieldPath`
- `exampleTargetType`
- `notes`

Recommended first-version `holderRole` values:

- `static-field-owner`
- `collection`
- `map`
- `thread-local`
- `thread-owner`
- `array-owner`
- `unknown`

### 5.3 retentionChains

Purpose: answer "how is it retained".

Suggested item fields:

- `rootKind`
- `segments`
- `terminalType`
- `terminalShallowBytes`
- `chainCountApprox`
- `retainedBytesApprox`
- `reachableSubgraphBytesApprox`

Suggested `segments` fields:

- `ownerType`
- `referenceKind`
- `referenceName`
- `targetType`

Important rendering rule:

- collapse noisy JDK container internals where possible
- preserve holder-significant field names
- aggregate equivalent chains into templates rather than listing every raw path

Example external rendering:

`system-class -> RetainedByteArrayStore.retained -> byte[]`

Instead of exposing every internal `ArrayList.elementData` hop.

### 5.4 gcRootHints

Purpose: answer "what kind of root is nearby".

Suggested item fields:

- `rootKind`
- `exampleOwnerType`
- `occurrenceCountApprox`
- `notes`

Examples:

- `system-class`
- `thread-object`
- `jni-global`
- `sticky-class`

This is a hint section, not a full root explorer.

### 5.5 confidenceAndLimits

Purpose: prevent false precision.

Suggested fields:

- `confidence`
- `limitations`
- `engineNotes`

The first version must explicitly state whether retained values are:

- unavailable
- approximate
- partial
- produced by a dominator-style pass

---

## 6. Byte accounting rules

The design must distinguish byte metrics with different semantics.

### 6.1 Stable metrics

- `terminalShallowBytes`
- `reachableSubgraphBytesApprox`

These are the safest first-version metrics.

### 6.2 Conditional metric

- `retainedBytesApprox`

This field should only be populated when the deeper engine has a defensible retained-style meaning.

If that is not true for a result, leave it empty rather than mislabeling subgraph size as retained size.

### 6.3 Sorting recommendation

Suggested ranking order:

1. `retainedBytesApprox`
2. `reachableSubgraphBytesApprox`
3. `terminalShallowBytes`

This lets the system prefer stronger evidence when available without lying about precision.

---

## 7. Analysis pipeline

### 7.1 End-to-end stages

Run the analysis in six stages:

1. `load`
2. `shallow pass`
3. `candidate selection`
4. `retention pass`
5. `normalization`
6. `rendering`

### 7.2 Stage responsibilities

`load`

- open the heap dump
- initialize graph access
- fail fast on unreadable or invalid dumps

`shallow pass`

- produce the current shallow-by-class evidence
- provide baseline signals for later target selection

`candidate selection`

- prioritize large arrays such as `byte[]`
- prioritize large collections and maps
- prioritize graphs near static fields, threads, and thread locals

`retention pass`

- run the heavier holder / retained analysis
- collect holder candidates
- build representative chains
- compute approximate retained values when justified

`normalization`

- collapse low-value implementation details
- classify holder roles
- merge equivalent chains
- standardize approximate metrics

`rendering`

- convert JSON evidence into readable Markdown
- automatically use downgraded wording when retained values are not available

### 7.3 Degradation rules

If shallow succeeds but retention fails:

- keep `analysisKind=combined`
- return shallow evidence
- mark `retentionSummary.analysisSucceeded=false`
- state that only shallow evidence is available

If retention partially succeeds:

- return partial structured evidence
- lower confidence
- list limitations and warnings

If the heavy engine is unavailable:

- allow a Shark-only fallback for hints
- do not fabricate `retainedBytesApprox`

---

## 8. MCP contract

### 8.1 Keep the current tool unchanged

Do not change the meaning of:

- `summarizeOfflineHeapDumpFile`

It remains:

- shallow only
- bounded
- non-dominator
- suitable for quick preview

### 8.2 Add a new tool

Introduce a separate MCP tool for retention-oriented analysis.

Suggested name:

- `analyzeOfflineHeapRetention`

This name may be finalized later, but the separation of concerns is required.

### 8.3 Suggested input

- `heapDumpAbsolutePath`
- `topObjectLimit`
- `maxOutputChars`
- `analysisDepth`
- `focusTypes`
- `focusPackages`

Notes:

- keep first-version knobs intentionally small
- avoid exposing internal engine complexity in the public schema

### 8.4 Suggested output

```json
{
  "analysisSucceeded": true,
  "engine": "hybrid",
  "warnings": [],
  "errorMessage": "",
  "retentionSummary": {},
  "summaryMarkdown": ""
}
```

The structured retention payload is the primary contract. Markdown is a convenience view.

### 8.5 Integration plan

Phase 1:

- tool exists independently
- offline flows can call it explicitly
- reports can render it explicitly

Phase 2:

- add `heapRetentionSummary` into the shared evidence pack
- allow online and offline advice generation to consume it

This avoids destabilizing the current advice flows too early.

---

## 9. Implementation priorities

First version should optimize for these holder classes:

1. static field owners
2. collections
3. maps
4. thread locals
5. thread-owned graphs
6. fallback array/object holders

Not first-version priorities:

- framework-specific deep recognizers
- full classloader leak taxonomy
- exhaustive JNI root analysis
- allocation line attribution

---

## 10. Reporting rules

Structured JSON is the source of truth. Markdown is a rendering layer.

The best human-readable report should:

- clearly separate shallow evidence from retention evidence
- use retained wording only when retained-style values exist
- otherwise say `reachable subgraph` or `retention hint`
- show representative chains, not raw graph dumps

This preserves readability without overstating certainty.

---

## 11. Risks and mitigations

### 11.1 False precision

Risk:

- reporting approximate subgraph size as retained size

Mitigation:

- use explicit `Approx` naming
- leave retained fields empty when not justified
- require confidence and limitation sections

### 11.2 Tool confusion

Risk:

- callers confuse shallow summary with retention analysis

Mitigation:

- keep separate MCP tools
- keep separate evidence sections
- document shallow tool as non-dominator

### 11.3 Runtime cost

Risk:

- deep analysis is slow or memory-heavy

Mitigation:

- allow analysis depth settings
- support partial results
- preserve shallow-only fallback

---

## 12. Recommendation

Adopt the layered design:

- preserve current Shark shallow summary as-is
- add a new retention-oriented analysis tool
- normalize everything into a shared JSON evidence model
- evolve toward common evidence-pack integration after the retention contract stabilizes

This gives the project a path from "what is large" to "who is retaining it" without breaking current behavior or blurring evidence semantics.
