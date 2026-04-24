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
