# Production Readiness P0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the JVM tuning agent reliable enough for real tuning workflows by adding bounded command execution, repeated lightweight sampling, trend-aware diagnosis, and schema/documentation drift checks.

**Architecture:** Keep current single-shot MCP tools compatible, add structured command execution under the existing `CommandExecutor`, expose repeated sampling through a new live MCP tool, and carry repeated-sample evidence through `MemoryGcEvidencePack` into a focused diagnosis rule. Public documentation becomes part of the test contract so tool schema and workflow docs cannot silently drift.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring AI MCP tools, JUnit 5, AssertJ, Mockito

---

## File structure

### New files

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/CommandExecutionOptions.java`
  - Immutable execution limits: timeout and max output bytes.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/CommandExecutionResult.java`
  - Structured command result with bounded output, elapsed time, timeout, truncation, and failure message.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/CommandExecutorFixtureMain.java`
  - Test helper JVM process for timeout, truncation, and non-zero exit tests.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingRequest.java`
  - Public MCP request for repeated lightweight sampling.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedRuntimeSample.java`
  - One timestamped sample containing memory, GC, optional thread/class counts, and warnings.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingResult.java`
  - JSON-friendly repeated sampling response.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingProperties.java`
  - Configured defaults and bounds for repeated sampling.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingRequestTest.java`
  - Request normalization and bounds tests.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorRepeatedSamplingTest.java`
  - Repeated collector behavior and partial failure tests.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/RepeatedSamplingTrendRule.java`
  - Trend-aware diagnosis rule for repeated lightweight samples.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/RepeatedSamplingTrendRuleTest.java`
  - Trend rule tests for rising heap, elevated GC activity, growing footprint, and stable samples.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpPublicDocumentationContractTest.java`
  - README / Cursor workflow drift gate.

### Modified files

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/CommandExecutor.java`
  - Add structured `execute(...)` default method.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SystemCommandExecutor.java`
  - Implement timeout, max output bytes, truncation, and structured result.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SystemCommandExecutorTest.java`
  - Cover timeout, truncation, non-zero exit, and `run(...)` compatibility.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmRuntimeCollector.java`
  - Add default `collectRepeated(...)`.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java`
  - Implement repeated lightweight sampling and add command truncation warnings.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`
  - Carry optional `RepeatedSamplingResult`.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java`
  - Register `RepeatedSamplingTrendRule`.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/DiagnosisConfidenceEvaluator.java`
  - Add confidence reasons for repeated samples, partial failures, and unavailable trend analysis.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/JavaTuningMcpTools.java`
  - Add `inspectJvmRuntimeRepeated`.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java`
  - Wire command execution limits and repeated sampling defaults.
- `src/main/resources/application.yml`
  - Document new config defaults.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`
  - Assert repeated sampling tool schema.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngineTest.java`
  - Verify diagnosis integrates trend findings.
- `README.md`
  - Correct tool count, add repeated sampling, add retention tool, add config.
- `.cursor/skills/java-tuning-agent-workflow/SKILL.md`
  - Add repeated sampling as optional live workflow branch.
- `.cursor/skills/java-tuning-agent-workflow/reference.md`
  - Add `inspectJvmRuntimeRepeated` JSON template and correct offline tool count.

---

## Task 1: Add structured command execution guardrails

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/CommandExecutionOptions.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/CommandExecutionResult.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/CommandExecutorFixtureMain.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/CommandExecutor.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SystemCommandExecutor.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SystemCommandExecutorTest.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Write failing tests for timeout, truncation, and run compatibility**

Add these tests to `SystemCommandExecutorTest`:

```java
@Test
void shouldTimeOutAndKillLongRunningCommand() {
    SystemCommandExecutor executor = executorWithJavaWhitelist(100, 1024);

    CommandExecutionResult result = executor.execute(fixtureCommand("sleep", "5000"),
            new CommandExecutionOptions(100L, 1024));

    assertThat(result.timedOut()).isTrue();
    assertThat(result.exitCode()).isEqualTo(-1);
    assertThat(result.failureMessage()).contains("timed out");
    assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(1L);
}

@Test
void shouldTruncateLargeOutputWithoutFailingSuccessfulCommand() {
    SystemCommandExecutor executor = executorWithJavaWhitelist(5_000, 64);

    CommandExecutionResult result = executor.execute(fixtureCommand("large", "4096"),
            new CommandExecutionOptions(5_000L, 64));

    assertThat(result.timedOut()).isFalse();
    assertThat(result.exitCode()).isEqualTo(0);
    assertThat(result.truncated()).isTrue();
    assertThat(result.output().length()).isLessThanOrEqualTo(64);
}

@Test
void runShouldThrowWithStructuredFailureMessageForNonZeroExit() {
    SystemCommandExecutor executor = executorWithJavaWhitelist(5_000, 1024);

    assertThatThrownBy(() -> executor.run(fixtureCommand("exit", "7")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exited with code 7");
}
```

