package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;

public final class DirectBufferPressureRule implements DiagnosisRule {

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		NativeMemorySummary summary = evidence.nativeMemorySummary();
		if (summary == null && hasDirectBufferHeapSignal(evidence)) {
			addNextStepIfMissing(scratch,
					"Direct buffer pressure cannot be confirmed without NMT NIO category; rerun with -XX:NativeMemoryTracking=summary.");
			return;
		}
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

	private static boolean hasDirectBufferHeapSignal(MemoryGcEvidencePack evidence) {
		boolean histogramSignal = evidence.classHistogram() != null && evidence.classHistogram()
			.entries()
			.stream()
			.anyMatch(entry -> isDirectBufferClass(entry.className()));
		boolean shallowSignal = evidence.heapShallowSummary() != null && evidence.heapShallowSummary()
			.topByShallowBytes()
			.stream()
			.anyMatch(entry -> isDirectBufferClass(entry.className()));
		return histogramSignal || shallowSignal;
	}

	private static boolean isDirectBufferClass(String className) {
		if (className == null) {
			return false;
		}
		return className.contains("java.nio.ByteBuffer") || className.contains("java.nio.DirectByteBuffer");
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
