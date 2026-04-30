# JVM Tuning Agent MCP Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve the JVM tuning MCP based on a full online + offline diagnosis run so future agents can avoid target mix-ups, preserve all evidence, and produce more precise source-level conclusions.

**Architecture:** Keep the current 13-tool MCP surface stable unless a task explicitly says to change it. Prefer strengthening validators, evidence models, diagnosis rules, and report formatting inside the existing online/offline workflow. When a schema changes, update exported MCP descriptors, README, skill/reference docs, and contract tests in the same task.

**Tech Stack:** Java 25, Spring Boot 3.5, Spring AI MCP annotations, jcmd/jstat/JFR, Shark heap graph parsing, JUnit 5, AssertJ, Mockito.

---

## Status Legend

- `[ ]` Not started
- `[~]` In progress
- `[x]` Done
- `[!]` Blocked, with blocker noted inline

Keep this file as the source of truth when switching models. Update the checkbox next to each step as soon as it is completed. If a task is partially implemented, leave the task header unchecked and mark only completed steps.

## Context From The End-To-End Run

The following issues were observed while using the MCP through a complete diagnosis flow:

- The first offline export directory pointed to `com.alibaba.cloud.ai.examples.javatuning.JavaTuningAgentApplication`, while the user expected `com.alibaba.cloud.ai.compat.memoryleakdemo.MemoryLeakDemoApplication`. Existing validation accepted the bundle because B1-B6 existed.
- `recordJvmFlightRecording` started a 30 second JFR recording, returned early with `jfrFile` missing, then the `.jfr` appeared after the duration window completed.
- Repeated sampling, JFR, and `collectMemoryGcEvidence` had to be manually remembered and merged by the agent. The advice path can consume optional evidence, but the workflow has no first-class session bundle or merge helper.
- `suspectedCodeHotspots` are currently histogram-driven. Deep retention later showed `Object[] -> AllocationRecord.payload -> byte[]`, but the hotspot list still ranked direct histogram classes rather than source-level holder fields.
- `analysisDepth=deep` produced valuable retained-style evidence, but the report still needs clearer wording around approximate retained bytes, warnings, and exact holder/source mapping.
- Missing NMT/native evidence was reported, but the next-step guidance could be more actionable.
- Offline heap uploads and generated dump artifacts need clearer lifecycle and cleanup guidance for repeated local demos or shared machines.

## File Map

Primary implementation files:

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineDraftValidator.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineAnalysisService.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineEvidenceAssembler.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapDumpChunkRepository.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingProperties.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowService.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/source/LocalSourceHotspotFinder.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/HeapRetentionInsightsRule.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/EvidenceGapRule.java`

Primary tests:

- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineDraftValidatorTest.java`
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineAnalysisServiceTest.java`
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorJfrTest.java`
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePackTest.java`
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/source/LocalSourceHotspotFinderTest.java`
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/HeapRetentionInsightsRuleTest.java`
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpPublicDocumentationContractTest.java`

Docs and generated descriptors:

- `README.md`
- `docs/mcp-jvm-tuning-demo-walkthrough.md`
- `.cursor/skills/java-tuning-agent-workflow/SKILL.md`
- `.cursor/skills/java-tuning-agent-workflow/reference.md`
- `mcps/user-java-tuning-agent/tools/*.json`

## Recommended Execution Order

1. Task 1: offline target consistency guard
2. Task 2: JFR completion wait fix
3. Task 3: evidence merge/session ergonomics
4. Task 4: multi-source hotspot correlation
5. Task 5: robust offline snapshot parsing
6. Task 6: deep retention wording and confidence
7. Task 7: NMT/native guidance
8. Task 8: offline artifact cleanup
9. Task 9: docs, skill, schema, and demo refresh

Task 1 and Task 2 are highest priority because they directly prevent incorrect conclusions during live demos.

---

### Task 1: Add Offline Target Consistency Guard

**Status:** `[ ]`

**Why:** The offline validator accepted a complete bundle for the wrong JVM. A complete but wrong bundle is worse than an incomplete bundle because it produces confident-looking advice for the wrong source tree.

**Files:**

- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineTargetConsistencyAnalyzer.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineTargetConsistencyResult.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineAnalysisService.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineDraftValidator.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineTargetConsistencyAnalyzerTest.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineAnalysisServiceTest.java`

- [ ] **Step 1: Write analyzer tests for matching target**

Create `OfflineTargetConsistencyAnalyzerTest` with a test that builds an `OfflineBundleDraft` containing:

```text
jvmIdentityText: java_command: com.alibaba.cloud.ai.compat.memoryleakdemo.MemoryLeakDemoApplication
runtimeSnapshotText: targetPid: 1961 ... sun.rt.javaCommand="com.alibaba.cloud.ai.compat.memoryleakdemo.MemoryLeakDemoApplication"
classHistogram: inlineText starting with "1961:"
threadDump: inlineText starting with "1961:"
CodeContextSummary.applicationNames: ["MemoryLeakDemoApplication", "memory-leak-demo"]
CodeContextSummary.candidatePackages: ["com.alibaba.cloud.ai.compat.memoryleakdemo"]
```

Expected assertions:

```java
assertThat(result.targetMatched()).isTrue();
assertThat(result.warnings()).isEmpty();
assertThat(result.extractedJavaCommand()).contains("MemoryLeakDemoApplication");
assertThat(result.extractedPids()).contains(1961L);
```

- [ ] **Step 2: Write analyzer tests for mismatched target**

Add a second test where `jvmIdentityText` and `runtimeSnapshotText` contain `com.alibaba.cloud.ai.examples.javatuning.JavaTuningAgentApplication`, while `CodeContextSummary.applicationNames` still contains `MemoryLeakDemoApplication`.

Expected assertions:

```java
assertThat(result.targetMatched()).isFalse();
assertThat(result.warnings()).anyMatch(w -> w.contains("java_command"));
assertThat(result.warnings()).anyMatch(w -> w.contains("MemoryLeakDemoApplication"));
assertThat(result.warnings()).anyMatch(w -> w.contains("JavaTuningAgentApplication"));
```

- [ ] **Step 3: Implement `OfflineTargetConsistencyResult`**

Use a compact immutable record:

```java
public record OfflineTargetConsistencyResult(
		boolean targetMatched,
		String extractedJavaCommand,
		List<Long> extractedPids,
		List<String> warnings) {

	public OfflineTargetConsistencyResult {
		extractedJavaCommand = extractedJavaCommand == null ? "" : extractedJavaCommand;
		extractedPids = extractedPids == null ? List.of() : List.copyOf(extractedPids);
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
	}
}
```

- [ ] **Step 4: Implement `OfflineTargetConsistencyAnalyzer`**

Extractor behavior:

- Parse `java_command:` from `jvmIdentityText`.
- Parse `sun.rt.javaCommand="..."` from `runtimeSnapshotText`.
- Parse `targetPid: <pid>` from `runtimeSnapshotText`.
- Parse leading `<pid>:` from `classHistogram` and `threadDump`, supporting both `filePath` and `inlineText`.
- Compare extracted application names against `CodeContextSummary.applicationNames`.
- Compare extracted package names against `CodeContextSummary.candidatePackages`.
- If no context is supplied, return matched with a warning-free result rather than blocking.

Warnings should be explicit and actionable:

```text
Offline evidence target appears to be com.alibaba.cloud.ai.examples.javatuning.JavaTuningAgentApplication, but source context expects MemoryLeakDemoApplication / com.alibaba.cloud.ai.compat.memoryleakdemo.
```

- [ ] **Step 5: Integrate into `OfflineAnalysisService.generateOfflineAdvice`**

After validation and before `evidenceAssembler.build(draft)`, run the analyzer with the normalized `CodeContextSummary`.

If `targetMatched=false`:

- Add warnings to the final `MemoryGcEvidencePack.warnings()`.
- Add `offlineTargetConsistency` to `missingData()` only if the mismatch is strong.
- Do not fail by default in this task, to avoid a breaking schema/behavior change.

- [ ] **Step 6: Surface warnings in `validateOfflineAnalysisDraft` when context is unavailable**

Keep `validateOfflineAnalysisDraft` structural. Add PID consistency checks that do not require code context:

- B1 pid and B3 `targetPid` differ.
- B4 leading pid and B5 leading pid differ.

Add these to `degradationWarnings`, not `missingRequired`.

- [ ] **Step 7: Run tests**

Run:

```bash
mvn -q -Dtest=OfflineDraftValidatorTest,OfflineTargetConsistencyAnalyzerTest,OfflineAnalysisServiceTest test
```

Expected: all selected tests pass.

- [ ] **Step 8: Update docs**

Update:

- `README.md` offline section
- `.cursor/skills/java-tuning-agent-workflow/SKILL.md` offline mode section
- `.cursor/skills/java-tuning-agent-workflow/reference.md`

State that offline bundles now warn when runtime identity and supplied source context disagree.

**Acceptance:**

- A complete wrong-target offline bundle produces a strong warning before advice.
- A correct `MemoryLeakDemoApplication` bundle produces no target mismatch warning.
- Existing callers without `CodeContextSummary` continue to work.

---

### Task 2: Fix JFR Recording Completion Wait

**Status:** `[ ]`

**Why:** `jcmd JFR.start duration=30s filename=...` can return immediately while recording continues in the target JVM. The current tool can report `jfrFile` missing even though the file appears after the duration window.

**Files:**

- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingProperties.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorJfrTest.java`
- Docs: `README.md`

