package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryGcEvidenceMergerTest {

	@Test
	void mergePreservesCoreEvidenceAndAddsRepeatedSamplingOnlyWhenAbsent() {
		JvmRuntimeSnapshot snapshot = stubSnapshot();
		ClassHistogramSummary histogram = histogram();
		ThreadDumpSummary threadDump = threadDump();
		RepeatedSamplingResult repeated = new RepeatedSamplingResult(snapshot.pid(), List.of(),
				List.of("r-warn"), List.of("r-miss"), 4000L, 500L);
		var base = new MemoryGcEvidencePack(snapshot, histogram, threadDump, List.of("m1"), List.of("w1"),
				"/tmp/dump.hprof", null);
		assertThat(MemoryGcEvidenceMerger.merge(base, repeated, null, null, null).snapshot()).isSameAs(snapshot);
		assertThat(MemoryGcEvidenceMerger.merge(base, repeated, null, null, null).classHistogram())
			.isSameAs(histogram);
		assertThat(MemoryGcEvidenceMerger.merge(base, repeated, null, null, null).threadDump()).isSameAs(threadDump);
		assertThat(MemoryGcEvidenceMerger.merge(base, repeated, null, null, null).heapDumpPath())
			.isEqualTo("/tmp/dump.hprof");
		assertThat(MemoryGcEvidenceMerger.merge(base, repeated, null, null, null).repeatedSamplingResult())
			.isSameAs(repeated);
	}

	@Test
	void mergeAddsJfrSummaryWhenAbsent() {
		var base = tinyPack(List.of(), List.of(), null, null);
		JfrSummary jfr = emptyJfr(5000L, 200L);
		MemoryGcEvidencePack merged = MemoryGcEvidenceMerger.merge(base, null, jfr, null, null);
		assertThat(merged.jfrSummary()).isSameAs(jfr);
	}

	@Test
	void mergeAddsResourceBudgetWhenAbsent() {
		var base = tinyPack(List.of(), List.of(), null, null);
		ResourceBudgetEvidence budget = new ResourceBudgetEvidence(1L, 2L, 0.5d, null, null, null, null, null,
				List.of("rb-w"), List.of("rb-m"));
		MemoryGcEvidencePack merged = MemoryGcEvidenceMerger.merge(base, null, null, budget, null);
		assertThat(merged.resourceBudgetEvidence()).isSameAs(budget);
		assertThat(merged.warnings()).contains("rb-w");
		assertThat(merged.missingData()).contains("rb-m");
	}

	@Test
	void mergeDedupesWarningsAndMissingDataPreserveOrder() {
		var base = tinyPack(List.of("x", "dup"), List.of("a", "dup"), null, null);
		ResourceBudgetEvidence budget = new ResourceBudgetEvidence(null, null, null, null, null, null, null, null,
				List.of("dup", "tail"), List.of("dup"));
		RepeatedSamplingResult repeated = new RepeatedSamplingResult(77L, List.of(),
				List.of("dup", "rz"), List.of("dup"), 1L, 0L);
		MemoryGcEvidencePack merged = MemoryGcEvidenceMerger.merge(base, repeated, emptyJfr(1L, 1L), budget, null);
		assertThat(merged.missingData()).containsExactly("x", "dup");
		assertThat(merged.warnings()).containsExactly("a", "dup", "rz", "tail");
	}

	@Test
	void mergesDiagnosisWindowsToEarliestStartAndLatestEnd() {
		JvmRuntimeSnapshot snapshot = stubSnapshotMeta(3000L, 100L);
		ThreadDumpSummary threadDump = threadDump();
		ClassHistogramSummary histogram = histogram();
		var base = new MemoryGcEvidencePack(snapshot, histogram, threadDump, List.of(), List.of(),
				null, null, null, null, null, null, null, null, null, null);
		RepeatedSamplingResult repeated = new RepeatedSamplingResult(snapshot.pid(), List.of(),
				List.of(), List.of(), 4000L, 500L);
		JfrSummary jfr = emptyJfr(5000L, 200L);
		DiagnosisWindow window = MemoryGcEvidenceMerger.merge(base, repeated, jfr, null, null).diagnosisWindow();
		assertThat(window).isNotNull();
		assertThat(window.startEpochMs()).isEqualTo(3000L);
		assertThat(window.endEpochMs()).isEqualTo(5200L);
	}

	@Test
	void doesNotOverwriteExistingRepeatedOrJfr() {
		RepeatedSamplingResult existing = new RepeatedSamplingResult(1L, List.of(), List.of(), List.of(), 9L, 1L);
		JfrSummary existingJfr = emptyJfr(10L, 10L);
		var base = tinyPack(List.of(), List.of(), existing, existingJfr);
		RepeatedSamplingResult challenger = new RepeatedSamplingResult(1L, List.of(), List.of("x"), List.of(), 999L,
				999L);
		JfrSummary challengerJfr = emptyJfr(9999L, 1L);
		MemoryGcEvidencePack merged = MemoryGcEvidenceMerger.merge(base, challenger, challengerJfr, null, null);
		assertThat(merged.repeatedSamplingResult()).isSameAs(existing);
		assertThat(merged.jfrSummary()).isSameAs(existingJfr);
	}

	@Test
	void mergeAddsBaselineEvidenceWhenAbsent() {
		JvmRuntimeSnapshot snapB = stubSnapshotMeta(500L, 10L);
		var baselinePack = tinyPack(List.of(), List.of(), null, null, snapB);
		var primary = tinyPack(List.of(), List.of(), null, null, stubSnapshotMeta(900L, 10L));

		assertThat(MemoryGcEvidenceMerger.merge(primary, null, null, null, baselinePack).baselineEvidence())
			.isNotNull();

		JvmRuntimeSnapshot dupSnap = stubSnapshotMeta(42L, 1L);
		var dupBase = tinyPack(List.of(), List.of(), null, null, dupSnap).withBaselineEvidence(baselinePack);
		assertThat(MemoryGcEvidenceMerger.merge(dupBase, null, null, null, baselinePack).baselineEvidence())
			.isSameAs(baselinePack);
	}

	@Test
	void rejectsNullBase() {
		assertThatThrownBy(() -> MemoryGcEvidenceMerger.merge(null,
				new RepeatedSamplingResult(1L, List.of(), List.of(), List.of(), 0L, 0L),
				null, null, null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("base evidence");
	}

	private static MemoryGcEvidencePack tinyPack(List<String> missing, List<String> warnings,
			RepeatedSamplingResult repeated, JfrSummary jfr) {
		return tinyPack(missing, warnings, repeated, jfr, stubSnapshot());
	}

	private static MemoryGcEvidencePack tinyPack(List<String> missing, List<String> warnings,
			RepeatedSamplingResult repeated, JfrSummary jfr, JvmRuntimeSnapshot snapshot) {
		return new MemoryGcEvidencePack(snapshot, histogram(), threadDump(), missing, warnings, null, null, null,
				null, repeated, null, jfr, null, null, null);
	}

	private static JvmRuntimeSnapshot stubSnapshot() {
		return stubSnapshotMeta(4200L, 50L);
	}

	private static JvmRuntimeSnapshot stubSnapshotMeta(long collectedAtEpochMs, long elapsedMs) {
		return new JvmRuntimeSnapshot(77L,
				new JvmMemorySnapshot(0L, 0L, 0L, null, null, null, null, null),
				new JvmGcSnapshot("g1", 0L, 0L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), collectedAtEpochMs, elapsedMs, false), List.of());
	}

	private static ClassHistogramSummary histogram() {
		return new ClassHistogramSummary(
				List.of(new ClassHistogramEntry(1L, 1L, 10L, "java.lang.Object")), 1L, 10L);
	}

	private static ThreadDumpSummary threadDump() {
		return new ThreadDumpSummary(1, Map.of("RUNNABLE", 1L), List.of());
	}

	private static JfrSummary emptyJfr(Long startEpochMs, long durationMs) {
		return new JfrSummary(startEpochMs, null, durationMs,
				new JfrGcSummary(0, 0d, 0d, List.of(), List.of()),
				new JfrAllocationSummary(0, List.of(), List.of(), 0),
				new JfrThreadSummary(0L, 0L, 0d, List.of()),
				new JfrExecutionSampleSummary(0, List.of()), Map.of(), List.of());
	}

}
