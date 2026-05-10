# Strengthen CPU Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the JVM tuning agent produce stronger Java-level high CPU evidence from JFR execution samples and `Thread.print` CPU-bearing thread headers.

**Architecture:** Keep the existing memory/GC evidence model and add a small thread CPU summary to `ThreadDumpSummary`. Feed that summary into a focused rule that reports RUNNABLE high-CPU thread suspects and lets existing source correlation map stack frames back to source files. Continue using current `jcmd`/JFR commands; no OS-level `top`, `ps`, or `pidstat` in this phase.

**Tech Stack:** Java 25-compatible source, JUnit 5, AssertJ, existing MCP schema export tests.

---

### Task 1: Thread CPU Parsing

**Files:**
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ThreadDumpSummary.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ThreadDumpParser.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ThreadDumpParserTest.java`

- [ ] Write a failing test that parses thread name, `cpu=...ms`, `nid`, state, and first application stack frame from `jcmd Thread.print`.
- [ ] Run `mvn -q -Dtest=ThreadDumpParserTest test` and confirm it fails because the summary has no CPU thread rows.
- [ ] Add a compact `ThreadCpuSample` record and extend `ThreadDumpSummary` with `topCpuThreads`.
- [ ] Update `ThreadDumpParser` to attach state and stack frame to the current thread header, sort CPU rows descending, and cap rows to a small fixed limit.
- [ ] Re-run `mvn -q -Dtest=ThreadDumpParserTest test`.

### Task 2: CPU Diagnosis Rule

**Files:**
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/ThreadCpuHotspotRule.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngineTest.java`

- [ ] Write a failing test that a RUNNABLE thread with high parsed CPU time produces a CPU finding and recommendation.
- [ ] Run the focused test and confirm it fails because no rule consumes thread CPU rows.
- [ ] Implement `ThreadCpuHotspotRule` with conservative thresholds and clear evidence text.
- [ ] Register the rule in `MemoryGcDiagnosisEngine.firstVersion()`.
- [ ] Re-run the focused diagnosis test.

### Task 3: Source Hotspot Correlation

**Files:**
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/source/SourceHotspotCorrelationService.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/source/SourceHotspotCorrelationServiceTest.java`

- [ ] Write a failing test that parsed thread CPU stack frames map to `SuspectedCodeHotspot`.
- [ ] Run the focused source correlation test and confirm it fails.
- [ ] Add CPU thread stack candidates ahead of generic thread dump candidates.
- [ ] Re-run the focused source correlation test.

### Task 4: Documentation And Verification

**Files:**
- Modify: `README.md`
- Modify: `.cursor/skills/java-tuning-agent-workflow/SKILL.md`
- Modify: `agent-pack/java-tuning-agent/skills/java-tuning-agent-workflow/SKILL.md`

- [ ] Update docs to state that CPU support is Java-level JFR/Thread.print evidence, not full OS CPU sampling.
- [ ] Run `mvn -q test`.
- [ ] Review `git diff --stat` and commit the completed CPU diagnostics enhancement.
