package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

/**
 * Surfaces missing evidence when runtime hints at memory pressure but data is thin.
 */
public final class EvidenceGapRule implements DiagnosisRule {

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		if (evidence.missingData().contains("nativeMemorySummary")) {
			long pid = evidence.snapshot() == null ? 0L : evidence.snapshot().pid();
			addNextStepIfMissing(scratch, "Restart with -XX:NativeMemoryTracking=summary");
			addNextStepIfMissing(scratch, "Collect jcmd " + (pid > 0L ? Long.toString(pid) : "<pid>")
					+ " VM.native_memory summary");
			addNextStepIfMissing(scratch,
					"Use collectMemoryGcEvidence or offline nativeMemorySummary to include NMT totals in advice");
		}
		JvmMemorySnapshot memory = evidence.snapshot().memory();
		if (memory == null || memory.heapMaxBytes() <= 0L) {
			return;
		}
		boolean noHistogram = evidence.classHistogram() == null || evidence.classHistogram().entries().isEmpty();
		if (!noHistogram) {
			return;
		}
		boolean elevatedHeap = memory.heapUsedBytes() > 0L
				&& (double) memory.heapUsedBytes() / (double) memory.heapMaxBytes() >= 0.70d;
		Double oldPct = evidence.snapshot().gc().oldUsagePercent();
		boolean elevatedOld = oldPct != null && oldPct >= 70.0d;
		if (!elevatedHeap && !elevatedOld) {
			return;
		}
		scratch.addMissingData("classHistogram");
		scratch.addNextStep(
				"Collect class histogram with explicit confirmation to distinguish churn vs retained growth");
		scratch.addNextStep(
				"Optional with confirmation: collect thread dump to check for deadlocks and BLOCKED threads");
	}

	private static void addNextStepIfMissing(DiagnosisScratch scratch, String step) {
		if (!scratch.nextSteps().contains(step)) {
			scratch.addNextStep(step);
		}
	}
}
