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

	private long mb(long bytes) {
		return Math.max(0L, bytes / (1024L * 1024L));
	}

}
