# JFR Short Recording Design

**Date:** 2026-04-24
**Status:** Approved for implementation planning
**Audience:** implementers of `java-tuning-agent` runtime collection, JFR parsing, MCP schema, and documentation workflows

---

## 1. Context

`java-tuning-agent` currently supports live JVM discovery, lightweight `jcmd` / `jstat`
inspection, repeated safe sampling, privileged memory/GC evidence collection, and offline
heap analysis. JFR is already named in the README as privileged diagnostics, and
`RuntimeCollectionPolicy.CollectionRequest` already has an `includeJfr` slot, but the
public MCP surface does not yet expose JFR collection or summaries.

The first JFR slice should add practical profiling evidence without turning the project
into a full recording lifecycle manager. The chosen direction is a short, one-shot JFR
recording tool that produces a `.jfr` file and a lightweight structured summary.

Oracle's JDK troubleshooting documentation describes Java Flight Recorder as a low-overhead
profiling and diagnostics facility and documents `jcmd` diagnostic commands such as
`JFR.start`, `JFR.check`, `JFR.dump`, and `JFR.stop` for managing recordings. The JDK also
ships `jdk.jfr.consumer.RecordingFile`, which can read `.jfr` event streams without adding
an external profiler dependency.

References:

- <https://docs.oracle.com/en/java/javase/12/troubleshoot/diagnostic-tools.html>
- <https://docs.oracle.com/en/java/javase/20/troubleshoot/diagnostic-tools.html>

---

## 2. Goals

- Add a dedicated live MCP tool that records one short JFR session for a target PID.
- Require explicit user approval through `confirmationToken`, matching existing privileged
  diagnostics policy.
- Write the recording to a caller-supplied absolute `.jfr` path without overwriting existing
  evidence.
- Parse the resulting `.jfr` file with JDK APIs and return a bounded structured summary.
- Keep this first phase separate from `collectMemoryGcEvidence` and `generateTuningAdvice`.
- Synchronize README, Cursor workflow docs, and schema/docs drift tests.

---

## 3. Non-Goals

- Do not add long-running recording lifecycle tools such as public `start`, `check`, `dump`,
  or `stop` MCP operations.
- Do not attach JFR evidence to `MemoryGcEvidencePack` in this phase.
- Do not make `generateTuningAdvice` consume JFR summaries in this phase.
- Do not support custom `.jfc` template paths in this phase.
- Do not enable `path-to-gc-roots`.
- Do not parse every JFR event category; focus on GC, allocation, thread/lock, and execution
  sample summaries.
- Do not overwrite an existing `.jfr` file.

---

## 4. Chosen Approach

Add one new live tool:

```text
recordJvmFlightRecording(request: JfrRecordingRequest): JfrRecordingResult
```

The tool starts one time-bounded recording with `jcmd`, waits for the target file to appear,
parses the file into `JfrSummary`, and returns both the file path and the summary.

This approach keeps the API clear:

- `inspectJvmRuntime` and `inspectJvmRuntimeRepeated` remain lightweight, safe read-only
  inspection tools.
- `collectMemoryGcEvidence` remains focused on memory/GC artifacts such as histograms,
  thread dumps, and heap dumps.
- `recordJvmFlightRecording` becomes the first live profiling evidence tool.

---

## 5. Request Model

Create `JfrRecordingRequest`:

```java
public record JfrRecordingRequest(
        long pid,
        Integer durationSeconds,
        String settings,
        String jfrOutputPath,
        Integer maxSummaryEvents,
        String confirmationToken) {
}
```

Validation and normalization:

- `pid` must be positive and should come from `listJavaApps`.
- `confirmationToken` is required and must be non-blank.
- `jfrOutputPath` is required, must be absolute, must normalize to a `.jfr` path, and must
  not already exist.
- The parent directory must already exist. The first version should fail fast rather than
  create directories.