- [ ] **Step 1: Add failing JFR test for early-returning `JFR.start`**

In `SafeJvmRuntimeCollectorJfrTest`, add a test named:

```java
shouldWaitForRecordingDurationWhenJfrStartReturnsBeforeFileExists
```

Arrange:

- `JFR.start` command returns success with elapsed time near `10ms`.
- Output file is absent immediately after command execution.
- The fake sleeper creates the file after the collector sleeps for the remaining recording window.

Expected:

```java
assertThat(result.jfrPath()).isEqualTo(output.toString());
assertThat(result.missingData()).isEmpty();
assertThat(result.warnings()).doesNotContain("JFR recording command finished but file was not found");
```

- [ ] **Step 2: Refactor the test collector sleeper**

Current tests pass `SafeJvmRuntimeCollector::sleepUncheckedForTests`. Add a small test sleeper helper that records requested sleep durations and creates the target file when the collector waits for at least the remaining duration.

- [ ] **Step 3: Change wait budget calculation**

In `SafeJvmRuntimeCollector.recordJfr`, after `record = executor.execute(...)`, compute:

```java
long durationMs = normalized.durationSeconds() * 1000L;
long elapsedByCommandMs = Math.max(0L, record.elapsedMs());
long remainingRecordingMs = Math.max(0L, durationMs - elapsedByCommandMs);
long fileWaitBudgetMs = remainingRecordingMs + jfrRecordingProperties.completionGraceMs();
```

Then call:

```java
waitForStableFile(output, fileWaitBudgetMs)
```

instead of only waiting for `completionGraceMs`.

- [ ] **Step 4: Make the warning more accurate**

If the file is still missing after the full wait budget, include duration and path:

```text
JFR recording file was not found after waiting for durationSeconds=<N> plus completionGraceMs=<M> at <path>
```

- [ ] **Step 5: Consider `JFR.check` as a follow-up, not required here**

Do not add `JFR.check` in this task unless the simple wait budget still fails tests. Keep the implementation minimal.

- [ ] **Step 6: Run tests**

Run:

```bash
mvn -q -Dtest=JfrRecordingRequestTest,SafeJvmRuntimeCollectorJfrTest test
```

Expected: all JFR request and collector tests pass.

**Acceptance:**

- A 30 second JFR does not return missing file just because `JFR.start` returned early.
- Existing unsupported-JFR and missing-file tests still pass.
- README correctly describes that the tool waits for the requested duration window plus grace.

---

### Task 3: Add Evidence Merge Ergonomics

**Status:** `[ ]`

**Why:** Users often gather evidence in multiple calls: snapshot, repeated sampling, JFR, histogram, thread dump, heap dump. The final advice should not require the agent to manually reconstruct or remember every object.

**Files:**

- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidenceMerger.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/JavaTuningMcpTools.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowService.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidenceMergerTest.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`

- [ ] **Step 1: Write merger tests**

Create tests for:

- Base pack plus `RepeatedSamplingResult` preserves snapshot, histogram, thread dump, heap dump path.
- Base pack plus `JfrSummary` sets `jfrSummary`.
- Base pack plus `ResourceBudgetEvidence` sets `resourceBudgetEvidence`.
- Merging warnings and missing data removes duplicates while preserving order.
- Diagnosis windows merge into a single window covering the earliest start and latest end.

- [ ] **Step 2: Implement `MemoryGcEvidenceMerger`**

API:

```java
public final class MemoryGcEvidenceMerger {
	public MemoryGcEvidencePack merge(
			MemoryGcEvidencePack base,
			RepeatedSamplingResult repeated,
			JfrSummary jfr,
			ResourceBudgetEvidence resourceBudget,
			MemoryGcEvidencePack baseline) {
		...
	}
}
```

Rules:

- `base` is required.
- Optional values replace `null` fields only.
- Do not overwrite existing non-null evidence unless the caller explicitly passes a richer value and tests cover it.
- Combine warning/missing lists without duplicates.

- [ ] **Step 3: Improve `generateTuningAdviceFromEvidence` signature**

Add optional MCP params to `JavaTuningMcpTools.generateTuningAdviceFromEvidence`:

- `RepeatedSamplingResult repeatedSamplingResult`
- `JfrSummary jfrSummary`
- `ResourceBudgetEvidence resourceBudgetEvidence`
- `MemoryGcEvidencePack baselineEvidence`

Use the merger before calling `workflowService.generateAdviceFromEvidence`.

This changes the tool schema. Update generated descriptors and docs in Task 9.

- [ ] **Step 4: Keep backwards compatibility**

Existing calls with only:

```json
{
  "evidence": { ... },
  "codeContextSummary": { ... },
  "environment": "local",
  "optimizationGoal": "diagnose leak"
}
```

must still work.

- [ ] **Step 5: Run tests**

Run:

```bash
mvn -q -Dtest=MemoryGcEvidenceMergerTest,JavaTuningMcpToolsTest,McpToolSchemaContractTest test
```

Expected: all selected tests pass. If schema snapshot tests fail, regenerate descriptors and update docs in Task 9.

**Acceptance:**

- An agent can pass repeated/JFR/resource/baseline evidence directly into `generateTuningAdviceFromEvidence`.
- No manual JSON reconstruction is needed for common multi-call workflows.
- The report includes diagnosis context from the merged time window.

---

### Task 4: Upgrade Hotspot Correlation Beyond Histogram Classes

**Status:** `[ ]`

**Why:** Histogram classes often show `byte[]` or `int[]`, but source-level hotspots live in holders such as `AllocationRecord.payload`, `InMemoryLeakStore.retainedRecords`, or deadlock stack frames.

**Files:**

- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/source/SourceHotspotCorrelationService.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/source/LocalSourceHotspotFinder.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowService.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/SuspectedCodeHotspot.java` only if the current record cannot express evidence source cleanly.
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/source/SourceHotspotCorrelationServiceTest.java`

- [ ] **Step 1: Write tests for retention path to source file**

Build a synthetic `HeapRetentionAnalysisResult` with representative chain:

```text
unknown -> Object[].* -> AllocationRecord.payload -> byte[]
```

Use `sourceRoots=["/Users/panpan/Workspace/java-tuning-agent/compat/memory-leak-demo"]`.

Expected hotspot:

```text
className = com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord
fileHint = compat/memory-leak-demo/src/main/java/.../AllocationRecord.java
evidenceLink contains heap-retention
confidence is high or medium-high
```

- [ ] **Step 2: Write tests for thread dump stack to source file**

Build a `ThreadDumpSummary` with deadlock hints containing:

```text
com.alibaba.cloud.ai.compat.memoryleakdemo.deadlock.DeadlockDemoTrigger.holdThenWait(DeadlockDemoTrigger.java:46)
```

Expected hotspot:

```text
className = com.alibaba.cloud.ai.compat.memoryleakdemo.deadlock.DeadlockDemoTrigger
evidenceLink contains Thread.print
confidence = high
```

- [ ] **Step 3: Write tests for JFR stack to source file**

Build a `JfrSummary` with execution samples containing:

```text
com.alibaba.cloud.ai.compat.memoryleakdemo.churn.JfrWorkloadService.burnCpu(byte[])
```

Expected hotspot:

```text
className = com.alibaba.cloud.ai.compat.memoryleakdemo.churn.JfrWorkloadService
evidenceLink contains JFR
```

- [ ] **Step 4: Implement `SourceHotspotCorrelationService`**

Merge hotspot candidates from:

- Existing histogram mapping.
- Heap retention chains and suspected holders.
- Thread dump deadlock stack frames.
- JFR allocation, monitor-blocked, and execution stack frames.

Ranking order:

1. Critical liveness evidence: deadlock stack classes.
2. Deep retention holder/path classes.
3. JFR allocation/contention/execution stack classes.
4. Histogram classes under `candidatePackages`.
5. Non-framework histogram classes.

Deduplicate by normalized FQCN. Keep the first strongest evidence reason.

- [ ] **Step 5: Replace direct finder call in `JavaTuningWorkflowService`**

Current logic calls:

```java
sourceHotspotFinder.hotspotsFromHistogram(...)
```

Replace with the new correlation service so `generateAdviceFromEvidence` considers the full evidence pack.

- [ ] **Step 6: Run tests**

Run:

```bash
mvn -q -Dtest=LocalSourceHotspotFinderTest,SourceHotspotCorrelationServiceTest,JavaTuningWorkflowServiceTest test
```

Expected: existing histogram mapping still works, and new retention/thread/JFR hotspots are present.

**Acceptance:**

- Deep offline report for `memory-leak-demo` lists `AllocationRecord` and `InMemoryLeakStore` because of retention path evidence, not just histogram rank.
- Deadlock reports list `DeadlockDemoTrigger` with high confidence.
- JFR reports list `JfrWorkloadService` when stack samples mention it.

---

### Task 5: Make Offline Snapshot Parsing Robust To Extra Text

**Status:** `[ ]`

**Why:** A compressed runtime snapshot containing `heap.max=6442450944` caused generation to fail. Offline parsing should degrade on unknown lines instead of aborting the whole report.

**Files:**

- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineJvmSnapshotAssembler.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcHeapInfoParser.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JstatGcUtilParser.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineJvmSnapshotAssemblerTest.java`

- [ ] **Step 1: Add failing parser test**

Add a test with `runtimeSnapshotText` containing:

```text
targetPid: 1961
heap.max=268435456
G1 heap committed 262144K used 227391K
jstat -gcutil: YGC 8 YGCT 0.015 FGC 0 FGCT 0.000
```

Expected:

- No exception.
- `missingData` contains a parser warning if exact heap parsing fails.
- Snapshot still includes any fields that can be parsed.

- [ ] **Step 2: Wrap parser failures per section**

In `OfflineJvmSnapshotAssembler`, catch `RuntimeException` per parser invocation and append warning text like:

```text
Unable to parse GC.heap_info section: <message>
```

Do not abort the whole assembler unless every required runtime field is missing.

- [ ] **Step 3: Avoid `NumberFormatException` leakage**

Any parser that reads tokens from human text should return empty/partial results on malformed tokens instead of throwing `NumberFormatException` to the MCP caller.

- [ ] **Step 4: Run tests**

Run:

```bash
mvn -q -Dtest=OfflineJvmSnapshotAssemblerTest,GcHeapInfoParserTest,JstatGcUtilParserTest test
```

Expected: malformed extra lines degrade to warnings, valid standard exports still parse fully.

**Acceptance:**

- `generateOfflineTuningAdvice` does not fail because of extra key/value text in `runtimeSnapshotText`.
- The final report explicitly lists parser gaps in `missingData` or `warnings`.

---

### Task 6: Clarify Deep Retention Semantics And Confidence

**Status:** `[ ]`

**Why:** Deep retention is valuable but approximate. Reports should clearly distinguish MAT-exact retained size, bounded dominator-style retained approximation, and Shark path hints.

**Files:**

- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/HeapRetentionInsightsRule.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/DominatorStyleHeapRetentionAnalyzer.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionMarkdownRenderer.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/HeapRetentionInsightsRuleTest.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalysisOrchestratorTest.java`

- [ ] **Step 1: Add test for warning-aware severity**

If `engine=dominator-style` and warnings include local node budget truncation, keep the finding severity at `medium` but allow other corroborated leak rules to be `high`.

Expected evidence contains:

```text
retainedBytesApprox=<value>
bounded dominator approximation
warnings=<truncated warning sample>
```

- [ ] **Step 2: Add test for clean deep pass**

If `engine=dominator-style` and warnings are empty, `HeapRetentionInsightsRule` should mark the holder evidence as stronger. It can still say approximate, but the impact text should be more confident.

- [ ] **Step 3: Improve Markdown wording**

Use consistent phrases:

- `retained-style approximation`
- `reachable subgraph estimate`
- `not MAT exact retained size`

Avoid saying only `retained bytes` without the approximation qualifier.

- [ ] **Step 4: Include business holder mapping when available**

When a retention chain contains `AllocationRecord.payload`, show:

```text
Likely source holder: com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord.payload
```

This can be implemented directly in Task 4's correlation service or as a small addition here.

- [ ] **Step 5: Run tests**

Run:

```bash
mvn -q -Dtest=HeapRetentionInsightsRuleTest,HeapRetentionAnalysisOrchestratorTest,DominatorStyleHeapRetentionAnalyzerTest test
```

Expected: all selected tests pass and deep report wording is explicit about approximation limits.

**Acceptance:**

- Deep reports are stronger when corroborated, but never imply MAT precision.
- Truncation warnings are visible in findings, next steps, and confidence reasons.

---

### Task 7: Add Better NMT / Native Memory Guidance

**Status:** `[ ]`

**Why:** Missing native evidence is common. The report should tell users exactly how to rerun with NMT and what to collect, especially for direct buffer or metaspace scenarios.

**Files:**

- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/EvidenceGapRule.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/NativeMemoryPressureRule.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/DirectBufferPressureRule.java`
- Modify: `README.md`
- Modify: `docs/mcp-jvm-tuning-demo-walkthrough.md`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/EvidenceGapRuleTest.java`

- [ ] **Step 1: Add missing NMT next-step test**

When `missingData` contains `nativeMemorySummary`, expected next steps include:

```text
Restart with -XX:NativeMemoryTracking=summary
Collect jcmd <pid> VM.native_memory summary
Use collectMemoryGcEvidence or offline nativeMemorySummary
```

- [ ] **Step 2: Add direct buffer guidance**

If class histogram or heap shallow summary shows `java.nio.ByteBuffer[]` but `nativeMemorySummary` is missing, add a targeted next step:

```text
Direct buffer pressure cannot be confirmed without NMT NIO category; rerun with -XX:NativeMemoryTracking=summary.
```

- [ ] **Step 3: Add metaspace/classloader guidance**

If class count grows, `ClassloaderPressureStore$DemoProxyClassLoader` appears, or metaspace is high, and native summary is missing, add:

```text
Collect NMT Class category to distinguish class metadata pressure from heap retention.
```

- [ ] **Step 4: Run tests**

Run:

```bash
mvn -q -Dtest=EvidenceGapRuleTest,DirectBufferPressureRuleTest,MetaspacePressureRuleTest test
```

Expected: missing NMT guidance appears only when relevant and does not duplicate existing missing-data text.

**Acceptance:**

- Reports include command-level guidance for missing NMT.
- Direct buffer and classloader/metaspace cases get distinct next steps.

---

### Task 8: Add Offline Heap Dump Upload Cleanup And Disk Hygiene

**Status:** `[ ]`

**Why:** Offline heap dump chunks and finalized dumps can be large. Repeated demos or shared machines need a cleanup story.

**Files:**

- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapDumpChunkRepository.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpTools.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapDumpChunkRepositoryTest.java`
- Docs: `README.md`

- [ ] **Step 1: Add repository tests for TTL cleanup**

Test cases:

- Incomplete upload older than TTL is deleted.
- Recent incomplete upload is kept.
- Finalized heap dump is not deleted unless cleanup is explicitly configured to include finalized files.

- [ ] **Step 2: Add configuration properties**

Add properties:

```yaml
java-tuning-agent:
  offline:
    heap-dump-upload:
      ttl-seconds: 86400
      cleanup-finalized: false
```

Bind them in the existing configuration class or a dedicated properties record.

- [ ] **Step 3: Implement cleanup method**

Add:

```java
public HeapDumpCleanupResult cleanupExpiredUploads(Instant now)
```

The result should include:

- deleted upload count
- deleted bytes
- retained upload count
- warnings

- [ ] **Step 4: Decide MCP exposure**

Prefer adding cleanup to existing lifecycle if possible. If adding a new MCP tool, document that the public surface becomes 14 tools and update every contract. If avoiding a new tool, run cleanup opportunistically when creating a new upload.

Recommended first implementation: opportunistic cleanup in `createUpload`.

- [ ] **Step 5: Run tests**

Run:

```bash
mvn -q -Dtest=HeapDumpChunkRepositoryTest,OfflineMcpToolsRetentionTest test
```

Expected: cleanup behavior is deterministic and does not delete finalized dumps by default.

**Acceptance:**

- Stale chunk uploads do not accumulate indefinitely.
- Cleanup behavior is configurable and documented.

---

### Task 9: Refresh Docs, Skill, Generated Schemas, And Demo Script

**Status:** `[ ]`

