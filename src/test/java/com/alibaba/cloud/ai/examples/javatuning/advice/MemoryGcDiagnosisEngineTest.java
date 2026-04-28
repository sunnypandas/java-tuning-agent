package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.GcLogSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapDumpShallowSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapShallowClassEntry;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrAllocationSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrCountAndBytes;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrExecutionSampleSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrGcSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrStackAggregate;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrThreadBlockAggregate;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrThreadSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedRuntimeSample;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ResourceBudgetEvidence;
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

		assertThat(report.findings()).extracting(TuningFinding::title)
			.contains("Dominant class-histogram candidate")
			.doesNotContain("Suspected retained-object leak", "Likely retained-object leak");
		assertThat(report.confidence()).isEqualTo("medium");
		assertThat(report.confidenceReasons())
			.anyMatch(reason -> reason.contains("single-snapshot live-object distribution"));
	}

	@Test
	void shouldUpgradeHistogramCandidateWhenTrendCorroboratesIt() {
		ClassHistogramSummary histogram = new ClassHistogramParser().parse("""
				 1: 600 157286400 com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord
				""");
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(stableBaseEvidence().snapshot(), histogram, null,
				List.of(), List.of(), null, null)
			.withRepeatedSamplingResult(risingHeapSamples());

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.empty(), "local", "diagnose-memory");

		assertThat(report.findings()).extracting(TuningFinding::title).contains("Likely retained-object leak");
		assertThat(report.confidence()).isEqualTo("high");
		assertThat(report.confidenceReasons()).anyMatch(reason -> reason.contains("corroborated"));
	}

	@Test
	void shouldReportShallowHeapDominanceAsCandidateWithoutRetainedProof() {
		MemoryGcEvidencePack base = stableBaseEvidence();
		HeapDumpShallowSummary shallow = new HeapDumpShallowSummary(
				List.of(new HeapShallowClassEntry("com.example.CacheValue", 80_000_000L, 45.0d)), 160_000_000L,
				false, "### Heap dump shallow summary", "");
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(base.snapshot(), null, null, List.of(), List.of(),
				"/tmp/heap.hprof", shallow);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.empty(), "local", "diagnose-memory");

		assertThat(report.findings()).extracting(TuningFinding::title)
			.contains("Dominant shallow heap candidate")
			.doesNotContain("Dominant shallow type in heap dump", "Corroborated shallow heap dominance");
		assertThat(report.confidence()).isEqualTo("medium");
		assertThat(report.confidenceReasons()).anyMatch(reason -> reason.contains("shallow-by-class candidate"));
	}

	@Test
	void shouldUpgradeShallowHeapCandidateWhenJfrAllocationCorroboratesIt() {
		MemoryGcEvidencePack base = stableBaseEvidence();
		HeapDumpShallowSummary shallow = new HeapDumpShallowSummary(
				List.of(new HeapShallowClassEntry("com.example.CacheValue", 80_000_000L, 45.0d)), 160_000_000L,
				false, "### Heap dump shallow summary", "");
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(base.snapshot(), null, null, List.of(), List.of(),
				"/tmp/heap.hprof", shallow)
			.withJfrSummary(sampleJfrSummary("com.example.CacheValue"));

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.empty(), "local", "diagnose-memory");

		assertThat(report.findings()).extracting(TuningFinding::title)
			.contains("Corroborated shallow heap dominance");
		assertThat(report.confidence()).isEqualTo("high");
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
	void shouldIncludeTrendFindingsFromRepeatedSamples() {
		MemoryGcEvidencePack evidence = stableBaseEvidence().withRepeatedSamplingResult(risingHeapSamples());

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.empty(), "local", "diagnose memory growth");

		assertThat(report.findings()).extracting(TuningFinding::title)
			.contains(RepeatedSamplingTrendRule.RISING_HEAP_TITLE);
		assertThat(report.confidenceReasons())
			.anyMatch(reason -> reason.contains("Repeated runtime samples present"));
	}

	@Test
	void shouldIncludeGcLogFindingsFromImportedSummary() {
		GcLogSummary gcLog = new GcLogSummary(2, 1, 1, 0, 850.0d, 870.0d, 500L * 1024L * 1024L,
				260L * 1024L * 1024L, 0, 0, Map.of("G1 Compaction Pause", 1L), List.of());
		MemoryGcEvidencePack evidence = stableBaseEvidence().withGcLogSummary(gcLog);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.empty(), "prod", "reduce pause");

		assertThat(report.findings()).extracting(TuningFinding::title)
			.contains(GcLogInsightsRule.LONG_PAUSE_TITLE);
		assertThat(report.confidenceReasons()).anyMatch(reason -> reason.contains("GC log summary present"));
	}

	@Test
	void shouldIncludeNativeMemoryFindingsWhenNmtShowsPressure() {
		NativeMemorySummary nativeSummary = new NativeMemorySummary(2_048L * 1024L * 1024L, 1_900L * 1024L * 1024L,
				600L * 1024L * 1024L, 520L * 1024L * 1024L, 700L * 1024L * 1024L, 600L * 1024L * 1024L, List.of());
		MemoryGcEvidencePack evidence = stableBaseEvidence().withNativeMemorySummary(nativeSummary);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.empty(), "prod", "reduce native pressure");

		assertThat(report.findings()).extracting(TuningFinding::title).contains("High native memory commitment",
				"Direct buffer native pressure", "Metaspace or class metadata pressure");
	}

	@Test
	void shouldIncludeResourceBudgetFindingWhenRssApproachesContainerLimit() {
		ResourceBudgetEvidence resourceBudget = new ResourceBudgetEvidence(1_024L * 1024L * 1024L,
				970L * 1024L * 1024L, 2.0d, 512L * 1024L * 1024L, 512L * 1024L * 1024L,
				380L * 1024L * 1024L, 128L * 1024L * 1024L, 1_020L * 1024L * 1024L, List.of(), List.of());
		MemoryGcEvidencePack evidence = stableBaseEvidence().withResourceBudgetEvidence(resourceBudget);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.empty(), "prod", "avoid container OOM");

		assertThat(report.findings()).extracting(TuningFinding::title)
			.contains(ResourceBudgetPressureRule.CONTAINER_MEMORY_PRESSURE_TITLE);
		assertThat(report.recommendations()).extracting(TuningRecommendation::category).contains("resource-budget");
	}

	@Test
	void shouldIncludeJfrFindingsWhenSummaryIsPresent() {
		MemoryGcEvidencePack evidence = stableBaseEvidence().withJfrSummary(sampleJfrSummary());

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.empty(), "prod", "reduce latency");

		assertThat(report.findings()).extracting(TuningFinding::title).contains(JfrInsightsRule.ALLOCATION_TITLE,
				JfrInsightsRule.CONTENTION_TITLE, JfrInsightsRule.EXECUTION_TITLE);
		assertThat(report.confidenceReasons()).anyMatch(reason -> reason.contains("JFR summary present"));
	}

	@Test
	void shouldNotRegressWhenJfrSummaryIsAbsent() {
		MemoryGcEvidencePack evidence = stableBaseEvidence();

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.empty(), "prod", "reduce latency");

		assertThat(report.findings()).extracting(TuningFinding::title)
			.doesNotContain(JfrInsightsRule.ALLOCATION_TITLE, JfrInsightsRule.CONTENTION_TITLE,
					JfrInsightsRule.EXECUTION_TITLE);
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

		assertThat(report.findings()).extracting(TuningFinding::title).contains("Dominant class-histogram candidate");
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

	private static MemoryGcEvidencePack stableBaseEvidence() {
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(500L,
				new JvmMemorySnapshot(128L * 1024L * 1024L, 512L * 1024L * 1024L, 512L * 1024L * 1024L, null, null,
						null, null, null),
				new JvmGcSnapshot("G1", 10L, 100L, 0L, 0L, 35.0d), List.of("-XX:+UseG1GC"), "", 20L, 1_000L,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		return new MemoryGcEvidencePack(snapshot, null, null, List.of(), List.of(), null, null);
	}

	private static RepeatedSamplingResult risingHeapSamples() {
		return new RepeatedSamplingResult(500L,
				List.of(repeatedSample(0L, 100L, 40.0d, 10L, 100L, 0L, 0L, 20L, 1_000L),
						repeatedSample(10_000L, 170L, 55.0d, 12L, 120L, 0L, 0L, 22L, 1_003L),
						repeatedSample(20_000L, 260L, 72.0d, 14L, 140L, 0L, 0L, 23L, 1_005L)),
				List.of(), List.of(), 1_000_000L, 20_000L);
	}

	private static RepeatedRuntimeSample repeatedSample(long atOffsetMs, long heapUsedMb, double oldPct, long ygc,
			long ygctMs, long fgc, long fgctMs, Long threads, Long classes) {
		return new RepeatedRuntimeSample(1_000_000L + atOffsetMs,
				new JvmMemorySnapshot(heapUsedMb * 1024L * 1024L, 512L * 1024L * 1024L, 512L * 1024L * 1024L, null,
						null, null, null, null),
				new JvmGcSnapshot("G1", ygc, ygctMs, fgc, fgctMs, oldPct), threads, classes, List.of());
	}

	private static JfrSummary sampleJfrSummary() {
		return sampleJfrSummary("java.lang.String");
	}

	private static JfrSummary sampleJfrSummary(String allocationClassName) {
		return new JfrSummary(1L, 3_001L, 3_000L, new JfrGcSummary(2L, 25.0d, 20.0d, List.of(), List.of()),
				new JfrAllocationSummary(42L * 1024L * 1024L,
						List.of(new JfrCountAndBytes(allocationClassName, 5_000L, 22L * 1024L * 1024L)),
						List.of(new JfrStackAggregate("com.example.Allocator.allocate", 400L, 20L * 1024L * 1024L,
								List.of("com.example.Allocator.allocate", "com.example.Api.handle"))),
						8_000L),
				new JfrThreadSummary(80L, 120L, 160.0d,
						List.of(new JfrThreadBlockAggregate("http-nio-8080-exec-12", 240L, 4_500.0d, 160.0d,
								List.of("java.util.concurrent.locks.ReentrantLock.lock",
										"com.example.Cache.get")))),
				new JfrExecutionSampleSummary(2_000L, List.of(new JfrStackAggregate("com.example.Service.execute", 450L, 0L,
						List.of("com.example.Service.execute", "com.example.Controller.route")))),
				Map.of("jdk.ExecutionSample", 2_000L), List.of());
	}

	@Test
	void shouldReportNativeDirectAndMetaspacePressureFromNativeEvidence() {
		NativeMemorySummary nativeSummary = new NativeMemorySummary(2L * 1024L * 1024L * 1024L,
				1800L * 1024L * 1024L, 400L * 1024L * 1024L, 360L * 1024L * 1024L, 700L * 1024L * 1024L,
				600L * 1024L * 1024L, List.of());
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(42L,
				new JvmMemorySnapshot(512L * 1024L * 1024L, 1024L * 1024L * 1024L, 1024L * 1024L * 1024L, null, null,
						600L * 1024L * 1024L, null, null),
				new JvmGcSnapshot("G1", 10L, 10L, 0L, 0L, null), List.of("-XX:+UseG1GC"), "17.0.10", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot, null, null, List.of(), List.of(), null, null)
			.withNativeMemorySummary(nativeSummary);

		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion()
			.diagnose(evidence, CodeContextSummary.empty(), "prod", "stabilize memory");

		assertThat(report.findings()).extracting(TuningFinding::title).contains("High native memory commitment",
				"Direct buffer native pressure", "Metaspace or class metadata pressure");
	}

}
