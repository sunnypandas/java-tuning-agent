# JFR Short Recording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a dedicated live MCP tool that records one short JFR session for a target JVM and returns the `.jfr` path plus a bounded lightweight summary.

**Architecture:** Keep JFR separate from the existing memory/GC evidence pack. Add focused runtime records, a defensive `JfrSummaryParser` based on `jdk.jfr.consumer.RecordingFile`, and a `SafeJvmRuntimeCollector.recordJfr` path that runs a bounded one-shot `jcmd JFR.start` command. Expose it through `JavaTuningMcpTools` and synchronize schema/docs drift checks.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring AI MCP tool annotations, JDK Flight Recorder APIs (`jdk.jfr`, `jdk.jfr.consumer`), JUnit 5, AssertJ, Mockito.

---

## File Structure

Create runtime models:

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingProperties.java` - immutable defaults and bounds for JFR duration, completion grace, event parsing, and top-list size.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingRequest.java` - public request record plus `normalized(JfrRecordingProperties)` validation.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingResult.java` - public result record for MCP clients.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummary.java` - summary root record.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrGcSummary.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrAllocationSummary.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrThreadSummary.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrExecutionSampleSummary.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrCount.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrCountAndBytes.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrHeapSample.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrStackAggregate.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrThreadBlockAggregate.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummaryParser.java` - defensive parser and event aggregation.

Modify runtime and configuration:

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmRuntimeCollector.java` - add default `recordJfr`.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java` - inject JFR properties/parser and implement one-shot recording.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java` - wire JFR config properties into the collector.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/JavaTuningMcpTools.java` - expose `recordJvmFlightRecording`.

Create/modify tests:

- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingRequestTest.java`
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummaryParserTest.java`
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorJfrTest.java`
- Modify `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`
- Modify `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpPublicDocumentationContractTest.java`

Modify docs:

- `README.md`
- `.cursor/skills/java-tuning-agent-workflow/SKILL.md`
- `.cursor/skills/java-tuning-agent-workflow/reference.md`

---

### Task 1: Request Model And Properties

**Files:**

- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingProperties.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingRequest.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingRequestTest.java`

- [ ] **Step 1: Write failing validation tests**

Create `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingRequestTest.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JfrRecordingRequestTest {

	private static final JfrRecordingProperties PROPS = JfrRecordingProperties.defaults();

	@TempDir
	Path tempDir;

	@Test
	void shouldNormalizeBlankOptionalFieldsToDefaults() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();
		JfrRecordingRequest normalized = new JfrRecordingRequest(123L, null, " ", output.toString(), null,
				"confirmed").normalized(PROPS);

		assertThat(normalized.pid()).isEqualTo(123L);
		assertThat(normalized.durationSeconds()).isEqualTo(PROPS.defaultDurationSeconds());
		assertThat(normalized.settings()).isEqualTo("profile");
		assertThat(normalized.jfrOutputPath()).isEqualTo(output.toString());
		assertThat(normalized.maxSummaryEvents()).isEqualTo(PROPS.defaultMaxSummaryEvents());
		assertThat(normalized.confirmationToken()).isEqualTo("confirmed");
	}

	@Test
	void shouldAcceptDefaultSettings() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();
		JfrRecordingRequest normalized = new JfrRecordingRequest(123L, 10, "default", output.toString(), 20,
				"confirmed").normalized(PROPS);

		assertThat(normalized.settings()).isEqualTo("default");
		assertThat(normalized.durationSeconds()).isEqualTo(10);
		assertThat(normalized.maxSummaryEvents()).isEqualTo(20);
	}

	@Test
	void shouldRejectMissingConfirmationToken() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "profile", output.toString(), 20, " ")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confirmationToken");
	}

	@Test
	void shouldRejectInvalidPid() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(0L, 10, "profile", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("pid");
	}

	@Test
	void shouldRejectDurationOutsideBounds() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 4, "profile", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("durationSeconds");
		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 301, "profile", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("durationSeconds");
	}

	@Test
	void shouldRejectUnsupportedSettings() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "custom", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("settings");
	}

	@Test
	void shouldRejectNonAbsolutePath() {
		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "profile", "relative.jfr", 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("absolute");
	}

	@Test
	void shouldRejectNonJfrPath() {
		Path output = tempDir.resolve("recording.txt").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "profile", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(".jfr");
	}

	@Test
	void shouldRejectMissingParentDirectory() {
		Path output = tempDir.resolve("missing").resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "profile", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("parent");
	}

	@Test
	void shouldRejectExistingOutputFile() throws Exception {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();
		Files.writeString(output, "existing");

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "profile", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("already exists");
	}

	@Test
	void shouldRejectInvalidMaxSummaryEvents() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "profile", output.toString(), 0, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxSummaryEvents");
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
mvn -Dtest=JfrRecordingRequestTest test
```

Expected: compilation fails because `JfrRecordingRequest` and `JfrRecordingProperties` do not exist.

- [ ] **Step 3: Add properties model**

Create `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingProperties.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record JfrRecordingProperties(int defaultDurationSeconds, int minDurationSeconds, int maxDurationSeconds,
		long completionGraceMs, int defaultMaxSummaryEvents, int topLimit) {

	public static JfrRecordingProperties defaults() {
		return new JfrRecordingProperties(30, 5, 300, 10_000L, 200_000, 10);
	}

	public JfrRecordingProperties {
		if (defaultDurationSeconds <= 0) {
			throw new IllegalArgumentException("defaultDurationSeconds must be positive");
		}
		if (minDurationSeconds <= 0) {
			throw new IllegalArgumentException("minDurationSeconds must be positive");
		}
		if (maxDurationSeconds < minDurationSeconds) {
			throw new IllegalArgumentException("maxDurationSeconds must be >= minDurationSeconds");
		}
		if (defaultDurationSeconds < minDurationSeconds || defaultDurationSeconds > maxDurationSeconds) {
			throw new IllegalArgumentException("defaultDurationSeconds must be within configured bounds");
		}
		if (completionGraceMs < 0L) {
			throw new IllegalArgumentException("completionGraceMs must be non-negative");
		}
		if (defaultMaxSummaryEvents <= 0) {
			throw new IllegalArgumentException("defaultMaxSummaryEvents must be positive");
		}
		if (topLimit <= 0) {
			throw new IllegalArgumentException("topLimit must be positive");
		}
	}
}
```

- [ ] **Step 4: Add request model**

Create `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingRequest.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
		Requests one short Java Flight Recorder recording for a target JVM. \
		Requires a non-blank confirmationToken and an absolute jfrOutputPath ending in .jfr.""")
public record JfrRecordingRequest(
		@JsonPropertyDescription("Target JVM process id (decimal); should match a PID from listJavaApps.") long pid,
		@JsonPropertyDescription("Recording duration in seconds; null uses the server default, usually 30.") Integer durationSeconds,
		@JsonPropertyDescription("JFR settings template: default or profile. Blank uses profile.") String settings,
		@JsonPropertyDescription("Absolute output path for the recording file; must end in .jfr and must not already exist.") String jfrOutputPath,
		@JsonPropertyDescription("Maximum number of JFR events to parse for the summary; null uses the server default.") Integer maxSummaryEvents,
		@JsonPropertyDescription("Non-blank caller-provided approval token required for JFR recording.") String confirmationToken) {

	public JfrRecordingRequest normalized(JfrRecordingProperties properties) {
		if (properties == null) {
			properties = JfrRecordingProperties.defaults();
		}
		if (pid <= 0L) {
			throw new IllegalArgumentException("pid must be positive");
		}
		String token = confirmationToken == null ? "" : confirmationToken.trim();
		if (token.isBlank()) {
			throw new IllegalArgumentException("confirmationToken is required for JFR recording");
		}
		int duration = durationSeconds == null ? properties.defaultDurationSeconds() : durationSeconds;
		if (duration < properties.minDurationSeconds() || duration > properties.maxDurationSeconds()) {
			throw new IllegalArgumentException("durationSeconds must be between " + properties.minDurationSeconds()
					+ " and " + properties.maxDurationSeconds());
		}
		String normalizedSettings = settings == null || settings.isBlank() ? "profile" : settings.trim();
		if (!"default".equals(normalizedSettings) && !"profile".equals(normalizedSettings)) {
			throw new IllegalArgumentException("settings must be default or profile");
		}
		if (jfrOutputPath == null || jfrOutputPath.isBlank()) {
			throw new IllegalArgumentException("jfrOutputPath is required");
		}
		Path output = Path.of(jfrOutputPath.trim()).toAbsolutePath().normalize();
		if (!Path.of(jfrOutputPath.trim()).isAbsolute()) {
			throw new IllegalArgumentException("jfrOutputPath must be absolute");
		}
		if (!output.toString().toLowerCase().endsWith(".jfr")) {
			throw new IllegalArgumentException("jfrOutputPath must end in .jfr");
		}
		Path parent = output.getParent();
		if (parent == null || !Files.isDirectory(parent)) {
			throw new IllegalArgumentException("jfrOutputPath parent directory must already exist");
		}
		if (Files.exists(output)) {
			throw new IllegalArgumentException("jfrOutputPath already exists: " + output);
		}
		int maxEvents = maxSummaryEvents == null ? properties.defaultMaxSummaryEvents() : maxSummaryEvents;
		if (maxEvents <= 0) {
			throw new IllegalArgumentException("maxSummaryEvents must be positive");
		}
		return new JfrRecordingRequest(pid, duration, normalizedSettings, output.toString(), maxEvents, token);
	}
}
```

- [ ] **Step 5: Run test to verify it passes**

Run:

```powershell
mvn -Dtest=JfrRecordingRequestTest test
```

Expected: test passes.

- [ ] **Step 6: Commit**

```powershell
git add -- src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingProperties.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingRequest.java src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingRequestTest.java
git commit -m "Add JFR recording request model"
```

---

### Task 2: Summary Records And Defensive Parser

**Files:**

- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummary.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrGcSummary.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrAllocationSummary.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrThreadSummary.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrExecutionSampleSummary.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrCount.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrCountAndBytes.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrHeapSample.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrStackAggregate.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrThreadBlockAggregate.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummaryParser.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummaryParserTest.java`

- [ ] **Step 1: Write failing parser tests**

Create `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummaryParserTest.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class JfrSummaryParserTest {

	@TempDir
	Path tempDir;

	@Test
	void shouldParseEventCountsAndWarnForMissingStandardCategories() throws Exception {
		Path recordingFile = tempDir.resolve("custom.jfr");
		createCustomRecording(recordingFile, 5);

		JfrSummary summary = new JfrSummaryParser(10).parse(recordingFile, 100);

		assertThat(summary.eventCounts()).containsEntry("com.alibaba.TestMarker", 5L);
		assertThat(summary.durationMs()).isNotNull();
		assertThat(summary.parserWarnings()).anyMatch(w -> w.contains("allocation events were not present"));
		assertThat(summary.parserWarnings()).anyMatch(w -> w.contains("ExecutionSample events were not present"));
		assertThat(summary.allocationSummary().allocationEventCount()).isZero();
		assertThat(summary.executionSampleSummary().sampleCount()).isZero();
	}

	@Test
	void shouldStopAtMaxSummaryEvents() throws Exception {
		Path recordingFile = tempDir.resolve("limited.jfr");
		createCustomRecording(recordingFile, 25);

		JfrSummary summary = new JfrSummaryParser(10).parse(recordingFile, 7);

		assertThat(summary.eventCounts()).containsEntry("com.alibaba.TestMarker", 7L);
		assertThat(summary.parserWarnings()).anyMatch(w -> w.contains("maxSummaryEvents"));
	}

	@Test
	void shouldCreateBoundedTopListsFromRealJfrWhenEventsAreAvailable() throws Exception {
		Path recordingFile = tempDir.resolve("profile.jfr");
		createBestEffortProfileRecording(recordingFile);

		JfrSummary summary = new JfrSummaryParser(3).parse(recordingFile, 100_000);

		assertThat(summary.eventCounts()).isNotEmpty();
		assertThat(summary.allocationSummary().topAllocatedClasses()).hasSizeLessThanOrEqualTo(3);
		assertThat(summary.allocationSummary().topAllocationStacks()).hasSizeLessThanOrEqualTo(3);
		assertThat(summary.executionSampleSummary().topMethods()).hasSizeLessThanOrEqualTo(3);
		assertThat(summary.threadSummary().topBlockedThreads()).hasSizeLessThanOrEqualTo(3);
	}

	private static void createCustomRecording(Path recordingFile, int count) throws Exception {
		try (Recording recording = new Recording()) {
			recording.enable("com.alibaba.TestMarker");
			recording.start();
			for (int i = 0; i < count; i++) {
				TestMarker event = new TestMarker();
				event.message = "event-" + i;
				event.commit();
			}
			recording.stop();
			recording.dump(recordingFile);
		}
	}

	private static void createBestEffortProfileRecording(Path recordingFile) throws Exception {
		try (Recording recording = new Recording()) {
			recording.enable("jdk.ObjectAllocationInNewTLAB").withThreshold(Duration.ZERO).withStackTrace();
			recording.enable("jdk.ObjectAllocationOutsideTLAB").withThreshold(Duration.ZERO).withStackTrace();
			recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(1));
			recording.enable("jdk.ThreadPark").withThreshold(Duration.ZERO).withStackTrace();
			recording.enable("jdk.JavaMonitorEnter").withThreshold(Duration.ZERO).withStackTrace();
			recording.enable("jdk.GarbageCollection");
			recording.enable("jdk.GCHeapSummary");
			recording.start();
			List<byte[]> allocations = new ArrayList<>();
			for (int i = 0; i < 200; i++) {
				allocations.add(new byte[1024]);
			}
			Thread.sleep(50L);
			System.gc();
			Thread.sleep(50L);
			recording.stop();
			recording.dump(recordingFile);
			assertThat(allocations).isNotEmpty();
		}
	}

	@Name("com.alibaba.TestMarker")
	static class TestMarker extends Event {
		String message;
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
mvn -Dtest=JfrSummaryParserTest test
```

Expected: compilation fails because JFR summary records and parser do not exist.

- [ ] **Step 3: Add summary records**

Create each record exactly as follows.

`src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummary.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;
import java.util.Map;

public record JfrSummary(Long recordingStartEpochMs, Long recordingEndEpochMs, Long durationMs,
		JfrGcSummary gcSummary, JfrAllocationSummary allocationSummary, JfrThreadSummary threadSummary,
		JfrExecutionSampleSummary executionSampleSummary, Map<String, Long> eventCounts,
		List<String> parserWarnings) {

	public JfrSummary {
		eventCounts = Map.copyOf(eventCounts);
		parserWarnings = List.copyOf(parserWarnings);
	}
}
```

`src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrGcSummary.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrGcSummary(long gcCount, double totalGcPauseMs, double maxGcPauseMs, List<JfrCount> topGcCauses,
		List<JfrHeapSample> heapBeforeAfterSamples) {

	public JfrGcSummary {
		topGcCauses = List.copyOf(topGcCauses);
		heapBeforeAfterSamples = List.copyOf(heapBeforeAfterSamples);
	}
}
```

`src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrAllocationSummary.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrAllocationSummary(long totalAllocationBytesApprox, List<JfrCountAndBytes> topAllocatedClasses,
		List<JfrStackAggregate> topAllocationStacks, long allocationEventCount) {

	public JfrAllocationSummary {
		topAllocatedClasses = List.copyOf(topAllocatedClasses);
		topAllocationStacks = List.copyOf(topAllocationStacks);
	}
}
```

`src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrThreadSummary.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrThreadSummary(long parkEventCount, long monitorEnterEventCount, double maxBlockedMs,
		List<JfrThreadBlockAggregate> topBlockedThreads) {

	public JfrThreadSummary {
		topBlockedThreads = List.copyOf(topBlockedThreads);
	}
}
```

`src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrExecutionSampleSummary.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrExecutionSampleSummary(long sampleCount, List<JfrStackAggregate> topMethods) {

	public JfrExecutionSampleSummary {
		topMethods = List.copyOf(topMethods);
	}
}
```

`src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrCount.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record JfrCount(String name, long count) {
}
```

`src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrCountAndBytes.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record JfrCountAndBytes(String name, long count, long bytesApprox) {
}
```

`src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrHeapSample.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record JfrHeapSample(Long timestampEpochMs, Long beforeUsedBytes, Long afterUsedBytes, Long heapUsedBytes,
		Long heapCommittedBytes) {
}
```

`src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrStackAggregate.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrStackAggregate(String frame, long count, long bytesApprox, List<String> sampleStack) {

	public JfrStackAggregate {
		sampleStack = List.copyOf(sampleStack);
	}
}
```

`src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrThreadBlockAggregate.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrThreadBlockAggregate(String threadName, long count, double totalBlockedMs, double maxBlockedMs,
		List<String> sampleStack) {

	public JfrThreadBlockAggregate {
		sampleStack = List.copyOf(sampleStack);
	}
}
```

- [ ] **Step 4: Add defensive parser**

Create `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummaryParser.java` with these responsibilities:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

public class JfrSummaryParser {

	private static final int STACK_SAMPLE_LIMIT = 8;

	private final int topLimit;

	public JfrSummaryParser(int topLimit) {
		if (topLimit <= 0) {
			throw new IllegalArgumentException("topLimit must be positive");
		}
		this.topLimit = topLimit;
	}

	public JfrSummary parse(Path recordingFile, int maxSummaryEvents) {
		if (recordingFile == null || !Files.isRegularFile(recordingFile)) {
			throw new IllegalArgumentException("JFR recording file does not exist: " + recordingFile);
		}
		if (maxSummaryEvents <= 0) {
			throw new IllegalArgumentException("maxSummaryEvents must be positive");
		}
		ParserState state = new ParserState();
		try (RecordingFile file = new RecordingFile(recordingFile)) {
			while (file.hasMoreEvents()) {
				if (state.totalEvents >= maxSummaryEvents) {
					state.warnings.add("Stopped parsing after maxSummaryEvents=" + maxSummaryEvents);
					break;
				}
				RecordedEvent event = file.readEvent();
				state.observe(event);
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to parse JFR recording " + recordingFile + ": " + ex.getMessage(),
					ex);
		}
		state.addMissingCategoryWarnings();
		return state.toSummary(topLimit);
	}

	private static final class ParserState {

		private long totalEvents;
		private Instant firstStart;
		private Instant lastEnd;
		private final Map<String, Long> eventCounts = new HashMap<>();
		private final List<String> warnings = new ArrayList<>();
		private long gcCount;
		private double totalGcPauseMs;
		private double maxGcPauseMs;
		private final Map<String, Long> gcCauses = new HashMap<>();
		private final List<JfrHeapSample> heapSamples = new ArrayList<>();
		private long allocationEventCount;
		private long totalAllocationBytesApprox;
		private final Map<String, CountAndBytesAccumulator> allocatedClasses = new HashMap<>();
		private final Map<String, StackAccumulator> allocationStacks = new HashMap<>();
		private long parkEventCount;
		private long monitorEnterEventCount;
		private double maxBlockedMs;
		private final Map<String, ThreadBlockAccumulator> blockedThreads = new HashMap<>();
		private long executionSampleCount;
		private final Map<String, StackAccumulator> executionSamples = new HashMap<>();

		private void observe(RecordedEvent event) {
			totalEvents++;
			String name = event.getEventType().getName();
			eventCounts.merge(name, 1L, Long::sum);
			Instant start = event.getStartTime();
			Instant end = event.getEndTime();
			if (firstStart == null || (start != null && start.isBefore(firstStart))) {
				firstStart = start;
			}
			if (lastEnd == null || (end != null && end.isAfter(lastEnd))) {
				lastEnd = end;
			}
			try {
				switch (name) {
					case "jdk.GarbageCollection" -> observeGc(event);
					case "jdk.GCHeapSummary" -> observeHeap(event);
					case "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB" -> observeAllocation(event);
					case "jdk.ThreadPark" -> observeThreadBlock(event, true);
					case "jdk.JavaMonitorEnter" -> observeThreadBlock(event, false);
					case "jdk.ExecutionSample" -> observeExecutionSample(event);
					default -> {
					}
				}
			}
			catch (RuntimeException ex) {
				warnings.add("Unable to parse " + name + " event: " + ex.getMessage());
			}
		}

		private void observeGc(RecordedEvent event) {
			gcCount++;
			double pauseMs = durationMs(event.getDuration());
			totalGcPauseMs += pauseMs;
			maxGcPauseMs = Math.max(maxGcPauseMs, pauseMs);
			String cause = stringValue(event, "cause", "unknown");
			gcCauses.merge(cause, 1L, Long::sum);
		}

		private void observeHeap(RecordedEvent event) {
			Long heapUsed = longValue(event, "heapUsed");
			Long heapCommitted = longValue(event, "heapCommitted");
			heapSamples.add(new JfrHeapSample(epochMs(event.getStartTime()), null, null, heapUsed, heapCommitted));
		}

		private void observeAllocation(RecordedEvent event) {
			allocationEventCount++;
			long bytes = longValue(event, "allocationSize", 0L);
			totalAllocationBytesApprox += bytes;
			String className = className(event, "objectClass");
			allocatedClasses.computeIfAbsent(className, CountAndBytesAccumulator::new).add(bytes);
			List<String> stack = stackFrames(event.getStackTrace());
			String frame = stack.isEmpty() ? className : stack.get(0);
			allocationStacks.computeIfAbsent(frame, StackAccumulator::new).add(bytes, stack);
		}

		private void observeThreadBlock(RecordedEvent event, boolean park) {
			if (park) {
				parkEventCount++;
			}
			else {
				monitorEnterEventCount++;
			}
			double blockedMs = durationMs(event.getDuration());
			maxBlockedMs = Math.max(maxBlockedMs, blockedMs);
			RecordedThread thread = value(event, "eventThread", RecordedThread.class);
			String threadName = thread == null || thread.getJavaName() == null ? "unknown" : thread.getJavaName();
			blockedThreads.computeIfAbsent(threadName, ThreadBlockAccumulator::new)
				.add(blockedMs, stackFrames(event.getStackTrace()));
		}

		private void observeExecutionSample(RecordedEvent event) {
			executionSampleCount++;
			List<String> stack = stackFrames(event.getStackTrace());
			if (!stack.isEmpty()) {
				executionSamples.computeIfAbsent(stack.get(0), StackAccumulator::new).add(0L, stack);
			}
		}

		private void addMissingCategoryWarnings() {
			if (allocationEventCount == 0L) {
				warnings.add(
						"JFR allocation events were not present; use settings=profile or custom templates later for allocation detail.");
			}
			if (executionSampleCount == 0L) {
				warnings.add("JFR ExecutionSample events were not present.");
			}
			if (parkEventCount == 0L && monitorEnterEventCount == 0L) {
				warnings.add("JFR thread park/monitor events were not present.");
			}
			if (gcCount == 0L) {
				warnings.add("JFR GC events were not present.");
			}
		}

		private JfrSummary toSummary(int topLimit) {
			Long startMs = epochMs(firstStart);
			Long endMs = epochMs(lastEnd);
			Long durationMs = startMs == null || endMs == null ? null : Math.max(0L, endMs - startMs);
			return new JfrSummary(startMs, endMs, durationMs,
					new JfrGcSummary(gcCount, totalGcPauseMs, maxGcPauseMs, topCounts(gcCauses, topLimit),
							limit(heapSamples, topLimit)),
					new JfrAllocationSummary(totalAllocationBytesApprox, topCountAndBytes(allocatedClasses, topLimit),
							topStacks(allocationStacks, topLimit), allocationEventCount),
					new JfrThreadSummary(parkEventCount, monitorEnterEventCount, maxBlockedMs,
							topThreadBlocks(blockedThreads, topLimit)),
					new JfrExecutionSampleSummary(executionSampleCount, topStacks(executionSamples, topLimit)),
					eventCounts, warnings);
		}
	}

	private static List<JfrCount> topCounts(Map<String, Long> counts, int limit) {
		return counts.entrySet().stream()
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
			.limit(limit)
			.map(e -> new JfrCount(e.getKey(), e.getValue()))
			.toList();
	}

	private static List<JfrCountAndBytes> topCountAndBytes(Map<String, CountAndBytesAccumulator> values, int limit) {
		return values.values().stream()
			.sorted(Comparator.comparingLong(CountAndBytesAccumulator::bytes).reversed()
				.thenComparing(CountAndBytesAccumulator::name))
			.limit(limit)
			.map(v -> new JfrCountAndBytes(v.name(), v.count(), v.bytes()))
			.toList();
	}

	private static List<JfrStackAggregate> topStacks(Map<String, StackAccumulator> values, int limit) {
		return values.values().stream()
			.sorted(Comparator.comparingLong(StackAccumulator::count)
				.reversed()
				.thenComparing(Comparator.comparingLong(StackAccumulator::bytes).reversed())
				.thenComparing(StackAccumulator::frame))
			.limit(limit)
			.map(v -> new JfrStackAggregate(v.frame(), v.count(), v.bytes(), v.sampleStack()))
			.toList();
	}

	private static List<JfrThreadBlockAggregate> topThreadBlocks(Map<String, ThreadBlockAccumulator> values, int limit) {
		return values.values().stream()
			.sorted(Comparator.comparingDouble(ThreadBlockAccumulator::totalBlockedMs)
				.reversed()
				.thenComparing(ThreadBlockAccumulator::threadName))
			.limit(limit)
			.map(v -> new JfrThreadBlockAggregate(v.threadName(), v.count(), v.totalBlockedMs(), v.maxBlockedMs(),
					v.sampleStack()))
			.toList();
	}

	private static <T> List<T> limit(List<T> values, int limit) {
		return values.stream().limit(limit).toList();
	}

	private static double durationMs(Duration duration) {
		return duration == null ? 0.0d : duration.toNanos() / 1_000_000.0d;
	}

	private static Long epochMs(Instant instant) {
		return instant == null ? null : instant.toEpochMilli();
	}

	private static String className(RecordedEvent event, String fieldName) {
		RecordedClass recordedClass = value(event, fieldName, RecordedClass.class);
		if (recordedClass == null || recordedClass.getName() == null) {
			return "unknown";
		}
		return recordedClass.getName();
	}

	private static String stringValue(RecordedEvent event, String fieldName, String fallback) {
		Object value = fieldValue(event, fieldName);
		return value == null ? fallback : value.toString();
	}

	private static Long longValue(RecordedEvent event, String fieldName) {
		Object value = fieldValue(event, fieldName);
		if (value instanceof Number number) {
			return number.longValue();
		}
		return null;
	}

	private static long longValue(RecordedEvent event, String fieldName, long fallback) {
		Long value = longValue(event, fieldName);
		return value == null ? fallback : value;
	}

	private static <T> T value(RecordedEvent event, String fieldName, Class<T> type) {
		Object value = fieldValue(event, fieldName);
		return type.isInstance(value) ? type.cast(value) : null;
	}

	private static Object fieldValue(RecordedEvent event, String fieldName) {
		if (!event.hasField(fieldName)) {
			return null;
		}
		return event.getValue(fieldName);
	}

	private static List<String> stackFrames(RecordedStackTrace stackTrace) {
		if (stackTrace == null || stackTrace.getFrames() == null) {
			return List.of();
		}
		return stackTrace.getFrames().stream().limit(STACK_SAMPLE_LIMIT).map(JfrSummaryParser::frameName).toList();
	}

	private static String frameName(RecordedFrame frame) {
		RecordedMethod method = frame.getMethod();
		if (method == null || method.getType() == null) {
			return "unknown";
		}
		return method.getType().getName() + "." + method.getName();
	}

	private static final class CountAndBytesAccumulator {

		private final String name;
		private long count;
		private long bytes;

		private CountAndBytesAccumulator(String name) {
			this.name = name;
		}

		private void add(long bytes) {
			this.count++;
			this.bytes += bytes;
		}

		private String name() {
			return name;
		}

		private long count() {
			return count;
		}

		private long bytes() {
			return bytes;
		}
	}

	private static final class StackAccumulator {

		private final String frame;
		private long count;
		private long bytes;
		private List<String> sampleStack = List.of();

		private StackAccumulator(String frame) {
			this.frame = frame;
		}

		private void add(long bytes, List<String> stack) {
			this.count++;
			this.bytes += bytes;
			if (sampleStack.isEmpty() && stack != null && !stack.isEmpty()) {
				this.sampleStack = List.copyOf(stack);
			}
		}

		private String frame() {
			return frame;
		}

		private long count() {
			return count;
		}

		private long bytes() {
			return bytes;
		}

		private List<String> sampleStack() {
			return sampleStack;
		}
	}

	private static final class ThreadBlockAccumulator {

		private final String threadName;
		private long count;
		private double totalBlockedMs;
		private double maxBlockedMs;
		private List<String> sampleStack = List.of();

		private ThreadBlockAccumulator(String threadName) {
			this.threadName = threadName;
		}

		private void add(double blockedMs, List<String> stack) {
			this.count++;
			this.totalBlockedMs += blockedMs;
			this.maxBlockedMs = Math.max(this.maxBlockedMs, blockedMs);
			if (sampleStack.isEmpty() && stack != null && !stack.isEmpty()) {
				this.sampleStack = List.copyOf(stack);
			}
		}

		private String threadName() {
			return threadName;
		}

		private long count() {
			return count;
		}

		private double totalBlockedMs() {
			return totalBlockedMs;
		}

		private double maxBlockedMs() {
			return maxBlockedMs;
		}

		private List<String> sampleStack() {
			return sampleStack;
		}
	}
}
```

- [ ] **Step 5: Run parser tests and fix JDK field drift**

Run:

```powershell
mvn -Dtest=JfrSummaryParserTest test
```

Expected: tests pass. If a JDK field name differs, fix `JfrSummaryParser` by making the field optional and adding a parser warning, not by weakening `eventCounts` assertions.

- [ ] **Step 6: Commit**

```powershell
git add -- src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/Jfr*.java src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummaryParserTest.java
git commit -m "Add JFR summary parser"
```

---

### Task 3: Collector Recording Flow

**Files:**

- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingResult.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmRuntimeCollector.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorJfrTest.java`

- [ ] **Step 1: Write failing collector tests**

Create `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorJfrTest.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapDumpSummarizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeJvmRuntimeCollectorJfrTest {

	private static final SharkHeapDumpSummarizer TEST_HEAP_SUMMARIZER = new SharkHeapDumpSummarizer(40, 32000);

	@TempDir
	Path tempDir;

	@Test
	void shouldRejectMissingConfirmationBeforeRunningCommands() {
		RecordingCommandExecutor executor = new RecordingCommandExecutor();
		SafeJvmRuntimeCollector collector = testCollector(executor);
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> collector.recordJfr(new JfrRecordingRequest(123L, 10, "profile", output.toString(),
				100, "")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confirmationToken");
		assertThat(executor.commands).isEmpty();
	}

	@Test
	void shouldReturnMissingDataWhenJfrStartIsUnsupported() {
		RecordingCommandExecutor executor = new RecordingCommandExecutor();
		executor.nextResult = new CommandExecutionResult(List.of("jcmd", "123", "help", "JFR.start"), 1,
				"Unknown diagnostic command", false, false, 5L, "Unknown diagnostic command");
		SafeJvmRuntimeCollector collector = testCollector(executor);
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		JfrRecordingResult result = collector.recordJfr(new JfrRecordingRequest(123L, 10, "profile",
				output.toString(), 100, "confirmed"));

		assertThat(result.jfrPath()).isNull();
		assertThat(result.summary()).isNull();
		assertThat(result.missingData()).contains("jfrSupport", "jfrRecording");
		assertThat(result.warnings()).anyMatch(w -> w.contains("not available") || w.contains("Unknown"));
		assertThat(result.commandsRun()).containsExactly("jcmd 123 help JFR.start");
	}

	@Test
	void shouldRunOneShotRecordingWithDynamicTimeoutAndParseFile() throws Exception {
		RecordingCommandExecutor executor = new RecordingCommandExecutor();
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();
		executor.fileToCreateAfterSecondCommand = output;
		executor.results.add(new CommandExecutionResult(List.of("jcmd", "123", "help", "JFR.start"), 0,
				"JFR.start\nSyntax", false, false, 5L, ""));
		executor.results.add(new CommandExecutionResult(List.of("jcmd", "123", "JFR.start"), 0, "Started recording",
				false, false, 10L, ""));
		JfrSummary summary = emptySummary();
		SafeJvmRuntimeCollector collector = testCollector(executor, (path, maxEvents) -> {
			assertThat(path).isEqualTo(output);
			assertThat(maxEvents).isEqualTo(50);
			return summary;
		});

		JfrRecordingResult result = collector.recordJfr(new JfrRecordingRequest(123L, 12, "default",
				output.toString(), 50, "confirmed"));

		assertThat(result.jfrPath()).isEqualTo(output.toString());
		assertThat(result.fileSizeBytes()).isGreaterThan(0L);
		assertThat(result.summary()).isSameAs(summary);
		assertThat(result.missingData()).isEmpty();
		assertThat(result.commandsRun()).containsExactly("jcmd 123 help JFR.start",
				"jcmd 123 JFR.start name=java-tuning-agent-123-" + result.startedAtEpochMs()
						+ " settings=default duration=12s filename=" + output + " disk=true");
		assertThat(executor.options.get(1).timeoutMs()).isEqualTo(12_000L);
	}

	@Test
	void shouldReturnMissingFileWhenRecordingCompletesButFileDoesNotExist() {
		RecordingCommandExecutor executor = new RecordingCommandExecutor();
		Path output = tempDir.resolve("missing.jfr").toAbsolutePath().normalize();
		executor.results.add(new CommandExecutionResult(List.of("jcmd", "123", "help", "JFR.start"), 0,
				"JFR.start\nSyntax", false, false, 5L, ""));
		executor.results.add(new CommandExecutionResult(List.of("jcmd", "123", "JFR.start"), 0, "Started recording",
				false, false, 10L, ""));
		SafeJvmRuntimeCollector collector = testCollector(executor);

		JfrRecordingResult result = collector.recordJfr(new JfrRecordingRequest(123L, 10, "profile",
				output.toString(), 100, "confirmed"));

		assertThat(result.jfrPath()).isNull();
		assertThat(result.summary()).isNull();
		assertThat(result.missingData()).contains("jfrFile", "jfrSummary");
		assertThat(result.warnings()).anyMatch(w -> w.contains("file was not found"));
	}

	private static SafeJvmRuntimeCollector testCollector(CommandExecutor executor) {
		return testCollector(executor, (path, maxEvents) -> emptySummary());
	}

	private static SafeJvmRuntimeCollector testCollector(CommandExecutor executor, JfrSummaryParserAdapter parser) {
		return new SafeJvmRuntimeCollector(executor, RuntimeCollectionPolicy.safeReadonly(), TEST_HEAP_SUMMARIZER,
				false, RepeatedSamplingProperties.defaults(), new JfrRecordingProperties(30, 5, 300, 0L, 200_000, 10),
				parser,
				SafeJvmRuntimeCollector::sleepUncheckedForTests);
	}

	private static JfrSummary emptySummary() {
		return new JfrSummary(null, null, null, new JfrGcSummary(0, 0, 0, List.of(), List.of()),
				new JfrAllocationSummary(0, List.of(), List.of(), 0), new JfrThreadSummary(0, 0, 0, List.of()),
				new JfrExecutionSampleSummary(0, List.of()), java.util.Map.of(), List.of());
	}

	private static final class RecordingCommandExecutor implements CommandExecutor {

		private final List<List<String>> commands = new ArrayList<>();
		private final List<CommandExecutionOptions> options = new ArrayList<>();
		private final List<CommandExecutionResult> results = new ArrayList<>();
		private CommandExecutionResult nextResult;
		private Path fileToCreateAfterSecondCommand;

		@Override
		public String run(List<String> command) {
			throw new UnsupportedOperationException("JFR should use structured execute");
		}

		@Override
		public CommandExecutionResult execute(List<String> command, CommandExecutionOptions options) {
			this.commands.add(command);
			this.options.add(options);
			if (fileToCreateAfterSecondCommand != null && this.commands.size() == 2) {
				try {
					Files.writeString(fileToCreateAfterSecondCommand, "not-a-real-jfr-but-parser-is-stubbed");
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}
			if (nextResult != null) {
				return nextResult;
			}
			if (!results.isEmpty()) {
				return results.remove(0);
			}
			return new CommandExecutionResult(command, 0, "", false, false, 1L, "");
		}
	}
}
```

The test references two package-private seams that must be added in implementation:

```java
@FunctionalInterface
interface JfrSummaryParserAdapter {
	JfrSummary parse(Path path, int maxSummaryEvents);
}
```

and

```java
static void sleepUncheckedForTests(long millis) {
}
```

Use the existing package-private constructor pattern in `SafeJvmRuntimeCollector` to avoid sleeping in unit tests.

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
mvn -Dtest=SafeJvmRuntimeCollectorJfrTest test
```

Expected: compilation fails because collector JFR APIs do not exist.

- [ ] **Step 3: Add result model**

Create `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingResult.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrRecordingResult(long pid, String jfrPath, long fileSizeBytes, long startedAtEpochMs, long elapsedMs,
		List<String> commandsRun, JfrSummary summary, List<String> warnings, List<String> missingData) {

	public JfrRecordingResult {
		commandsRun = List.copyOf(commandsRun);
		warnings = List.copyOf(warnings);
		missingData = List.copyOf(missingData);
	}
}
```

- [ ] **Step 4: Extend collector interface**

Modify `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmRuntimeCollector.java`:

```java
default JfrRecordingResult recordJfr(JfrRecordingRequest request) {
	throw new UnsupportedOperationException("JFR recording is not supported by this collector implementation");
}
```

- [ ] **Step 5: Add parser adapter**

Create `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummaryParserAdapter.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Path;

@FunctionalInterface
interface JfrSummaryParserAdapter {

	JfrSummary parse(Path path, int maxSummaryEvents);
}
```

Make `JfrSummaryParser` implement `JfrSummaryParserAdapter`:

```java
public class JfrSummaryParser implements JfrSummaryParserAdapter {
```

- [ ] **Step 6: Extend `SafeJvmRuntimeCollector` constructors**

Modify constructor fields in `SafeJvmRuntimeCollector`:

```java
private final JfrRecordingProperties jfrRecordingProperties;

private final JfrSummaryParserAdapter jfrSummaryParser;
```

Keep existing public constructors source-compatible by delegating to a new package-private constructor:

```java
SafeJvmRuntimeCollector(CommandExecutor executor, RuntimeCollectionPolicy policy,
		SharkHeapDumpSummarizer heapDumpSummarizer, boolean autoHeapSummary,
		RepeatedSamplingProperties repeatedSamplingProperties, LongConsumer sleeper) {
	this(executor, policy, heapDumpSummarizer, autoHeapSummary, repeatedSamplingProperties,
			JfrRecordingProperties.defaults(), new JfrSummaryParser(JfrRecordingProperties.defaults().topLimit()),
			sleeper);
}

SafeJvmRuntimeCollector(CommandExecutor executor, RuntimeCollectionPolicy policy,
		SharkHeapDumpSummarizer heapDumpSummarizer, boolean autoHeapSummary,
		RepeatedSamplingProperties repeatedSamplingProperties, JfrRecordingProperties jfrRecordingProperties,
		JfrSummaryParserAdapter jfrSummaryParser, LongConsumer sleeper) {
	this.executor = executor;
	this.policy = policy;
	this.heapDumpSummarizer = heapDumpSummarizer;
	this.autoHeapSummary = autoHeapSummary;
	this.repeatedSamplingProperties = repeatedSamplingProperties;
	this.jfrRecordingProperties = jfrRecordingProperties;
	this.jfrSummaryParser = jfrSummaryParser;
	this.sleeper = sleeper;
}
```

Add a no-op test sleeper:

```java
static void sleepUncheckedForTests(long millis) {
}
```

- [ ] **Step 7: Implement `recordJfr`**

Add imports to `SafeJvmRuntimeCollector`:

```java
import java.nio.file.Path;
import java.util.Objects;
```

Add method:

```java
@Override
public JfrRecordingResult recordJfr(JfrRecordingRequest request) {
	long startedAt = System.currentTimeMillis();
	JfrRecordingRequest normalized = request.normalized(jfrRecordingProperties);
	String pidValue = Long.toString(normalized.pid());
	Path output = Path.of(normalized.jfrOutputPath()).toAbsolutePath().normalize();
	List<String> commandsRun = new ArrayList<>();
	List<String> warnings = new ArrayList<>();
	List<String> missingData = new ArrayList<>();

	List<String> supportCommand = jfrHelpCommand(pidValue);
	commandsRun.add(String.join(" ", supportCommand));
	CommandExecutionResult support = executor.execute(supportCommand, new CommandExecutionOptions(15_000L, 1024 * 1024));
	if (!support.succeeded() || support.output() == null || !support.output().contains("JFR.start")) {
		missingData.add("jfrSupport");
		missingData.add("jfrRecording");
		warnings.add("JFR.start is not available on target JVM: " + firstNonBlank(support.failureMessage(),
				support.output(), "no help output"));
		return jfrResult(normalized.pid(), null, 0L, startedAt, commandsRun, null, warnings, missingData);
	}

	String recordingName = "java-tuning-agent-" + normalized.pid() + "-" + startedAt;
	List<String> recordCommand = jfrStartCommand(pidValue, recordingName, normalized.settings(),
			normalized.durationSeconds(), output.toString());
	commandsRun.add(String.join(" ", recordCommand));
	long timeoutMs = normalized.durationSeconds() * 1000L + jfrRecordingProperties.completionGraceMs();
	CommandExecutionResult record = executor.execute(recordCommand,
			new CommandExecutionOptions(timeoutMs, 1024 * 1024));
	if (!record.succeeded()) {
		missingData.add("jfrRecording");
		missingData.add("jfrFile");
		missingData.add("jfrSummary");
		warnings.add("Unable to start JFR recording " + recordingName + ": "
				+ firstNonBlank(record.failureMessage(), record.output(), "unknown failure"));
		return jfrResult(normalized.pid(), null, 0L, startedAt, commandsRun, null, warnings, missingData);
	}

	if (!waitForStableFile(output, jfrRecordingProperties.completionGraceMs())) {
		missingData.add("jfrFile");
		missingData.add("jfrSummary");
		warnings.add("JFR recording command finished but file was not found at " + output);
		return jfrResult(normalized.pid(), null, 0L, startedAt, commandsRun, null, warnings, missingData);
	}

	long fileSize = safeSize(output);
	try {
		JfrSummary summary = jfrSummaryParser.parse(output, normalized.maxSummaryEvents());
		warnings.addAll(summary.parserWarnings());
		return jfrResult(normalized.pid(), output.toString(), fileSize, startedAt, commandsRun, summary, warnings,
				missingData);
	}
	catch (RuntimeException ex) {
		missingData.add("jfrSummary");
		warnings.add("Unable to parse JFR recording: " + ex.getMessage());
		return jfrResult(normalized.pid(), output.toString(), fileSize, startedAt, commandsRun, null, warnings,
				missingData);
	}
}
```

Add helpers:

```java
private List<String> jfrHelpCommand(String pidValue) {
	return List.of(JCMD, pidValue, "help", "JFR.start");
}

private List<String> jfrStartCommand(String pidValue, String name, String settings, int durationSeconds,
		String absolutePath) {
	return List.of(JCMD, pidValue, "JFR.start", "name=" + name, "settings=" + settings,
			"duration=" + durationSeconds + "s", "filename=" + absolutePath, "disk=true");
}

private boolean waitForStableFile(Path output, long graceMs) {
	long deadline = System.currentTimeMillis() + Math.max(0L, graceMs);
	long previousSize = -1L;
	while (System.currentTimeMillis() <= deadline) {
		long size = safeSize(output);
		if (size > 0L && size == previousSize) {
			return true;
		}
		previousSize = size;
		sleeper.accept(100L);
	}
	return safeSize(output) > 0L;
}

private long safeSize(Path output) {
	try {
		return Files.isRegularFile(output) ? Files.size(output) : 0L;
	}
	catch (Exception ex) {
		return 0L;
	}
}

private JfrRecordingResult jfrResult(long pid, String path, long fileSizeBytes, long startedAtEpochMs,
		List<String> commandsRun, JfrSummary summary, List<String> warnings, List<String> missingData) {
	return new JfrRecordingResult(pid, path, fileSizeBytes, startedAtEpochMs,
			Math.max(0L, System.currentTimeMillis() - startedAtEpochMs), List.copyOf(commandsRun), summary,
			List.copyOf(warnings), List.copyOf(missingData));
}

private static String firstNonBlank(String... values) {
	for (String value : values) {
		if (value != null && !value.isBlank()) {
			return value.trim();
		}
	}
	return "";
}
```

- [ ] **Step 8: Run collector JFR tests**

Run:

```powershell
mvn -Dtest=SafeJvmRuntimeCollectorJfrTest test
```

Expected: test passes.

- [ ] **Step 9: Run existing collector tests**

Run:

```powershell
mvn -Dtest=SafeJvmRuntimeCollectorTest test
```

Expected: existing collector tests still pass.

- [ ] **Step 10: Commit**

```powershell
git add -- src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrRecordingResult.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JfrSummaryParserAdapter.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmRuntimeCollector.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorJfrTest.java
git commit -m "Add one-shot JFR recording collection"
```

---

### Task 4: Configuration And MCP Tool

**Files:**

- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/JavaTuningMcpTools.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`

- [ ] **Step 1: Write failing schema expectations**

Modify `McpToolSchemaContractTest.everyRegisteredToolExposesParsableInputSchemaWithExpectedShape` by adding this switch case:

```java
case "recordJvmFlightRecording" -> {
	JsonNode request = schema.path("properties").path("request");
	assertThat(request.path("type").asText()).isEqualTo("object");
	assertThat(request.path("properties").path("pid").path("type").asText()).isIn("integer", "number");
	assertThat(request.path("properties").path("durationSeconds").path("type").asText()).isIn("integer",
			"number");
	assertThat(request.path("properties").path("settings").path("type").asText()).isEqualTo("string");
	assertThat(request.path("properties").path("jfrOutputPath").path("type").asText()).isEqualTo("string");
	assertThat(request.path("properties").path("maxSummaryEvents").path("type").asText()).isIn("integer",
			"number");
	assertThat(request.path("properties").path("confirmationToken").path("type").asText()).isEqualTo("string");
	assertThat(def.description()).contains("Java Flight Recorder").contains("confirmationToken").contains(".jfr");
}
```

Modify `privilegedToolsShouldDocumentKeyFieldsInSchema` to include the new tool:

```java
if (!"collectMemoryGcEvidence".equals(def.name()) && !"generateTuningAdvice".equals(def.name())
		&& !"generateOfflineTuningAdvice".equals(def.name())
		&& !"recordJvmFlightRecording".equals(def.name())) {
	continue;
}
```

Add registration test:

```java
@Test
void recordJvmFlightRecordingShouldBeRegistered() {
	assertThat(Arrays.stream(toolCallbackProvider.getToolCallbacks()).map(callback -> callback.getToolDefinition())
		.map(def -> def.name())).contains("recordJvmFlightRecording");
}
```

- [ ] **Step 2: Run schema test to verify it fails**

Run:

```powershell
mvn -Dtest=McpToolSchemaContractTest test
```

Expected: fails because the tool is not registered.

- [ ] **Step 3: Wire JFR properties**

Modify `JavaTuningAgentConfig.jvmRuntimeCollector` signature to add:

```java
@Value("${java-tuning-agent.jfr.default-duration-seconds:30}") int defaultJfrDurationSeconds,
@Value("${java-tuning-agent.jfr.min-duration-seconds:5}") int minJfrDurationSeconds,
@Value("${java-tuning-agent.jfr.max-duration-seconds:300}") int maxJfrDurationSeconds,
@Value("${java-tuning-agent.jfr.completion-grace-ms:10000}") long jfrCompletionGraceMs,
@Value("${java-tuning-agent.jfr.default-max-summary-events:200000}") int defaultJfrMaxSummaryEvents,
@Value("${java-tuning-agent.jfr.top-limit:10}") int jfrTopLimit
```

Construct properties and parser:

```java
JfrRecordingProperties jfrProperties = new JfrRecordingProperties(defaultJfrDurationSeconds, minJfrDurationSeconds,
		maxJfrDurationSeconds, jfrCompletionGraceMs, defaultJfrMaxSummaryEvents, jfrTopLimit);
return new SafeJvmRuntimeCollector(commandExecutor, policy, sharkHeapDumpSummarizer, autoHeapSummary,
		new RepeatedSamplingProperties(defaultSampleCount, defaultIntervalMs, maxSampleCount, maxTotalDurationMs),
		jfrProperties, new JfrSummaryParser(jfrProperties.topLimit()), SafeJvmRuntimeCollector::sleepUnchecked);
```

If `sleepUnchecked` is private, make it package-private static:

```java
static void sleepUnchecked(long millis) {
```

- [ ] **Step 4: Add MCP tool**

Modify `JavaTuningMcpTools.java` imports:

```java
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrRecordingRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrRecordingResult;
```

Add method:

```java
@Tool(description = """
		Record one short Java Flight Recorder session for a target JVM and return the .jfr path plus a lightweight summary. \
		Requires a non-blank confirmationToken and an absolute jfrOutputPath ending in .jfr. \
		Example: {"request":{"pid":12345,"durationSeconds":30,"settings":"profile","jfrOutputPath":"C:/tmp/app.jfr","maxSummaryEvents":200000,"confirmationToken":"user-approved"}}""")
public JfrRecordingResult recordJvmFlightRecording(
		@ToolParam(description = "JfrRecordingRequest JSON: pid, durationSeconds, settings, jfrOutputPath, maxSummaryEvents, confirmationToken.") JfrRecordingRequest request) {
	return collector.recordJfr(request);
}
```

- [ ] **Step 5: Run schema test**

Run:

```powershell
mvn -Dtest=McpToolSchemaContractTest test
```

Expected: test passes.

- [ ] **Step 6: Commit**

```powershell
git add -- src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/JavaTuningMcpTools.java src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java
git commit -m "Expose short JFR recording MCP tool"
```

---

### Task 5: Public Documentation And Cursor Workflow

**Files:**

- Modify: `README.md`
- Modify: `.cursor/skills/java-tuning-agent-workflow/SKILL.md`
- Modify: `.cursor/skills/java-tuning-agent-workflow/reference.md`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpPublicDocumentationContractTest.java`

- [ ] **Step 1: Write failing documentation contract**

Modify `McpPublicDocumentationContractTest.readmeAndCursorReferenceShouldMentionEveryPublicTool` assertion:

```java
assertThat(readme).contains("analysisDepth", "heapDumpAbsolutePath", "confirmationToken", "sampleCount",
		"intervalMillis", "recordJvmFlightRecording", "durationSeconds", "settings", "jfrOutputPath",
		"maxSummaryEvents");
assertThat(reference).contains("analysisDepth", "heapDumpAbsolutePath", "confirmationToken", "sampleCount",
		"intervalMillis", "recordJvmFlightRecording", "durationSeconds", "settings", "jfrOutputPath",
		"maxSummaryEvents");
assertThat(skill).contains("inspectJvmRuntimeRepeated", "recordJvmFlightRecording", "jfrOutputPath");
```

- [ ] **Step 2: Run documentation test to verify it fails**

Run:

```powershell
mvn -Dtest=McpPublicDocumentationContractTest test
```

Expected: fails because docs do not mention the new tool and fields.

- [ ] **Step 3: Update README tool count and live tool table**

In `README.md`, update the tool count from current value to one higher. Add a row in the live tools table:

```markdown
| `recordJvmFlightRecording` | Record one short Java Flight Recorder session for a PID and return the `.jfr` path plus a bounded summary of GC, allocation, thread/lock, and execution sample events. Requires `confirmationToken` and an absolute `jfrOutputPath`. |
```

Add a new section after the Memory/GC diagnosis flow:

```markdown
## JFR short profiling flow

Use `recordJvmFlightRecording` when a lightweight snapshot or repeated `jstat` samples show that you need profiling evidence rather than another heap artifact.

Required fields:

- `pid`: target JVM from `listJavaApps`
- `durationSeconds`: bounded recording window, typically 30
- `settings`: `profile` for more profiling signal or `default` for lower overhead
- `jfrOutputPath`: absolute path ending in `.jfr`; the file must not already exist
- `maxSummaryEvents`: parser event cap, typically `200000`
- `confirmationToken`: non-blank approval token

The tool runs one bounded `jcmd JFR.start ... duration=<Ns> filename=<path>` recording. It does not expose public `JFR.stop` lifecycle management, does not overwrite existing recordings, and can feed JFR findings into advice when its `summary` is supplied as `jfrSummary`.
```

Update security notes to mention JFR short recording as privileged.

- [ ] **Step 4: Update Cursor workflow skill**

In `.cursor/skills/java-tuning-agent-workflow/SKILL.md`, add `recordJvmFlightRecording` to the live workflow after repeated sampling and before heap dump/deep artifacts:

```markdown
Use `recordJvmFlightRecording` only after explicit user approval. Ask for an absolute `jfrOutputPath` ending in `.jfr`, a short `durationSeconds` window, and whether the user wants `profile` or `default` settings. Do not request this tool for default lightweight inspection.
```

- [ ] **Step 5: Update Cursor reference**

In `.cursor/skills/java-tuning-agent-workflow/reference.md`, add JSON template:

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

Also document result fields:

```markdown
Result fields to inspect: `jfrPath`, `fileSizeBytes`, `summary.eventCounts`, `summary.gcSummary`, `summary.allocationSummary`, `summary.threadSummary`, `summary.executionSampleSummary`, `warnings`, and `missingData`.
```

- [ ] **Step 6: Run documentation contract**

Run:

```powershell
mvn -Dtest=McpPublicDocumentationContractTest test
```

Expected: test passes.

- [ ] **Step 7: Commit**

```powershell
git add -- README.md .cursor/skills/java-tuning-agent-workflow/SKILL.md .cursor/skills/java-tuning-agent-workflow/reference.md src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpPublicDocumentationContractTest.java
git commit -m "Document short JFR recording workflow"
```

---

### Task 6: Full Verification And Package Build

**Files:**

- No source changes expected unless verification finds a real failure.

- [ ] **Step 1: Run focused JFR test set**

Run:

```powershell
mvn -Dtest=JfrRecordingRequestTest,JfrSummaryParserTest,SafeJvmRuntimeCollectorJfrTest,McpToolSchemaContractTest,McpPublicDocumentationContractTest test
```

Expected: all selected tests pass.

- [ ] **Step 2: Run full test suite**

Run:

```powershell
mvn test
```

Expected: all tests pass.

- [ ] **Step 3: Run package build**

Run:

```powershell
mvn -DskipTests package
```

Expected: build succeeds.

- [ ] **Step 4: Inspect git state**

Run:

```powershell
git status --short
```

Expected: no uncommitted changes.

- [ ] **Step 5: Commit verification fixes only if needed**

If any verification command required code or docs fixes, commit only those fixes:

```powershell
git add -- <fixed-files>
git commit -m "Stabilize JFR recording verification"
```

If no fixes were needed, do not create an empty commit.

---

## Self-Review Notes

- Spec coverage: Tasks cover request/result models, one-shot `JFR.start`, support probing, bounded parser summaries, MCP exposure, configuration, docs, schema drift, and verification.
- Scope guard: The plan intentionally does not add `generateTuningAdvice` integration, lifecycle tools, custom `.jfc` templates, `path-to-gc-roots`, or full event parsing.
- Type consistency: Public names match the approved spec: `recordJvmFlightRecording`, `JfrRecordingRequest`, `JfrRecordingResult`, `JfrSummary`, `durationSeconds`, `settings`, `jfrOutputPath`, `maxSummaryEvents`, and `confirmationToken`.