- `durationSeconds` defaults to 30 when null and must be within 5 to 300 seconds.
- `settings` defaults to `profile` when blank and must be `default` or `profile`.
- `maxSummaryEvents` defaults to an implementation property, and must be positive.

Configuration properties:

- `java-tuning-agent.jfr.default-duration-seconds=30`
- `java-tuning-agent.jfr.min-duration-seconds=5`
- `java-tuning-agent.jfr.max-duration-seconds=300`
- `java-tuning-agent.jfr.completion-grace-ms=10000`
- `java-tuning-agent.jfr.default-max-summary-events=200000`
- `java-tuning-agent.jfr.top-limit=10`

---

## 6. Result Model

Create `JfrRecordingResult`:

```java
public record JfrRecordingResult(
        long pid,
        String jfrPath,
        long fileSizeBytes,
        long startedAtEpochMs,
        long elapsedMs,
        List<String> commandsRun,
        JfrSummary summary,
        List<String> warnings,
        List<String> missingData) {
}
```

Rules:

- `jfrPath` is populated only when the file exists.
- `fileSizeBytes` is zero when the file is missing.
- `commandsRun` includes `JFR.start` support probing and the recording command.
- `summary` may be null when recording failed or parsing was impossible.
- Partial failures should return a structured result with warnings rather than throwing after
  command validation has passed.
- Validation failures should throw `IllegalArgumentException`, consistent with current
  privileged evidence behavior.

---

## 7. Recording Strategy

Use one short recording command:

```text
jcmd <pid> JFR.start name=<generated-name> settings=<default|profile> duration=<Ns> filename=<absolute-path> disk=true
```

Recording name format:

```text
java-tuning-agent-<pid>-<epochMs>
```

Execution flow:

1. Validate and normalize `JfrRecordingRequest`.
2. Probe JFR support with:

   ```text
   jcmd <pid> help JFR.start
   ```

3. If unsupported, return `missingData=["jfrSupport", "jfrRecording"]` and a warning that
   JFR is not available on the target JVM.
4. Run `JFR.start` with a command timeout of `durationSeconds * 1000 + completionGraceMs`.
5. Wait for the `.jfr` file to exist and have a stable non-zero size within the same grace
   window.
6. Parse the file into `JfrSummary`.
7. Return the result.

The tool should not call `JFR.stop` in the normal path because `duration=<Ns>` and
`filename=<path>` make this a bounded one-shot operation. If `JFR.start` returns a failure
after starting a recording, the first version may report the failure and include the generated
recording name in warnings, but it should not expose lifecycle management publicly.

---

## 8. Summary Model

Create `JfrSummary`:

```java
public record JfrSummary(
        Long recordingStartEpochMs,
        Long recordingEndEpochMs,
        Long durationMs,
        JfrGcSummary gcSummary,
        JfrAllocationSummary allocationSummary,
        JfrThreadSummary threadSummary,
        JfrExecutionSampleSummary executionSampleSummary,
        Map<String, Long> eventCounts,
        List<String> parserWarnings) {
}
```

The parser uses `jdk.jfr.consumer.RecordingFile`.

### 8.1 GC Summary

Create `JfrGcSummary`:

```java
public record JfrGcSummary(
        long gcCount,
        double totalGcPauseMs,
        double maxGcPauseMs,
        List<JfrCount> topGcCauses,
        List<JfrHeapSample> heapBeforeAfterSamples) {
}
```

Primary events:

- `jdk.GarbageCollection`
- `jdk.GCHeapSummary`, when present

The parser should tolerate missing fields and vendor variation by adding parser warnings
instead of failing the full summary.

### 8.2 Allocation Summary

Create `JfrAllocationSummary`:

```java
public record JfrAllocationSummary(
        long totalAllocationBytesApprox,
        List<JfrCountAndBytes> topAllocatedClasses,
        List<JfrStackAggregate> topAllocationStacks,
        long allocationEventCount) {
}
```

