package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;

public final class DirectBufferPressureRule implements DiagnosisRule {

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		NativeMemorySummary summary = evidence.nativeMemorySummary();
		if (summary == null || summary.directCommittedBytes() == null || summary.totalCommittedBytes() <= 0L) {
			return;
		}
		double directRatio = (double) summary.directCommittedBytes() / (double) summary.totalCommittedBytes();
		if (summary.directCommittedBytes() < 256L * 1024L * 1024L && directRatio < 0.20d) {
			return;
		}
		scratch.addFinding(new TuningFinding("Direct buffer native pressure", "medium",
				"directCommittedMb=" + mb(summary.directCommittedBytes()) + ", totalNativeCommittedMb="
						+ mb(summary.totalCommittedBytes()) + ", directShare=" + String.format("%.2f", directRatio),
				"rule-based", "Direct buffers can consume native memory outside heap guardrails"));
		scratch.addRecommendation(new TuningRecommendation("Cap and observe direct buffer usage", "native-memory",
				"-XX:MaxDirectMemorySize=<size>", "Reduce off-heap spikes and stabilize RSS",
				"Too-low cap may increase IO copy overhead", "Validate with throughput and latency tests"));
	}

	private long mb(long bytes) {
		return Math.max(0L, bytes / (1024L * 1024L));
	}

}
