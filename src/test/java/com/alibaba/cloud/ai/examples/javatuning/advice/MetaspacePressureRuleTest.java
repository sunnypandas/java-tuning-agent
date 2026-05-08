package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramEntry;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedRuntimeSample;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetaspacePressureRuleTest {

	@Test
	void shouldAskForNmtClassCategoryWhenMetaspaceIsHighAndNativeSummaryIsMissing() {
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot(640L * 1024L * 1024L, 1_000L), null,
				null, List.of("nativeMemorySummary"), List.of(), null, null);
		DiagnosisScratch scratch = new DiagnosisScratch();

		new MetaspacePressureRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.nextSteps()).contains(
				"Collect NMT Class category with -XX:NativeMemoryTracking=summary and jcmd 1961 VM.native_memory summary to distinguish class metadata pressure from heap retention.");
	}

	@Test
	void shouldAskForNmtClassCategoryWhenProxyClassLoaderAppearsAndNativeSummaryIsMissing() {
		ClassHistogramSummary histogram = new ClassHistogramSummary(List.of(new ClassHistogramEntry(1, 12,
				24_576L,
				"com.alibaba.cloud.ai.compat.memoryleakdemo.metaspace.ClassloaderPressureStore$DemoProxyClassLoader")),
				12, 24_576L);
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot(64L * 1024L * 1024L, 1_000L), histogram,
				null, List.of("nativeMemorySummary"), List.of(), null, null);
		DiagnosisScratch scratch = new DiagnosisScratch();

		new MetaspacePressureRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.nextSteps()).contains(
				"Collect NMT Class category with -XX:NativeMemoryTracking=summary and jcmd 1961 VM.native_memory summary to distinguish class metadata pressure from heap retention.");
	}

	@Test
	void shouldAskForNmtClassCategoryWhenClassCountGrowsAndNativeSummaryIsMissing() {
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot(64L * 1024L * 1024L, 1_000L), null,
				null, List.of("nativeMemorySummary"), List.of(), null, null)
			.withRepeatedSamplingResult(new RepeatedSamplingResult(1961L,
					List.of(sample(0L, 1_000L), sample(60_000L, 1_700L)), List.of(), List.of(), 0L, 60_000L));
		DiagnosisScratch scratch = new DiagnosisScratch();

		new MetaspacePressureRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.nextSteps()).contains(
				"Collect NMT Class category with -XX:NativeMemoryTracking=summary and jcmd 1961 VM.native_memory summary to distinguish class metadata pressure from heap retention.");
	}

	@Test
	void shouldReportMetaspacePressureWhenNmtClassCommittedGrows() {
		NativeMemorySummary nativeSummary = new NativeMemorySummary(1024L * 1024L * 1024L,
				512L * 1024L * 1024L, null, null, 128L * 1024L * 1024L, 96L * 1024L * 1024L,
				Map.of("class", new NativeMemorySummary.CategoryUsage(128L * 1024L * 1024L, 96L * 1024L * 1024L)),
				Map.of("class", new NativeMemorySummary.CategoryGrowth(64L * 1024L * 1024L, 48L * 1024L * 1024L)),
				List.of());
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot(96L * 1024L * 1024L, 1_000L), null,
				null, List.of(), List.of(), null, null).withNativeMemorySummary(nativeSummary);
		DiagnosisScratch scratch = new DiagnosisScratch();

		new MetaspacePressureRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.findings()).extracting(TuningFinding::title)
			.contains("Metaspace or class metadata pressure");
		assertThat(scratch.findings().get(0).evidence()).contains("classCommittedGrowthMb=48");
	}

	@Test
	void shouldReportMetaspacePressureWhenJstatMetaspaceUtilizationIsHigh() {
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshotWithGcUtil(96L * 1024L * 1024L,
				120L * 1024L * 1024L, 256L * 1024L * 1024L, 1_000L, 96.25d, 91.50d), null, null,
				List.of("nativeMemorySummary"), List.of(), null, null);
		DiagnosisScratch scratch = new DiagnosisScratch();

		new MetaspacePressureRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.findings()).extracting(TuningFinding::title)
			.contains("Metaspace or class metadata pressure");
		assertThat(scratch.findings().get(0).evidence()).contains("metaspaceUtilPercent=96.25")
			.contains("compressedClassSpaceUtilPercent=91.5");
	}

	private static RepeatedRuntimeSample sample(long at, long classCount) {
		return new RepeatedRuntimeSample(at,
				new JvmMemorySnapshot(128L * 1024L * 1024L, 256L * 1024L * 1024L, 1024L * 1024L * 1024L, null,
						null, 64L * 1024L * 1024L, null, null),
				new JvmGcSnapshot("G1", 0L, 0L, 0L, 0L, 20.0d), null, classCount, List.of());
	}

	private static JvmRuntimeSnapshot snapshot(long metaspaceUsedBytes, long loadedClassCount) {
		return new JvmRuntimeSnapshot(1961L,
				new JvmMemorySnapshot(128L * 1024L * 1024L, 256L * 1024L * 1024L, 1024L * 1024L * 1024L, null,
						null, metaspaceUsedBytes, null, null),
				new JvmGcSnapshot("G1", 0L, 0L, 0L, 0L, 20.0d), List.of(), "", null, loadedClassCount,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
	}

	private static JvmRuntimeSnapshot snapshotWithGcUtil(long metaspaceUsedBytes, long metaspaceCommittedBytes,
			long metaspaceReservedBytes, long loadedClassCount, Double metaspaceUtilPercent,
			Double compressedClassSpaceUtilPercent) {
		return new JvmRuntimeSnapshot(1961L,
				new JvmMemorySnapshot(128L * 1024L * 1024L, 256L * 1024L * 1024L, 1024L * 1024L * 1024L, null,
						null, metaspaceUsedBytes, metaspaceCommittedBytes, metaspaceReservedBytes, null, null),
				new JvmGcSnapshot("G1", 0L, 0L, 0L, 0L, 20.0d, metaspaceUtilPercent,
						compressedClassSpaceUtilPercent),
				List.of(), "", null, loadedClassCount, new JvmCollectionMetadata(List.of(), 0L, 0L, false),
				List.of());
	}

}
