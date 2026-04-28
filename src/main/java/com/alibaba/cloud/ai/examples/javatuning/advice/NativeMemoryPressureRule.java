package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;

public final class NativeMemoryPressureRule implements DiagnosisRule {

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		NativeMemorySummary summary = evidence.nativeMemorySummary();
		if (summary == null || !summary.hasTotals() || summary.totalReservedBytes() <= 0L) {
			return;
		}
		double commitRatio = (double) summary.totalCommittedBytes() / (double) summary.totalReservedBytes();
		if (commitRatio < 0.80d) {
			return;
		}
		scratch.addFinding(new TuningFinding("High native memory commitment", "medium",
				"nmtTotalReservedMb=" + mb(summary.totalReservedBytes()) + ", nmtTotalCommittedMb="
						+ mb(summary.totalCommittedBytes()) + ", committedRatio=" + String.format("%.2f", commitRatio),
				"rule-based", "Native allocation pressure can trigger OOM-kill or unstable RSS growth"));
		scratch.addRecommendation(new TuningRecommendation("Review native allocation budget", "native-memory",
				"-XX:NativeMemoryTracking=summary", "Improve visibility and cap native overhead",
				"NMT adds slight overhead; disable in latency-critical production after diagnosis", "JDK attach permissions"));
	}

	private long mb(long bytes) {
		return Math.max(0L, bytes / (1024L * 1024L));
	}

}
