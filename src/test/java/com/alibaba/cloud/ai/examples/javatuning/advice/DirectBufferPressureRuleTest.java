package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramEntry;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapDumpShallowSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapShallowClassEntry;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DirectBufferPressureRuleTest {

	@Test
	void shouldAskForNmtNioCategoryWhenByteBufferHistogramAppearsWithoutNativeSummary() {
		ClassHistogramSummary histogram = new ClassHistogramSummary(
				List.of(new ClassHistogramEntry(1, 24, 4_096_000L, "java.nio.ByteBuffer[]")), 24, 4_096_000L);
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot(), histogram, null,
				List.of("nativeMemorySummary"), List.of(), null, null);
		DiagnosisScratch scratch = new DiagnosisScratch();

		new DirectBufferPressureRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.nextSteps()).contains(
				"Direct buffer pressure cannot be confirmed without NMT NIO category; rerun with -XX:NativeMemoryTracking=summary.");
	}

	@Test
	void shouldAskForNmtNioCategoryWhenByteBufferShallowSummaryAppearsWithoutNativeSummary() {
		HeapDumpShallowSummary shallow = new HeapDumpShallowSummary(
				List.of(new HeapShallowClassEntry("java.nio.ByteBuffer[]", 4_096_000L, 42.0d)), 8_192_000L,
				false, "", "");
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot(), null, null,
				List.of("nativeMemorySummary"), List.of(), null, shallow);
		DiagnosisScratch scratch = new DiagnosisScratch();

		new DirectBufferPressureRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.nextSteps()).contains(
				"Direct buffer pressure cannot be confirmed without NMT NIO category; rerun with -XX:NativeMemoryTracking=summary.");
	}

	private static JvmRuntimeSnapshot snapshot() {
		return new JvmRuntimeSnapshot(1961L,
				new JvmMemorySnapshot(256L * 1024L * 1024L, 512L * 1024L * 1024L, 1024L * 1024L * 1024L, null,
						null, null, null, null),
				new JvmGcSnapshot("G1", 0L, 0L, 0L, 0L, 20.0d), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
	}

}
