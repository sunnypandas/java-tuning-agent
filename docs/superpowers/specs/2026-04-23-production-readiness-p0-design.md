# JVM Tuning Agent Production Readiness P0 Design

**Date:** 2026-04-23
**Status:** Proposed for implementation planning
**Audience:** implementers of `java-tuning-agent` runtime collection, diagnosis, MCP schema, and documentation workflows

---

## 1. Context

`java-tuning-agent` now has a useful memory/GC diagnosis shape:

- live JVM discovery and lightweight `jcmd` / `jstat` inspection
- optional privileged evidence collection for class histograms, thread dumps, and heap dumps
- deterministic diagnosis rules producing `TuningAdviceReport`
- offline bundle validation and advice generation
- heap dump shallow summary and explicit deep retention analysis

The current implementation is still strongest as a single-shot memory/GC assistant. Real JVM tuning work usually needs repeated observations, bounded command execution, trend-aware diagnosis, and stable public tool contracts. P0 production readiness focuses on those foundations before adding heavier evidence sources such as JFR, GC log parsing, Native Memory Tracking, or async-profiler integration.

---

## 2. Goals

- Add a small repeated-sampling workflow so the tool can observe short trends instead of only one snapshot.
- Add execution guardrails around local diagnostic commands: timeout, output-size limit, duration metadata, and structured failure information.
- Add first-version trend-aware diagnosis rules using repeated samples.
- Add a schema/docs drift gate so MCP tool count, important parameters, and README descriptions stay synchronized with code.
- Preserve current single-shot APIs and behavior for existing clients.

---

## 3. Non-Goals

- Do not implement JFR collection or parsing in this P0 slice.
- Do not implement GC unified log parsing in this P0 slice.
- Do not implement Native Memory Tracking or direct-buffer diagnostics in this P0 slice.
- Do not replace the rule engine with an LLM-driven diagnosis engine.
- Do not change heap retention semantics or introduce another public retention tool.
- Do not build a long-running daemon, database, or persistent scheduler.

---

## 4. Chosen Approach

P0 uses an incremental design:

1. Keep existing single-shot MCP tools intact.
2. Add bounded command execution underneath all current collection paths.
3. Add an explicit repeated-sampling request/result model.
4. Add a new MCP tool for repeated sampling rather than overloading `inspectJvmRuntime`.
5. Thread repeated-sample evidence into the existing `MemoryGcDiagnosisEngine` through a small optional field on `MemoryGcEvidencePack`.
6. Add tests that fail when README/schema/tool descriptions drift from the exported tool surface.

This keeps the public surface understandable while giving future JFR, GC log, and native-memory work a reliable collection substrate.

---

## 5. Command Execution Guardrails

### 5.1 Current Problem

`SystemCommandExecutor` currently starts `ProcessBuilder`, reads all output, and waits without timeout. A stuck attach, very large output, blocked process, or slow external command can stall the MCP server.

### 5.2 Required Behavior

Introduce a command result abstraction:

- command arguments
- exit code
- stdout text, bounded by configured max bytes
- timed-out flag
- elapsed milliseconds
- truncated flag
- failure message when execution fails

Default limits:

- timeout: 15 seconds
- max output bytes: 8 MiB for ordinary commands
- max output bytes: 64 MiB for known large privileged commands such as `GC.class_histogram` and `Thread.print`

The first implementation may keep the existing `CommandExecutor#run(List<String>)` method by adapting the structured result into either stdout or an exception, but production code should internally use the structured result where warnings need to preserve cause and duration.

### 5.3 Failure Semantics

- Timeout kills the process and returns a structured timeout failure.
- Non-zero exit returns a structured failure with bounded output.
- Output truncation is not automatically fatal, but collection warnings must mention truncation.
- Interrupted execution restores interrupt status and returns or throws an interruption failure.

---

## 6. Repeated Sampling

### 6.1 New Request Model

Add `RepeatedSamplingRequest`:

- `pid`
- `sampleCount`
- `intervalMillis`
- `includeThreadCount`
- `includeClassCount`
- `confirmationToken`

Bounds:

- `sampleCount`: 2 to 20
- `intervalMillis`: 500 to 60_000
- total planned duration should default-limit to 5 minutes

The default recommended request is 3 samples with 10 seconds between samples.

### 6.2 New Result Model

Add `RepeatedSamplingResult`:

- `pid`
- `samples`
- `warnings`
- `missingData`
- `startedAtEpochMs`
- `elapsedMs`

Each sample should carry:

- timestamp
- `JvmMemorySnapshot`
- `JvmGcSnapshot`
- thread count when available
- loaded class count when available
- collection warnings for that sample

The result must be JSON-friendly and usable from MCP clients without reading Markdown.

### 6.3 New MCP Tool

Add `inspectJvmRuntimeRepeated`:

Purpose:

- collect repeated lightweight snapshots using safe read-only commands only
- no heap dump, class histogram, or thread dump
- return `RepeatedSamplingResult`

This tool should not require `confirmationToken` when it only uses safe read-only commands. The token field can remain in the request for future privileged repeated modes, but P0 should ignore or preserve it without requiring it.

