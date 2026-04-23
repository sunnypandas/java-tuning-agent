package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SuspectedHolderSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ThreadDumpSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryGcDiagnosisEngineTest {

	@Test
	void shouldReportLeakSuspicionFromHistogramEvidence() {
		ClassHistogramSummary histogram = new ClassHistogramParser().parse("""
				 1: 600 157286400 com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord
				""");
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(14628L,
				new JvmMemorySnapshot(100L * 1024L * 1024L, 256L * 1024L * 1024L, 512L * 1024L * 1024L, null, null,
						null, null, null),
				new JvmGcSnapshot("G1", 100L, 100L, 0L, 0L, 40.0d), List.of("-XX:+UseG1GC"), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot, histogram, null, List.of(), List.of(), null, null);
		CodeContextSummary context = CodeContextSummary.withoutSource(List.of(), java.util.Map.of(), List.of());

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, context, "local", "diagnose-memory");

		assertThat(report.findings()).extracting(TuningFinding::title).contains("Suspected retained-object leak");
		assertThat(report.confidence()).isEqualTo("high");
		assertThat(report.confidenceReasons()).isNotEmpty();
	}

	@Test
	void shouldReportRetentionEvidenceFromHeapRetentionAnalysis() {
		HeapRetentionAnalysisResult retention = new HeapRetentionAnalysisResult(true, "dominator-style", List.of(), "",
				new HeapRetentionSummary(List.of(),
						List.of(new SuspectedHolderSummary("com.example.CacheHolder", "static-field-owner", 24_000_000L,
								24_000_000L, 3L, "com.example.CacheHolder.INSTANCE", "java.util.Map",
								"static holder")),
						List.of(), List.of(),
						new HeapRetentionConfidence("medium", List.of("retained bytes are approximate"),
								List.of("Engine=dominator-style")),
						"### Heap retention analysis\n\nEngine=dominator-style", true, List.of(), ""),
				"### Heap retention analysis\n\nEngine=dominator-style");
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(99L,
				new JvmMemorySnapshot(1L, 1L, 1L, null, null, null, null, null),
				new JvmGcSnapshot("G1", 1L, 1L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot, null, null, List.of(), List.of(), null, null,
				retention);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.empty(), "local", "diagnose-memory");

		assertThat(report.findings()).extracting(TuningFinding::title)
			.contains(HeapRetentionInsightsRule.FINDING_TITLE);
		assertThat(report.confidenceReasons())
			.anyMatch(r -> r.contains("holder-oriented retained-style approximation evidence"));
		assertThat(report.confidence()).isEqualTo("medium");
	}

	@Test
	void shouldReportHighHeapPressureFromRuntimeCounters() {
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(123L,
				new JvmMemorySnapshot(920L * 1024L * 1024L, 1024L * 1024L * 1024L, 1024L * 1024L * 1024L, null, null,
						null, null, null),
				new JvmGcSnapshot("G1", 1200L, 0L, 0L, 0L, null), List.of("-XX:+UseG1GC"), "", null, null,
				new JvmCollectionMetadata(List.of("jcmd 123 GC.heap_info"), 0L, 0L, false), List.of());
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot, null, null, List.of(), List.of(), null, null);
		CodeContextSummary context = CodeContextSummary.withoutSource(List.of("spring-boot-starter-web"),
				java.util.Map.of("server.tomcat.threads.max", "200"), List.of("orders"));

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, context, "prod", "lower-gc-pause");

		assertThat(report.findings()).extracting(TuningFinding::title).contains("High heap pressure");
		assertThat(report.recommendations()).extracting(TuningRecommendation::category).contains("jvm-gc");
	}

	@Test
	void shouldReportHighHeapPressureFromOldGenWhenOverallHeapRatioBelowThreshold() {
		// Mirrors G1 demos: ~192MiB used of 256MiB Xmx (~0.75) but jstat old space ~86%.
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(20380L,
				new JvmMemorySnapshot(191L * 1024L * 1024L, 256L * 1024L * 1024L, 256L * 1024L * 1024L, null, null,
						null, null, null),
				new JvmGcSnapshot("G1", 200L, 0L, 8L, 0L, 86.22d), List.of("-XX:+UseG1GC"), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot, null, null, List.of(), List.of(), null, null);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.withoutSource(List.of(), Map.of(), List.of()), "local", "stabilize-heap");

		assertThat(report.findings()).extracting(TuningFinding::title).contains("High heap pressure");
		assertThat(report.findings())
			.filteredOn(f -> "High heap pressure".equals(f.title()))
			.first()
			.satisfies(f -> assertThat(f.evidence()).doesNotContain("unavailable or zero")
				.contains("oldGenUsagePercent=86.22")
				.contains("heapUsedMb=191"));
	}

	@Test
	void shouldReportLeakSuspicionWhenPrimitiveByteArrayDominatesHistogram() {
		ClassHistogramSummary histogram = new ClassHistogramParser().parse("""
				 1: 500 52428800 [B
				""");
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(1L,
				new JvmMemorySnapshot(0L, 0L, 256L * 1024L * 1024L, null, null, null, null, null),
				new JvmGcSnapshot("G1", 5L, 10L, 0L, 0L, 30.0d), List.of("-XX:+UseG1GC"), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot, histogram, null, List.of(), List.of(), null, null);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.withoutSource(List.of(), java.util.Map.of(), List.of()), "local",
					"diagnose-memory");

		assertThat(report.findings()).extracting(TuningFinding::title).contains("Suspected retained-object leak");
		assertThat(report.nextSteps()).anyMatch(s -> s.contains("heap_dump") || s.contains("Heap dump"));
	}

	@Test
	void shouldReportDeadlockFromThreadDumpSummary() {
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(1L,
				new JvmMemorySnapshot(1L, 1L, 1L, null, null, null, null, null),
				new JvmGcSnapshot("G1", 1L, 1L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		ThreadDumpSummary threadDump = new ThreadDumpSummary(2, Map.of("BLOCKED", 2L),
				List.of("Found one Java-level deadlock:", "\"worker-1\" waiting to lock"));
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot, null, threadDump, List.of(), List.of(), null, null);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.withoutSource(List.of(), java.util.Map.of(), List.of()), "local",
					"diagnose");

		assertThat(report.findings()).extracting(TuningFinding::title)
			.contains(ThreadDumpInsightsRule.DEADLOCK_FINDING_TITLE);
		assertThat(report.confidence()).isEqualTo("high");
		assertThat(report.confidenceReasons()).anyMatch(r -> r.contains("deadlock"));
	}

	@Test
	void shouldReportBlockedThreadsFromThreadDumpSummary() {
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(1L,
				new JvmMemorySnapshot(1L, 1L, 1L, null, null, null, null, null),
				new JvmGcSnapshot("G1", 1L, 1L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		ThreadDumpSummary threadDump = new ThreadDumpSummary(20, Map.of("BLOCKED", 5L, "RUNNABLE", 15L), List.of());
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot, null, threadDump, List.of(), List.of(), null, null);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.withoutSource(List.of(), java.util.Map.of(), List.of()), "local",
					"diagnose");

		assertThat(report.findings()).extracting(TuningFinding::title)
			.contains(ThreadDumpInsightsRule.BLOCKED_SEVERE_TITLE);
	}

	@Test
	void shouldReportModerateBlockedInLargeThreadPool() {
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(1L,
				new JvmMemorySnapshot(1L, 1L, 1L, null, null, null, null, null),
				new JvmGcSnapshot("G1", 1L, 1L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		ThreadDumpSummary threadDump = new ThreadDumpSummary(100, Map.of("BLOCKED", 7L, "RUNNABLE", 93L), List.of());
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot, null, threadDump, List.of(), List.of(), null, null);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.withoutSource(List.of(), java.util.Map.of(), List.of()), "local",
					"diagnose");

		assertThat(report.findings()).extracting(TuningFinding::title)
			.contains(ThreadDumpInsightsRule.BLOCKED_MODERATE_TITLE);
		assertThat(report.findings()).extracting(TuningFinding::title)
			.doesNotContain(ThreadDumpInsightsRule.BLOCKED_SEVERE_TITLE);
	}

	@Test
	void shouldNotFlagSparseBlockedInVeryLargePool() {
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(1L,
				new JvmMemorySnapshot(1L, 1L, 1L, null, null, null, null, null),
				new JvmGcSnapshot("G1", 1L, 1L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		ThreadDumpSummary threadDump = new ThreadDumpSummary(200, Map.of("BLOCKED", 5L, "RUNNABLE", 195L), List.of());
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot, null, threadDump, List.of(), List.of(), null, null);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.withoutSource(List.of(), java.util.Map.of(), List.of()), "local",
					"diagnose");

		assertThat(report.findings()).extracting(TuningFinding::title)
			.doesNotContain(ThreadDumpInsightsRule.BLOCKED_SEVERE_TITLE, ThreadDumpInsightsRule.BLOCKED_MODERATE_TITLE);
	}

	@Test
	void shouldNotReportDeadlockWithoutSufficientHints() {
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(1L,
				new JvmMemorySnapshot(1L, 1L, 1L, null, null, null, null, null),
				new JvmGcSnapshot("G1", 1L, 1L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		ThreadDumpSummary threadDump = new ThreadDumpSummary(2, Map.of("RUNNABLE", 2L),
				List.of("Found one Java-level deadlock:"));
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot, null, threadDump, List.of(), List.of(), null, null);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.withoutSource(List.of(), java.util.Map.of(), List.of()), "local",
					"diagnose");

		assertThat(report.findings()).extracting(TuningFinding::title)
			.doesNotContain(ThreadDumpInsightsRule.DEADLOCK_FINDING_TITLE);
	}
}