Add helpers in the same test class:

```java
private static SystemCommandExecutor executorWithJavaWhitelist(long timeoutMs, int maxOutputBytes) {
    String javaExe = javaExecutable();
    return new SystemCommandExecutor(List.of(javaExe), timeoutMs, maxOutputBytes);
}

private static List<String> fixtureCommand(String mode, String value) {
    return List.of(javaExecutable(), "-cp", System.getProperty("java.class.path"),
            "com.alibaba.cloud.ai.examples.javatuning.runtime.CommandExecutorFixtureMain", mode, value);
}

private static String javaExecutable() {
    String suffix = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    return java.nio.file.Path.of(System.getProperty("java.home"), "bin", suffix).toString();
}
```

Create `CommandExecutorFixtureMain` with the modes referenced by the tests:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

public final class CommandExecutorFixtureMain {

    private CommandExecutorFixtureMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        int value = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        switch (mode) {
            case "sleep" -> Thread.sleep(value);
            case "large" -> System.out.print("x".repeat(value));
            case "exit" -> System.exit(value);
            default -> System.out.print("ok");
        }
    }
}
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `mvn "-Dtest=SystemCommandExecutorTest" test`

Expected: FAIL because `CommandExecutionOptions`, `CommandExecutionResult`, constructor overloads, and `execute(...)` do not exist.

- [ ] **Step 3: Add command execution records**