**Why:** This project relies on MCP schema descriptors, README, demo docs, and the Cursor skill staying in sync. Any tool signature or behavior change must be visible to future agents.

**Files:**

- Modify: `README.md`
- Modify: `docs/mcp-jvm-tuning-demo-walkthrough.md`
- Modify: `.cursor/skills/java-tuning-agent-workflow/SKILL.md`
- Modify: `.cursor/skills/java-tuning-agent-workflow/reference.md`
- Modify generated descriptors under `mcps/user-java-tuning-agent/tools/*.json`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpPublicDocumentationContractTest.java`

- [ ] **Step 1: Regenerate MCP descriptors if schemas changed**

Use the existing export runner or project command used by this repository. If unsure, inspect `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/export/McpToolSchemaExportRunner.java` and existing test instructions.

Expected changed files:

- `mcps/user-java-tuning-agent/tools/generateTuningAdviceFromEvidence.json` if Task 3 changes the signature.
- Other tool descriptors only if tasks changed tool parameters or added tools.

- [ ] **Step 2: Update README**

Add sections for:

- Offline target consistency warnings.
- JFR recording waits for duration plus grace.
- Evidence merge ergonomics.
- Multi-source `suspectedCodeHotspots`.
- Deep retention approximation wording.
- NMT missing evidence guidance.
- Offline heap upload cleanup.

- [ ] **Step 3: Update the workflow skill**

In `.cursor/skills/java-tuning-agent-workflow/SKILL.md`, update:

- Offline mode target consistency: verify `java_command` and source context.
- JFR recording: if a recording returns missing file, the tool should normally have waited; manual polling should be a fallback, not the expected path.
- Evidence reuse: pass repeated/JFR/resource evidence into advice instead of summarizing manually.

- [ ] **Step 4: Update demo walkthrough**

In `docs/mcp-jvm-tuning-demo-walkthrough.md`, add a short section after offline export:

```text
Before generating offline advice, confirm B1 java_command matches the demo app:
com.alibaba.cloud.ai.compat.memoryleakdemo.MemoryLeakDemoApplication
```

Also add an example of `analysisDepth=deep` producing:

```text
Object[] -> AllocationRecord.payload -> byte[]
```

- [ ] **Step 5: Run documentation and schema tests**

Run:

```bash
mvn -q -Dtest=McpToolSchemaContractTest,McpPublicDocumentationContractTest test
```

Expected: public tool count, descriptions, and docs are aligned.

**Acceptance:**

- A fresh agent can read README + skill + this TODO and correctly run online and offline workflows.
- MCP descriptors match Java tool signatures.
- Demo doc explicitly prevents the wrong-JVM offline bundle mistake.

---

## Cross-Task Verification Checklist

Run this after completing any subset that touches Java behavior:

```bash
mvn -q test
```

If full tests are too slow, run the impacted group first:

```bash
mvn -q -Dtest=OfflineDraftValidatorTest,OfflineTargetConsistencyAnalyzerTest,OfflineAnalysisServiceTest test
mvn -q -Dtest=SafeJvmRuntimeCollectorJfrTest,JfrRecordingRequestTest test
mvn -q -Dtest=MemoryGcEvidenceMergerTest,JavaTuningMcpToolsTest test
mvn -q -Dtest=SourceHotspotCorrelationServiceTest,LocalSourceHotspotFinderTest test
mvn -q -Dtest=HeapRetentionInsightsRuleTest,HeapRetentionAnalysisOrchestratorTest test
mvn -q -Dtest=McpToolSchemaContractTest,McpPublicDocumentationContractTest test
```

Before claiming completion:

- [ ] Update this TODO file's checkboxes.
- [ ] Mention any schema changes and regenerated descriptor files.
- [ ] Mention any behavior changes that affect the 13-tool public surface.
- [ ] Include exact tests run and whether they passed.

## Current Overall Status

- [ ] Task 1: Offline target consistency guard
- [ ] Task 2: JFR completion wait fix
- [ ] Task 3: Evidence merge ergonomics
- [ ] Task 4: Multi-source hotspot correlation
- [ ] Task 5: Robust offline snapshot parsing
- [ ] Task 6: Deep retention semantics and confidence wording
- [ ] Task 7: NMT/native guidance
- [ ] Task 8: Offline heap dump upload cleanup
- [ ] Task 9: Docs, skill, generated schemas, and demo refresh

No implementation has been completed in this document yet; it is the handoff plan for future implementation work.