---

## 7. Evidence Pack Integration

Extend `MemoryGcEvidencePack` with optional repeated samples.

Rules:

- Existing constructors or overloads must keep source compatibility where practical.
- Single-shot `generateTuningAdvice` behavior remains unchanged when repeated samples are absent.
- Offline repeated samples can be added later; P0 focuses on live repeated collection first.

`JavaTuningWorkflowService.generateAdviceFromEvidence` should pass the repeated samples to the rule engine through the evidence pack, with no special formatting until a trend rule fires.

---

## 8. Trend-Aware Diagnosis

Add a first-version `RepeatedSamplingTrendRule`.

It should emit conservative findings only when at least 3 samples are present or when 2 samples show a very strong change.

Initial signals:

- old-gen usage percent rises across samples and does not drop after young GC activity
- heap used bytes rises monotonically by a material amount
- full GC count increases during the sampling window
- young GC rate is high relative to elapsed time
- live thread count rises materially
- loaded class count rises materially

P0 should avoid precise leak claims. Wording should say "trend suggests" and recommend longer observation or stronger evidence when needed.

Expected report additions:

- finding title: `Repeated samples show rising heap pressure`
- finding title: `Repeated samples show elevated GC activity`
- finding title: `Repeated samples show growing runtime footprint`

Only add findings when evidence crosses thresholds; otherwise add a next step summarizing that no strong trend was detected.

---

## 9. Schema and Documentation Drift Gate

### 9.1 Current Problem

The README currently describes an older tool count and offline tool set. This can mislead MCP users and hides public-contract changes.

### 9.2 Required Checks

Add tests or a build-time verification helper that checks:

- exported MCP tool schema includes expected live and offline tool names
- README mentions every public MCP tool
- README tool count matches exported schema
- key public parameters are documented:
  - `analysisDepth`
  - `heapDumpAbsolutePath`
  - `confirmationToken`
  - repeated sampling request fields
- Cursor skill reference mentions the same public tool names

The check should run in `mvn test` so drift is caught before merge.

### 9.3 Documentation Updates

Update:

- `README.md`
- `.cursor/skills/java-tuning-agent-workflow/SKILL.md`
- `.cursor/skills/java-tuning-agent-workflow/reference.md`
- `docs/offline-mode-spec.md` if tool contract text changes

---

## 10. Configuration

Add configuration properties:

- `java-tuning-agent.command.default-timeout-ms`
- `java-tuning-agent.command.default-max-output-bytes`
- `java-tuning-agent.command.privileged-max-output-bytes`
- `java-tuning-agent.sampling.default-sample-count`
- `java-tuning-agent.sampling.default-interval-ms`
- `java-tuning-agent.sampling.max-sample-count`
- `java-tuning-agent.sampling.max-total-duration-ms`

Use conservative defaults so local MCP usage remains responsive.

---

## 11. Error Handling and User Experience

Repeated sampling must remain useful under partial failure:

- If one sample fails, keep successful samples and add a warning.
- If fewer than 2 samples succeed, return a result but mark trend analysis as unavailable.
- If the PID disappears mid-session, stop sampling and report that the target process exited or became unavailable.
- If commands time out, surface timeout warnings in the result and the final advice confidence reasons.

The report should not hide command failures behind generic "missing data" text.

---

## 12. Testing Strategy

Unit tests:

- command executor timeout and truncation behavior
- repeated request validation bounds
- repeated collector keeps partial samples on failure
- trend rule fires for rising heap / old-gen / GC activity
- trend rule stays quiet for stable samples
- schema/docs drift check fails when README omits a public tool

Integration-style tests:

- MCP tool registration includes `inspectJvmRuntimeRepeated`
- `generateAdviceFromEvidence` includes trend findings when repeated samples are present
- existing single-shot tests continue passing

Manual verification:

- run `mvn test`
- run `mvn -DskipTests package`
- launch MCP server and confirm tool list includes the new repeated-sampling tool

---

## 13. Risks

### 13.1 Sampling Cost

Repeated sampling can tie up the MCP request for seconds or minutes. P0 mitigates this by bounding sample count, interval, and total duration.

### 13.2 False Trend Claims

Short windows can mislead. P0 mitigates this with conservative wording and by recommending longer sampling or stronger evidence for borderline cases.

### 13.3 Backward Compatibility

Adding fields to records can break call sites. P0 should use overloads or helper factories to keep existing tests readable and avoid broad churn.

### 13.4 Platform Variance

`jcmd` and `jstat` output differs across JDKs and collectors. P0 should preserve partial results with warnings rather than failing the whole request.

---

## 14. Rollout Summary

P0 should deliver:

- bounded command execution
- a live repeated-sampling MCP tool
- trend-aware diagnosis from repeated lightweight samples
- schema/docs drift tests
- synchronized public docs and Cursor workflow references

This makes the project a more reliable JVM tuning foundation without taking on heavier profiler integrations in the same slice.

---

## 15. Revision History

| Date | Change |
|------|--------|
| 2026-04-23 | Initial P0 production-readiness design: repeated sampling, command guardrails, trend diagnosis, and schema/docs drift gate. |