Primary events:

- `jdk.ObjectAllocationInNewTLAB`
- `jdk.ObjectAllocationOutsideTLAB`

If allocation events are absent, add a parser warning such as:

```text
JFR allocation events were not present; use settings=profile or custom templates later for allocation detail.
```

This is not a collection failure because some settings and JDKs legitimately omit these events.

### 8.3 Thread And Lock Summary

Create `JfrThreadSummary`:

```java
public record JfrThreadSummary(
        long parkEventCount,
        long monitorEnterEventCount,
        double maxBlockedMs,
        List<JfrThreadBlockAggregate> topBlockedThreads) {
}
```

Primary events:

- `jdk.ThreadPark`
- `jdk.JavaMonitorEnter`

The first version aggregates high-level blocking signals only. It does not reconstruct thread
state timelines.

### 8.4 Execution Sample Summary

Create `JfrExecutionSampleSummary`:

```java
public record JfrExecutionSampleSummary(
        long sampleCount,
        List<JfrStackAggregate> topMethods) {
}
```

Primary event:

- `jdk.ExecutionSample`

Aggregation should be by representative Java method from the stack trace. If stack frames are
missing or native-only, count the event but skip method aggregation.

### 8.5 Shared Aggregate Types

Use small JSON-friendly records:

```java
public record JfrCount(String name, long count) {}

public record JfrCountAndBytes(String name, long count, long bytesApprox) {}

public record JfrHeapSample(
        Long timestampEpochMs,
        Long beforeUsedBytes,
        Long afterUsedBytes,
        Long heapUsedBytes,
        Long heapCommittedBytes) {}

public record JfrStackAggregate(
        String frame,
        long count,
        long bytesApprox,
        List<String> sampleStack) {}

public record JfrThreadBlockAggregate(
        String threadName,
        long count,
        double totalBlockedMs,
        double maxBlockedMs,
        List<String> sampleStack) {}
```

Top lists should default to the configured `topLimit` and be deterministic under ties.

---

## 9. Parser Boundaries

The parser must be defensive:

- Keep `eventCounts` for every observed event type so users can tell whether a recording was
  empty or merely lacked one category.
- Stop or degrade when `maxSummaryEvents` is reached, and add a parser warning.
- Do not include raw event lists in the MCP response.
- Keep stack samples short, for example 8 frames per aggregate.
- Prefer explicit warnings over exceptions for per-event field mismatches.
- Throw only for unreadable files, invalid paths, or structurally impossible parser setup.

---

## 10. Error Handling

Validation errors:

- Missing `confirmationToken`
- Invalid duration
- Unsupported `settings`
- Non-absolute output path
- Non-`.jfr` output path
- Existing output file
- Missing parent directory

These should fail fast with `IllegalArgumentException`.

Runtime collection errors:

- Target JVM does not support JFR
- Attach denied
- Command timeout
- Non-zero `jcmd` exit
- Recording command succeeds but file is missing
- File exists but parser fails

These should return `JfrRecordingResult` with `warnings` and `missingData` when possible.

Suggested missing data IDs:

- `jfrSupport`
- `jfrRecording`
- `jfrFile`
- `jfrSummary`
- `jfrAllocationEvents`
- `jfrExecutionSamples`
- `jfrThreadEvents`
- `jfrGcEvents`

---

## 11. Integration Points

### 11.1 Runtime Package

Add under `runtime`:

- `JfrRecordingRequest`
- `JfrRecordingResult`
- `JfrSummary`
- summary sub-records
- `JfrSummaryParser`
- `JfrRecordingProperties`

Extend `JvmRuntimeCollector` with a default method:

```java
default JfrRecordingResult recordJfr(JfrRecordingRequest request) {
    throw new UnsupportedOperationException("JFR recording is not supported by this collector implementation");
}
```

Implement it in `SafeJvmRuntimeCollector`.

### 11.2 MCP Tool

