package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedRuntimeSample;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatedSamplingTrendRuleTest {

	@Test
	void shouldReportRisingHeapPressureAcrossRepeatedSamples() {
		MemoryGcEvidencePack evidence = packWithSamples(List.of(sample(0L, 100L, 40.0d, 10L, 100L, 0L, 0L, 20L, 1_000L),
				sample(10_000L, 170L, 55.0d, 12L, 120L, 0L, 0L, 22L, 1_003L),
				sample(20_000L, 260L, 72.0d, 14L, 140L, 0L, 0L, 23L, 1_005L)));
		DiagnosisScratch scratch = new DiagnosisScratch();

		new RepeatedSamplingTrendRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.findings()).extracting(TuningFinding::title)
			.contains(RepeatedSamplingTrendRule.RISING_HEAP_TITLE);
		assertThat(scratch.findings().get(0).evidence()).contains("samples=3").contains("heapDeltaMb=160");
	}

	@Test
	void shouldStayQuietForStableSamplesAndAddNextStep() {
		MemoryGcEvidencePack evidence = packWithSamples(List.of(sample(0L, 100L, 40.0d, 10L, 100L, 0L, 0L, 20L, 1_000L),
				sample(10_000L, 101L, 40.5d, 10L, 100L, 0L, 0L, 20L, 1_000L),
				sample(20_000L, 99L, 40.0d, 11L, 101L, 0L, 0L, 20L, 1_001L)));
		DiagnosisScratch scratch = new DiagnosisScratch();

		new RepeatedSamplingTrendRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.findings()).isEmpty();
		assertThat(scratch.nextSteps()).anyMatch(step -> step.contains("No strong repeated-sampling trend"));
	}

	private static MemoryGcEvidencePack packWithSamples(List<RepeatedRuntimeSample> samples) {
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(1L,
				new JvmMemorySnapshot(100L * 1024L * 1024L, 512L * 1024L * 1024L, 512L * 1024L * 1024L, null, null,
						null, null, null),
				new JvmGcSnapshot("G1", 10L, 100L, 0L, 0L, 40.0d), List.of("-XX:+UseG1GC"), "", 20L, 1_000L,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		return new MemoryGcEvidencePack(snapshot, null, null, List.of(), List.of(), null, null).withRepeatedSamplingResult(
				new RepeatedSamplingResult(snapshot.pid(), samples, List.of(), List.of(), 1_000_000L, 20_000L));
	}

	private static RepeatedRuntimeSample sample(long atOffsetMs, long heapUsedMb, double oldPct, long ygc, long ygctMs,
			long fgc, long fgctMs, Long threads, Long classes) {
		return new RepeatedRuntimeSample(1_000_000L + atOffsetMs,
				new JvmMemorySnapshot(heapUsedMb * 1024L * 1024L, 512L * 1024L * 1024L, 512L * 1024L * 1024L, null,
						null, null, null, null),
				new JvmGcSnapshot("G1", ygc, ygctMs, fgc, fgctMs, oldPct), threads, classes, List.of());
	}

}
