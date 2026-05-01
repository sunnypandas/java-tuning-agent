package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;

public final class MetaspacePressureRule implements DiagnosisRule {

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		Long metaspaceUsedValue = evidence.snapshot().memory() != null ? evidence.snapshot().memory().metaspaceUsedBytes()
				: null;
		long metaspaceUsed = metaspaceUsedValue == null ? 0L : metaspaceUsedValue;
		NativeMemorySummary summary = evidence.nativeMemorySummary();
		Long classCommitted = summary != null ? summary.classCommittedBytes() : null;
		boolean largeMetaspace = metaspaceUsed >= 512L * 1024L * 1024L;
		boolean classPressure = classCommitted != null && classCommitted >= 512L * 1024L * 1024L;
		boolean classloaderSignal = hasClassloaderHeapSignal(evidence);
		boolean classCountGrowth = hasClassCountGrowth(evidence);
		if (summary == null && (largeMetaspace || classloaderSignal || classCountGrowth)) {
			addNextStepIfMissing(scratch,
					"Collect NMT Class category to distinguish class metadata pressure from heap retention.");
		}
		if (!largeMetaspace && !classPressure) {
			return;
		}
		scratch.addFinding(new TuningFinding("Metaspace or class metadata pressure", "medium",
				"metaspaceUsedMb=" + mb(metaspaceUsed) + ", classCommittedMb=" + mb(classCommitted == null ? 0L : classCommitted),
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

	private static void addNextStepIfMissing(DiagnosisScratch scratch, String step) {
		if (!scratch.nextSteps().contains(step)) {
			scratch.addNextStep(step);
		}
	}

	private long mb(long bytes) {
		return Math.max(0L, bytes / (1024L * 1024L));
	}

}