Add to `JavaTuningMcpTools`:

```java
@Tool(description = """
        Record one short Java Flight Recorder session for a target JVM and return the .jfr path plus a lightweight summary.
        Requires a non-blank confirmationToken and an absolute jfrOutputPath ending in .jfr.
        """)
public JfrRecordingResult recordJvmFlightRecording(
        @ToolParam(description = "JfrRecordingRequest JSON: pid, durationSeconds, settings, jfrOutputPath, maxSummaryEvents, confirmationToken.")
        JfrRecordingRequest request) {
    return collector.recordJfr(request);
}
```

### 11.3 Configuration

Wire `JfrRecordingProperties` through `JavaTuningAgentConfig` into `SafeJvmRuntimeCollector`.

### 11.4 Documentation

Update:

- `README.md`
- `.cursor/skills/java-tuning-agent-workflow/SKILL.md`
- `.cursor/skills/java-tuning-agent-workflow/reference.md`
- MCP public documentation/schema drift tests

---

## 12. Testing Strategy

Unit tests:

- `JfrRecordingRequest` normalization and validation.
- `JfrSummaryParser` on a generated small recording or compact fixture.
- Missing allocation events produce warnings, not parser failure.
- Event count aggregation is deterministic.
- Top list bounds are honored.
- Stack samples are bounded.
- `SafeJvmRuntimeCollector.recordJfr` rejects missing confirmation token.
- `SafeJvmRuntimeCollector.recordJfr` rejects invalid paths and existing files.
- Unsupported `JFR.start` probe returns missing data and warning.
- Dynamic command timeout is based on duration plus grace.
- Existing single-shot and repeated sampling tests continue passing.

Integration-style tests:

- MCP tool registration includes `recordJvmFlightRecording`.
- Exported schema includes key fields: `durationSeconds`, `settings`, `jfrOutputPath`,
  `maxSummaryEvents`, and `confirmationToken`.
- README and Cursor workflow docs mention the new public tool.

Manual verification:

- Run `mvn test`.
- Run `mvn -DskipTests package`.
- Start the MCP server and confirm the tool list includes `recordJvmFlightRecording`.
- Against the memory leak demo or another local Java process, run a short `profile` recording
  and confirm a `.jfr` file plus non-empty `eventCounts` are returned.

---

## 13. Risks

### 13.1 JDK And Vendor Variation

JFR event availability and field shapes can vary across JDK versions and settings. The parser
must treat missing categories as partial evidence rather than a hard failure.

### 13.2 Request Duration

The MCP call blocks for the recording duration. Bounds and defaults keep first-version behavior
predictable.

### 13.3 Recording Cost

`profile` settings can add more overhead than lightweight inspection. Requiring
`confirmationToken`, bounding duration, and documenting the difference from `inspectJvmRuntime`
mitigates accidental use.

### 13.4 Large Files

Large recordings can be expensive to parse and return. `maxSummaryEvents`, bounded top lists,
and no raw event lists keep responses controlled.

### 13.5 Stale Recordings

Using `duration=<Ns>` avoids public lifecycle state, but a JVM or `jcmd` failure could still
leave a recording active. The generated recording name must be included in warnings whenever
collection fails after attempting `JFR.start`.

---

## 14. Rollout Summary

This phase delivers:

- a dedicated short JFR live MCP tool
- a safe one-shot `jcmd JFR.start` workflow
- a structured `.jfr` summary based on JDK APIs
- bounded top-level signals for GC, allocation, lock/thread blocking, and execution samples
- synchronized public docs and schema drift checks

Later phases can decide whether to feed `JfrSummary` into `generateTuningAdvice`, add offline
`summarizeJfrFile`, or expose explicit recording lifecycle tools.

---

## 15. Revision History

| Date | Change |
|------|--------|
| 2026-04-24 | Initial design for a one-shot JFR recording MCP tool with lightweight summary parsing. |
