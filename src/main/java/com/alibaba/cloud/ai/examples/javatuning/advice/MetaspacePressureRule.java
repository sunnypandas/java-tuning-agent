package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;

public final class MetaspacePressureRule implements DiagnosisRule {

	private static final long LARGE_BYTES = 512L * 1024L * 1024L;

	private static final long SIGNIFICANT_CLASS_GROWTH_BYTES = 32L * 1024L * 1024L;

	private static final double HIGH_UTILIZATION_PERCENT = 90.0d;

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		Long metaspaceUsedValue = evidence.snapshot().memory() != null ? evidence.snapshot().memory().metaspaceUsedBytes()
				: null;
		long metaspaceUsed = metaspaceUsedValue == null ? 0L : metaspaceUsedValue;
		Long metaspaceCommittedValue = evidence.snapshot().memory() != null
				? evidence.snapshot().memory().metaspaceCommittedBytes() : null;
		Long metaspaceReservedValue = evidence.snapshot().memory() != null
				? evidence.snapshot().memory().metaspaceReservedBytes() : null;
		long metaspaceCommitted = metaspaceCommittedValue == null ? 0L : metaspaceCommittedValue;
		long metaspaceReserved = metaspaceReservedValue == null ? 0L : metaspaceReservedValue;
		NativeMemorySummary summary = evidence.nativeMemorySummary();
		Long classCommitted = summary != null ? summary.classCommittedBytes() : null;
		Long classCommittedGrowth = classCommittedGrowth(summary);
		Double metaspaceUtilPercent = evidence.snapshot().gc() != null ? evidence.snapshot().gc().metaspaceUtilPercent()
				: null;
		Double compressedClassSpaceUtilPercent = evidence.snapshot().gc() != null
				? evidence.snapshot().gc().compressedClassSpaceUtilPercent() : null;
		boolean largeMetaspace = metaspaceUsed >= LARGE_BYTES || metaspaceCommitted >= LARGE_BYTES;
		boolean classPressure = classCommitted != null && classCommitted >= LARGE_BYTES;
		boolean classGrowthPressure = classCommittedGrowth != null
				&& classCommittedGrowth >= SIGNIFICANT_CLASS_GROWTH_BYTES;
		boolean metaspaceUtilPressure = metaspaceUtilPercent != null && metaspaceUtilPercent >= HIGH_UTILIZATION_PERCENT;
		boolean classSpaceUtilPressure = compressedClassSpaceUtilPercent != null
				&& compressedClassSpaceUtilPercent >= HIGH_UTILIZATION_PERCENT;
		boolean classloaderSignal = hasClassloaderHeapSignal(evidence);
		boolean classCountGrowth = hasClassCountGrowth(evidence);
		if (summary == null && (largeMetaspace || classloaderSignal || classCountGrowth)) {
			addNextStepIfMissing(scratch, "Collect NMT Class category with -XX:NativeMemoryTracking=summary and jcmd "
					+ evidence.snapshot().pid()
					+ " VM.native_memory summary to distinguish class metadata pressure from heap retention.");
		}
		if (!largeMetaspace && !classPressure && !classGrowthPressure && !metaspaceUtilPressure
				&& !classSpaceUtilPressure) {
			return;
		}
		scratch.addFinding(new TuningFinding("Metaspace or class metadata pressure", "medium",
				evidenceText(metaspaceUsed, metaspaceCommitted, metaspaceReserved, classCommitted, classCommittedGrowth,
						metaspaceUtilPercent, compressedClassSpaceUtilPercent),
				"rule-based", "High class metadata usage can increase full GC likelihood and startup churn"));
		scratch.addRecommendation(new TuningRecommendation("Inspect classloader lifecycle and metaspace sizing", "jvm-gc",
				"-XX:MaxMetaspaceSize=<size>", "Avoid class metadata growth surprises and reduce classloader leaks",
				"Hard caps can fail fast if real class footprint exceeds budget", "Need classloader ownership mapping"));
	}

	private static boolean hasClassloaderHeapSignal(MemoryGcEvidencePack evidence) {
		return evidence.classHistogram() != null && evidence.classHistogram()
			.entries()
			.stream()
			.anyMatch(entry -> entry.className() != null
					&& (entry.className().contains("ClassloaderPressureStore$DemoProxyClassLoader")
							|| entry.className().contains("ClassLoader")));
	}

	private static boolean hasClassCountGrowth(MemoryGcEvidencePack evidence) {
		if (evidence.repeatedSamplingResult() == null || evidence.repeatedSamplingResult().samples().size() < 2) {
			return false;
		}
		var samples = evidence.repeatedSamplingResult().samples();
		Long first = samples.get(0).loadedClassCount();
		Long last = samples.get(samples.size() - 1).loadedClassCount();
		return first != null && last != null && last - first >= 500L;
	}

	private static Long classCommittedGrowth(NativeMemorySummary summary) {
		if (summary == null || summary.categoryGrowth().isEmpty()) {
			return null;
		}
		NativeMemorySummary.CategoryGrowth growth = summary.categoryGrowth().get("class");
		return growth != null ? growth.committedDeltaBytes() : null;
	}

	private static void addNextStepIfMissing(DiagnosisScratch scratch, String step) {
		if (!scratch.nextSteps().contains(step)) {
			scratch.addNextStep(step);
		}
	}

	private String evidenceText(long metaspaceUsed, long metaspaceCommitted, long metaspaceReserved,
			Long classCommitted, Long classCommittedGrowth, Double metaspaceUtilPercent,
			Double compressedClassSpaceUtilPercent) {
		return "metaspaceUsedMb=" + mb(metaspaceUsed) + ", metaspaceCommittedMb=" + mb(metaspaceCommitted)
				+ ", metaspaceReservedMb=" + mb(metaspaceReserved) + ", classCommittedMb="
				+ mb(classCommitted == null ? 0L : classCommitted) + ", classCommittedGrowthMb="
				+ mb(classCommittedGrowth == null ? 0L : classCommittedGrowth) + ", metaspaceUtilPercent="
				+ percentText(metaspaceUtilPercent) + ", compressedClassSpaceUtilPercent="
				+ percentText(compressedClassSpaceUtilPercent);
	}

	private String percentText(Double value) {
		return value == null ? "unknown" : Double.toString(value);
	}

	private long mb(long bytes) {
		return Math.max(0L, bytes / (1024L * 1024L));
	}

}