Create `CommandExecutionOptions`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record CommandExecutionOptions(long timeoutMs, int maxOutputBytes) {

    public static CommandExecutionOptions defaults() {
        return new CommandExecutionOptions(15_000L, 8 * 1024 * 1024);
    }

    public CommandExecutionOptions {
        if (timeoutMs <= 0L) {
            throw new IllegalArgumentException("timeoutMs must be positive");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
    }
}
```

Create `CommandExecutionResult`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record CommandExecutionResult(List<String> command, int exitCode, String output, boolean timedOut,
        boolean truncated, long elapsedMs, String failureMessage) {

    public CommandExecutionResult {
        command = command == null ? List.of() : List.copyOf(command);
        output = output == null ? "" : output;
        failureMessage = failureMessage == null ? "" : failureMessage;
    }

    public boolean succeeded() {
        return exitCode == 0 && !timedOut && failureMessage.isBlank();
    }
}
```

- [ ] **Step 4: Extend `CommandExecutor` without breaking existing mocks**

Update `CommandExecutor`:

```java
default CommandExecutionResult execute(List<String> command, CommandExecutionOptions options) {
    long started = System.currentTimeMillis();
    try {
        String output = run(command);
        return new CommandExecutionResult(command, 0, output, false, false,
                Math.max(0L, System.currentTimeMillis() - started), "");
    }
    catch (RuntimeException ex) {
        return new CommandExecutionResult(command, -1, "", false, false,
                Math.max(0L, System.currentTimeMillis() - started), ex.getMessage());
    }
}
```

- [ ] **Step 5: Implement bounded execution in `SystemCommandExecutor`**

Add constructor fields:

```java
private final long defaultTimeoutMs;

private final int defaultMaxOutputBytes;

public SystemCommandExecutor(List<String> whitelist) {
    this(whitelist, 15_000L, 8 * 1024 * 1024);
}

public SystemCommandExecutor(List<String> whitelist, long defaultTimeoutMs, int defaultMaxOutputBytes) {
    this.whitelist = List.copyOf(whitelist);
    this.defaultTimeoutMs = defaultTimeoutMs;
    this.defaultMaxOutputBytes = defaultMaxOutputBytes;
}
```

Implement `execute(...)` using `Process#waitFor(timeout, TimeUnit.MILLISECONDS)`, bounded reads, and `destroyForcibly()` on timeout. Keep `run(...)` delegating to `execute(command, new CommandExecutionOptions(defaultTimeoutMs, defaultMaxOutputBytes))` and throwing on non-success.

- [ ] **Step 6: Wire configuration**

Update `JavaTuningAgentConfig#commandExecutor`:

```java
CommandExecutor commandExecutor(
        @Value("${java-tuning-agent.command-whitelist:jps,jcmd,jstat}") List<String> commandWhitelist,
        @Value("${java-tuning-agent.command.default-timeout-ms:15000}") long defaultTimeoutMs,
        @Value("${java-tuning-agent.command.default-max-output-bytes:8388608}") int defaultMaxOutputBytes) {
    return new SystemCommandExecutor(commandWhitelist, defaultTimeoutMs, defaultMaxOutputBytes);
}
```

Update `application.yml`:

```yaml
java-tuning-agent:
  command:
    default-timeout-ms: 15000
    default-max-output-bytes: 8388608
    privileged-max-output-bytes: 67108864
```

- [ ] **Step 7: Re-run command executor tests**

Run: `mvn "-Dtest=SystemCommandExecutorTest" test`

Expected: PASS.

- [ ] **Step 8: Commit command guardrails**

```bash
git add -- src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/CommandExecutionOptions.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/CommandExecutionResult.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/CommandExecutor.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SystemCommandExecutor.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java src/main/resources/application.yml src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/CommandExecutorFixtureMain.java src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SystemCommandExecutorTest.java
git commit -m "Add bounded command execution"
```

---

## Task 2: Add repeated sampling models and collector support

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingRequest.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedRuntimeSample.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingResult.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingProperties.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingRequestTest.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorRepeatedSamplingTest.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmRuntimeCollector.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Write failing request validation tests**

Create `RepeatedSamplingRequestTest`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepeatedSamplingRequestTest {

    @Test
    void shouldNormalizeBlankRequestFieldsToDefaults() {
        RepeatedSamplingProperties props = RepeatedSamplingProperties.defaults();
        RepeatedSamplingRequest normalized = new RepeatedSamplingRequest(123L, null, null, true, true, "")
                .normalized(props);

        assertThat(normalized.sampleCount()).isEqualTo(3);
        assertThat(normalized.intervalMillis()).isEqualTo(10_000L);
        assertThat(normalized.includeThreadCount()).isTrue();
        assertThat(normalized.includeClassCount()).isTrue();
    }

    @Test
    void shouldRejectSamplingWindowBeyondConfiguredLimit() {
        RepeatedSamplingProperties props = new RepeatedSamplingProperties(3, 10_000L, 20, 20_000L);

        assertThatThrownBy(() -> new RepeatedSamplingRequest(123L, 5, 10_000L, true, true, "")
                .normalized(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-total-duration");
    }
}
```

- [ ] **Step 2: Write failing collector test for partial repeated sampling**

Create `SafeJvmRuntimeCollectorRepeatedSamplingTest` with a fake `CommandExecutor` that returns different `GC.heap_info` and `jstat -gcutil` outputs per call. The test should assert successful samples are kept when one command fails:

```java
@Test
void shouldKeepSuccessfulSamplesWhenOneSampleFails() {
    ScriptedCommandExecutor executor = new ScriptedCommandExecutor();
    executor.addStandardSample("123", 100, 40.0, 1, 10, 0, 0);
    executor.addFailureForNextHeapInfo("attach failed");
    executor.addStandardSample("123", 180, 55.0, 2, 20, 0, 0);
    SafeJvmRuntimeCollector collector = collector(executor, millis -> { });

    RepeatedSamplingResult result = collector.collectRepeated(
            new RepeatedSamplingRequest(123L, 3, 500L, true, true, ""));

    assertThat(result.samples()).hasSize(2);
    assertThat(result.warnings()).anyMatch(w -> w.contains("sample 2") && w.contains("attach failed"));
    assertThat(result.missingData()).contains("sample[1]");
}
```

Use helper methods that return the same G1 heap and jstat text formats already used in `SafeJvmRuntimeCollectorTest`.

- [ ] **Step 3: Run repeated sampling tests and confirm they fail**

Run: `mvn "-Dtest=RepeatedSamplingRequestTest,SafeJvmRuntimeCollectorRepeatedSamplingTest" test`

Expected: FAIL because repeated sampling classes and collector method do not exist.

- [ ] **Step 4: Add repeated sampling records**

Create `RepeatedSamplingProperties`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record RepeatedSamplingProperties(int defaultSampleCount, long defaultIntervalMillis, int maxSampleCount,
        long maxTotalDurationMillis) {

    public static RepeatedSamplingProperties defaults() {
        return new RepeatedSamplingProperties(3, 10_000L, 20, 300_000L);
    }
}
```

Create `RepeatedSamplingRequest`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Request for repeated safe read-only JVM runtime sampling.")
public record RepeatedSamplingRequest(
        @JsonPropertyDescription("Target JVM process id.") long pid,
        @JsonPropertyDescription("Number of samples; blank uses server default.") Integer sampleCount,
        @JsonPropertyDescription("Interval between samples in milliseconds; blank uses server default.") Long intervalMillis,
        @JsonPropertyDescription("Whether to include live thread count when available.") boolean includeThreadCount,
        @JsonPropertyDescription("Whether to include loaded class count when available.") boolean includeClassCount,
        @JsonPropertyDescription("Reserved for future privileged repeated modes; not required for safe read-only P0 sampling.") String confirmationToken) {

    public RepeatedSamplingRequest normalized(RepeatedSamplingProperties props) {
        int count = sampleCount == null ? props.defaultSampleCount() : sampleCount;
        long interval = intervalMillis == null ? props.defaultIntervalMillis() : intervalMillis;
        if (count < 2 || count > props.maxSampleCount()) {
            throw new IllegalArgumentException("sampleCount must be between 2 and " + props.maxSampleCount());
        }
        if (interval < 500L || interval > 60_000L) {
            throw new IllegalArgumentException("intervalMillis must be between 500 and 60000");
        }
        long planned = Math.max(0L, count - 1L) * interval;
        if (planned > props.maxTotalDurationMillis()) {
            throw new IllegalArgumentException("sampling window exceeds max-total-duration " + props.maxTotalDurationMillis());
        }
        return new RepeatedSamplingRequest(pid, count, interval, includeThreadCount, includeClassCount,
                confirmationToken == null ? "" : confirmationToken);
    }
}
```

Create `RepeatedRuntimeSample` and `RepeatedSamplingResult`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record RepeatedRuntimeSample(long sampledAtEpochMs, JvmMemorySnapshot memory, JvmGcSnapshot gc,
        Long threadCount, Long loadedClassCount, List<String> warnings) {

    public RepeatedRuntimeSample {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
```

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record RepeatedSamplingResult(long pid, List<RepeatedRuntimeSample> samples, List<String> warnings,
        List<String> missingData, long startedAtEpochMs, long elapsedMs) {

    public RepeatedSamplingResult {
        samples = samples == null ? List.of() : List.copyOf(samples);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        missingData = missingData == null ? List.of() : List.copyOf(missingData);
    }
}
```

- [ ] **Step 5: Extend collector interface**

Update `JvmRuntimeCollector`:

```java
default RepeatedSamplingResult collectRepeated(RepeatedSamplingRequest request) {
    throw new UnsupportedOperationException("Repeated sampling is not supported by this collector implementation");
}
```

- [ ] **Step 6: Implement repeated sampling in `SafeJvmRuntimeCollector`**

Add fields and constructors:

```java
private final RepeatedSamplingProperties repeatedSamplingProperties;

private final java.util.function.LongConsumer sleeper;
```

Main constructor should use `RepeatedSamplingProperties.defaults()` and `Thread::sleep` wrapped through a helper. Add a package-private constructor for tests that accepts `RepeatedSamplingProperties` and `LongConsumer sleeper`.

Implement:

```java
@Override
public RepeatedSamplingResult collectRepeated(RepeatedSamplingRequest request) {
    RepeatedSamplingRequest normalized = request.normalized(repeatedSamplingProperties);
    long started = System.currentTimeMillis();
    List<RepeatedRuntimeSample> samples = new java.util.ArrayList<>();
    List<String> warnings = new java.util.ArrayList<>();
    List<String> missing = new java.util.ArrayList<>();
    for (int i = 0; i < normalized.sampleCount(); i++) {
        if (i > 0) {
            sleeper.accept(normalized.intervalMillis());
        }
        try {
            JvmRuntimeSnapshot snapshot = collect(normalized.pid(), RuntimeCollectionPolicy.CollectionRequest.safeReadonly());
            samples.add(new RepeatedRuntimeSample(System.currentTimeMillis(), snapshot.memory(), snapshot.gc(),
                    normalized.includeThreadCount() ? snapshot.threadCount() : null,
                    normalized.includeClassCount() ? snapshot.loadedClassCount() : null,
                    snapshot.warnings()));
        }
        catch (RuntimeException ex) {
            missing.add("sample[" + i + "]");
            warnings.add("Repeated sample " + (i + 1) + " failed: " + ex.getMessage());
        }
    }
    if (samples.size() < 2) {
        missing.add("repeatedTrendAnalysis");
        warnings.add("Fewer than two repeated samples succeeded; trend analysis is unavailable.");
    }
    return new RepeatedSamplingResult(normalized.pid(), samples, warnings, missing, started,
            Math.max(0L, System.currentTimeMillis() - started));
}
```

- [ ] **Step 7: Wire sampling configuration**

Update `JavaTuningAgentConfig#jvmRuntimeCollector` to accept:

```java
@Value("${java-tuning-agent.sampling.default-sample-count:3}") int defaultSampleCount,
@Value("${java-tuning-agent.sampling.default-interval-ms:10000}") long defaultIntervalMs,
@Value("${java-tuning-agent.sampling.max-sample-count:20}") int maxSampleCount,
@Value("${java-tuning-agent.sampling.max-total-duration-ms:300000}") long maxTotalDurationMs
```

Pass `new RepeatedSamplingProperties(defaultSampleCount, defaultIntervalMs, maxSampleCount, maxTotalDurationMs)` into `SafeJvmRuntimeCollector`.

Update `application.yml`:

```yaml
  sampling:
    default-sample-count: 3
    default-interval-ms: 10000
    max-sample-count: 20
    max-total-duration-ms: 300000
```

- [ ] **Step 8: Re-run repeated sampling tests**

Run: `mvn "-Dtest=RepeatedSamplingRequestTest,SafeJvmRuntimeCollectorRepeatedSamplingTest,SafeJvmRuntimeCollectorTest" test`

Expected: PASS.

- [ ] **Step 9: Commit repeated sampling collector**

```bash
git add -- src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingRequest.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedRuntimeSample.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingResult.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingProperties.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmRuntimeCollector.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java src/main/resources/application.yml src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingRequestTest.java src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorRepeatedSamplingTest.java
git commit -m "Add repeated JVM runtime sampling"
```

---

## Task 3: Carry repeated samples into diagnosis and add trend rules

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/RepeatedSamplingTrendRule.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/RepeatedSamplingTrendRuleTest.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/DiagnosisConfidenceEvaluator.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngineTest.java`

- [ ] **Step 1: Write failing evidence-pack and trend-rule tests**

Create `RepeatedSamplingTrendRuleTest`:

```java
@Test
void shouldReportRisingHeapPressureAcrossRepeatedSamples() {
    MemoryGcEvidencePack evidence = packWithSamples(List.of(
            sample(0, 100, 40.0, 10, 100, 0, 0, 20L, 1_000L),
            sample(10_000, 170, 55.0, 12, 120, 0, 0, 22L, 1_003L),
            sample(20_000, 260, 72.0, 14, 140, 0, 0, 23L, 1_005L)));
    DiagnosisScratch scratch = new DiagnosisScratch();

    new RepeatedSamplingTrendRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

    assertThat(scratch.findings()).extracting(TuningFinding::title)
            .contains(RepeatedSamplingTrendRule.RISING_HEAP_TITLE);
    assertThat(scratch.findings().get(0).evidence()).contains("samples=3").contains("heapDeltaMb=160");
}

@Test
void shouldStayQuietForStableSamplesAndAddNextStep() {
    MemoryGcEvidencePack evidence = packWithSamples(List.of(
            sample(0, 100, 40.0, 10, 100, 0, 0, 20L, 1_000L),
            sample(10_000, 101, 40.5, 10, 100, 0, 0, 20L, 1_000L),
            sample(20_000, 99, 40.0, 11, 101, 0, 0, 20L, 1_001L)));
    DiagnosisScratch scratch = new DiagnosisScratch();

    new RepeatedSamplingTrendRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

    assertThat(scratch.findings()).isEmpty();
    assertThat(scratch.nextSteps()).anyMatch(step -> step.contains("No strong repeated-sampling trend"));
}
```

Helper sample shape:

```java
private static RepeatedRuntimeSample sample(long atOffsetMs, long heapUsedMb, double oldPct,
        long ygc, long ygctMs, long fgc, long fgctMs, Long threads, Long classes) {
    return new RepeatedRuntimeSample(1_000_000L + atOffsetMs,
            new JvmMemorySnapshot(heapUsedMb * 1024L * 1024L, 512L * 1024L * 1024L,
                    512L * 1024L * 1024L, null, null, null, null, null),
            new JvmGcSnapshot("G1", ygc, ygctMs, fgc, fgctMs, oldPct), threads, classes, List.of());
}
```

- [ ] **Step 2: Run the trend tests and confirm they fail**

Run: `mvn "-Dtest=RepeatedSamplingTrendRuleTest" test`

Expected: FAIL because `MemoryGcEvidencePack` has no repeated sampling field and `RepeatedSamplingTrendRule` does not exist.

- [ ] **Step 3: Extend `MemoryGcEvidencePack` with compatible constructors**

Add field:

```java
RepeatedSamplingResult repeatedSamplingResult
```

Keep existing constructor signatures by delegating to the new canonical record constructor. Add:

```java
public MemoryGcEvidencePack withRepeatedSamplingResult(RepeatedSamplingResult repeatedSamplingResult) {
    return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
            heapShallowSummary, heapRetentionAnalysis, repeatedSamplingResult);
}
```

- [ ] **Step 4: Implement `RepeatedSamplingTrendRule`**

Create constants:

```java
public static final String RISING_HEAP_TITLE = "Repeated samples show rising heap pressure";
public static final String ELEVATED_GC_TITLE = "Repeated samples show elevated GC activity";
public static final String GROWING_FOOTPRINT_TITLE = "Repeated samples show growing runtime footprint";
```

Rule thresholds for P0:

- heap used monotonically rises by at least 128 MiB or at least 20% of heap max
- old usage rises by at least 15 percentage points across at least 3 samples
- full GC count increases by at least 1
- young GC rate is at least 30 collections per minute or young GC time grows by at least 1 second per minute
- thread count rises by at least 20
- loaded class count rises by at least 500

Use conservative finding text with "trend suggests" and add next steps for longer sampling or stronger evidence.

- [ ] **Step 5: Register rule and confidence reasons**

Update `MemoryGcDiagnosisEngine.firstVersion()` to place `new RepeatedSamplingTrendRule()` after `HighHeapPressureRule()`.

Update `DiagnosisConfidenceEvaluator`:

```java
if (evidence.repeatedSamplingResult() != null && !evidence.repeatedSamplingResult().samples().isEmpty()) {
    reasons.add("Repeated runtime samples present: trend-aware rules were applied");
}
if (evidence.repeatedSamplingResult() != null && evidence.repeatedSamplingResult().samples().size() < 2) {
    reasons.add("Repeated sampling had fewer than two successful samples; trend confidence is limited");
}
```

- [ ] **Step 6: Add diagnosis integration test**

Add to `MemoryGcDiagnosisEngineTest`:

```java
@Test
void shouldIncludeTrendFindingsFromRepeatedSamples() {
    MemoryGcEvidencePack evidence = stableBaseEvidence().withRepeatedSamplingResult(risingHeapSamples());

    TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
            .diagnose(evidence, CodeContextSummary.empty(), "local", "diagnose memory growth");

    assertThat(report.findings()).extracting(TuningFinding::title)
            .contains(RepeatedSamplingTrendRule.RISING_HEAP_TITLE);
    assertThat(report.confidenceReasons())
            .anyMatch(reason -> reason.contains("Repeated runtime samples present"));
}
```

- [ ] **Step 7: Re-run focused diagnosis tests**

Run: `mvn "-Dtest=RepeatedSamplingTrendRuleTest,MemoryGcDiagnosisEngineTest,JavaTuningWorkflowServiceTest" test`

Expected: PASS.

- [ ] **Step 8: Commit trend diagnosis**

```bash
git add -- src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/RepeatedSamplingTrendRule.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/DiagnosisConfidenceEvaluator.java src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/RepeatedSamplingTrendRuleTest.java src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngineTest.java
git commit -m "Add repeated sampling trend diagnosis"
```

---

## Task 4: Expose repeated sampling through MCP

**Files:**
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/JavaTuningMcpTools.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/JavaTuningMcpToolsTest.java`

- [ ] **Step 1: Write failing MCP schema test**

Update `McpToolSchemaContractTest#everyRegisteredToolExposesParsableInputSchemaWithExpectedShape` switch:

```java
case "inspectJvmRuntimeRepeated" -> {
    JsonNode request = schema.path("properties").path("request");
    assertThat(request.path("type").asText()).isEqualTo("object");
    assertThat(request.path("properties").path("pid").path("type").asText()).isIn("integer", "number");
    assertThat(request.path("properties").path("sampleCount").path("type").asText()).isIn("integer", "number");
    assertThat(request.path("properties").path("intervalMillis").path("type").asText()).isIn("integer", "number");
    assertThat(request.path("properties").path("includeThreadCount").path("type").asText()).isEqualTo("boolean");
    assertThat(request.path("properties").path("includeClassCount").path("type").asText()).isEqualTo("boolean");
}
```

Add a dedicated registration test:

```java
@Test
void inspectJvmRuntimeRepeatedShouldBeRegistered() {
    assertThat(Arrays.stream(toolCallbackProvider.getToolCallbacks())
            .map(callback -> callback.getToolDefinition().name()))
            .contains("inspectJvmRuntimeRepeated");
}
```

- [ ] **Step 2: Run schema test and confirm it fails**

Run: `mvn "-Dtest=McpToolSchemaContractTest" test`

Expected: FAIL because the new MCP tool is not registered.

- [ ] **Step 3: Add MCP tool method**

Update `JavaTuningMcpTools`:

```java
@Tool(description = """
        Collect repeated safe read-only JVM runtime samples for a PID using bounded jcmd/jstat commands.
        This P0 repeated mode does not collect class histograms, thread dumps, heap dumps, or JFR, and does not require confirmationToken.
        Example arguments JSON: {"request":{"pid":12345,"sampleCount":3,"intervalMillis":10000,"includeThreadCount":true,"includeClassCount":true,"confirmationToken":""}}""")
public RepeatedSamplingResult inspectJvmRuntimeRepeated(
        @ToolParam(description = "RepeatedSamplingRequest JSON: pid, sampleCount, intervalMillis, includeThreadCount, includeClassCount.") RepeatedSamplingRequest request) {
    return collector.collectRepeated(request);
}
```

- [ ] **Step 4: Add tool unit test**

In `JavaTuningMcpToolsTest`, mock `JvmRuntimeCollector#collectRepeated` and assert method delegates:

```java
@Test
void shouldDelegateRepeatedRuntimeInspectionToCollector() {
    JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
    RepeatedSamplingRequest request = new RepeatedSamplingRequest(123L, 3, 500L, true, true, "");
    RepeatedSamplingResult result = new RepeatedSamplingResult(123L, List.of(), List.of(), List.of(), 1L, 0L);
    given(collector.collectRepeated(request)).willReturn(result);
    JavaTuningMcpTools tools = new JavaTuningMcpTools(mock(JavaProcessDiscoveryService.class), collector,
            mock(JavaTuningWorkflowService.class));

    assertThat(tools.inspectJvmRuntimeRepeated(request)).isSameAs(result);
}
```

- [ ] **Step 5: Re-run MCP tests**

Run: `mvn "-Dtest=McpToolSchemaContractTest,JavaTuningMcpToolsTest" test`

Expected: PASS.

- [ ] **Step 6: Commit MCP repeated sampling tool**

```bash
git add -- src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/JavaTuningMcpTools.java src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/JavaTuningMcpToolsTest.java
git commit -m "Expose repeated JVM runtime sampling tool"
```

---

## Task 5: Add schema and documentation drift gate

**Files:**
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpPublicDocumentationContractTest.java`
- Modify: `README.md`
- Modify: `.cursor/skills/java-tuning-agent-workflow/SKILL.md`
- Modify: `.cursor/skills/java-tuning-agent-workflow/reference.md`
- Modify: `docs/offline-mode-spec.md`

- [ ] **Step 1: Write failing documentation contract test**

Create `McpPublicDocumentationContractTest`:

```java
package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class McpPublicDocumentationContractTest {

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Test
    void readmeAndCursorReferenceShouldMentionEveryPublicTool() throws Exception {
        Set<String> tools = java.util.Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        String readme = Files.readString(Path.of("README.md"));
        String skill = Files.readString(Path.of(".cursor/skills/java-tuning-agent-workflow/SKILL.md"));
        String reference = Files.readString(Path.of(".cursor/skills/java-tuning-agent-workflow/reference.md"));

        for (String tool : tools) {
            assertThat(readme).as("README should mention " + tool).contains(tool);
            assertThat(reference).as("Cursor reference should mention " + tool).contains(tool);
        }
        assertThat(skill).contains("inspectJvmRuntimeRepeated");
        assertThat(readme).contains("analysisDepth", "heapDumpAbsolutePath", "confirmationToken",
                "sampleCount", "intervalMillis");
        assertThat(reference).contains("analysisDepth", "heapDumpAbsolutePath", "confirmationToken",
                "sampleCount", "intervalMillis");
    }

    @Test
    void readmeShouldStateCurrentToolCount() throws Exception {
        int toolCount = toolCallbackProvider.getToolCallbacks().length;
        String readme = Files.readString(Path.of("README.md"));

        assertThat(readme).contains("**" + toolCount + "** tools");
    }
}
```

- [ ] **Step 2: Run documentation contract test and confirm it fails**

Run: `mvn "-Dtest=McpPublicDocumentationContractTest" test`

Expected: FAIL because README and Cursor references still have older tool count and missing repeated sampling entries.

- [ ] **Step 3: Update README live and offline tool sections**

Change the README opening to state the current tool count dynamically expected by the test after Task 4. The current public surface is 13 tools:

```markdown
After the server is started over stdio, MCP clients should see **13** tools: seven for **live JVM** workflows and six for **offline / imported** bundles.
```

Add a live tool row:

```markdown
| `inspectJvmRuntimeRepeated` | Collect repeated safe read-only snapshots for short trend analysis (`sampleCount`, `intervalMillis`, optional thread/class counts). |
```

Add offline row if missing:

```markdown
| `analyzeOfflineHeapRetention` | Analyze an existing `.hprof` for holder-oriented retention evidence; `analysisDepth=deep` attempts retained-style analysis and falls back honestly. |
```

Add configuration rows for command and sampling properties.

- [ ] **Step 4: Update Cursor skill workflow**

In `.cursor/skills/java-tuning-agent-workflow/SKILL.md`:

- mention `inspectJvmRuntimeRepeated` in the description
- add an optional branch after `inspectJvmRuntime`: use repeated sampling when the user asks about trend, leak over time, GC rate, or production-readiness P0 diagnostics
- keep mandatory privileged scope gate unchanged
- update offline section to include `analyzeOfflineHeapRetention`

- [ ] **Step 5: Update Cursor reference JSON**

In `.cursor/skills/java-tuning-agent-workflow/reference.md`, add this section:

````markdown
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
````

Also update offline tool count from five to six and add `analyzeOfflineHeapRetention` with `analysisDepth`.

- [ ] **Step 6: Update offline mode spec if needed**

In `docs/offline-mode-spec.md`, ensure the public tool count and `analyzeOfflineHeapRetention` / `analysisDepth` language matches README.

- [ ] **Step 7: Re-run documentation contract and schema tests**

Run: `mvn "-Dtest=McpPublicDocumentationContractTest,McpToolSchemaContractTest" test`

Expected: PASS.

- [ ] **Step 8: Commit documentation drift gate**

```bash
git add -- src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpPublicDocumentationContractTest.java README.md .cursor/skills/java-tuning-agent-workflow/SKILL.md .cursor/skills/java-tuning-agent-workflow/reference.md docs/offline-mode-spec.md
git commit -m "Add MCP documentation drift gate"
```

---

## Task 6: Final regression and packaging

**Files:**
- No production files expected beyond previous tasks.

- [ ] **Step 1: Run focused P0 regression suite**

Run:

```bash
mvn "-Dtest=SystemCommandExecutorTest,RepeatedSamplingRequestTest,SafeJvmRuntimeCollectorRepeatedSamplingTest,RepeatedSamplingTrendRuleTest,McpToolSchemaContractTest,McpPublicDocumentationContractTest,JavaTuningMcpToolsTest,MemoryGcDiagnosisEngineTest,JavaTuningWorkflowServiceTest,JavaTuningAgentApplicationTests" test
```

Expected: PASS with zero failures.

- [ ] **Step 2: Run full test suite**

Run: `mvn test`

Expected: PASS with zero failures.

- [ ] **Step 3: Run package**

Run: `mvn -DskipTests package`

Expected: `BUILD SUCCESS`.

If Windows reports the jar is locked, stop any `java -jar target/java-tuning-agent-*.jar` process and rerun once. Record the locked-jar cause in the final handoff.

- [ ] **Step 4: Inspect final public surface**

Run:

```bash
git status --short --branch
git log --oneline -8
```

Expected:

- working tree clean
- latest commits include the P0 slices
- branch is ready for review or merge

- [ ] **Step 5: Commit any verification-only doc correction**

Only if verification reveals a small doc/schema text mismatch, stage and commit that correction:

```bash
git add -- README.md .cursor/skills/java-tuning-agent-workflow/SKILL.md .cursor/skills/java-tuning-agent-workflow/reference.md docs/offline-mode-spec.md
git commit -m "Sync P0 repeated sampling documentation"
```

---

## Self-review

### Spec coverage

- Bounded command execution: Task 1
- Repeated sampling request/result model: Task 2
- Live repeated sampling MCP tool: Task 4
- Evidence-pack integration: Task 3
- Trend-aware diagnosis: Task 3
- Schema/docs drift gate: Task 5
- Configuration defaults: Tasks 1 and 2
- Full verification: Task 6

### Type consistency

- `RepeatedSamplingRequest` is the MCP input wrapper payload for `inspectJvmRuntimeRepeated`.
- `RepeatedSamplingResult` is returned by `JvmRuntimeCollector#collectRepeated` and carried optionally by `MemoryGcEvidencePack`.
- `RepeatedRuntimeSample` holds memory/GC/thread/class values for one timestamp.
- `CommandExecutionResult` is the structured executor output; legacy `run(...)` remains compatible.

### Risk notes

- The repeated collector is deliberately synchronous and bounded; no scheduler or database is introduced.
- Trend wording must remain conservative because P0 windows are short.
- Documentation drift checks should validate public tool names and key parameters without asserting every README sentence.
