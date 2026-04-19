package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

public final class HighHeapPressureRule implements DiagnosisRule {

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		JvmMemorySnapshot memory = evidence.snapshot().memory();
		if (memory == null || memory.heapMaxBytes() <= 0L) {
			return;
		}
		double ratio = memory.heapUsedBytes() > 0L ? (double) memory.heapUsedBytes() / (double) memory.heapMaxBytes()
				: 0.0d;
		Double oldPct = evidence.snapshot().gc().oldUsagePercent();
		boolean byHeapUsed = ratio >= 0.85d;
		boolean byOldGen = oldPct != null && oldPct >= 85.0d;
		if (!byHeapUsed && !byOldGen) {
			return;
		}
		long usedMb = memory.heapUsedBytes() / (1024L * 1024L);
		long maxMb = memory.heapMaxBytes() / (1024L * 1024L);
		String evidenceLine;
		if (byHeapUsed) {
			evidenceLine = "heapUsedMb=" + usedMb + ", heapMaxMb=" + maxMb + ", utilization="
					+ String.format("%.2f", ratio);
		}
		else if (memory.heapUsedBytes() > 0L && oldPct != null) {
			// G1 can show high old-gen occupancy while overall heap used / Xmx stays below the heap-ratio threshold.
			evidenceLine = "heapUsedMb=" + usedMb + ", heapMaxMb=" + maxMb + ", utilization="
					+ String.format("%.2f", ratio) + "; oldGenUsagePercent=" + String.format("%.2f", oldPct)
					+ " (pressure driven by G1 old generation)";
		}
		else {
			evidenceLine = "heapUsedBytes unavailable or zero; oldGenUsagePercent=" + String.format("%.2f", oldPct)
					+ ", heapMaxMb=" + maxMb;
		}
		scratch.addFinding(new TuningFinding("High heap pressure", "high", evidenceLine, "rule-based",
				"Sustained high heap utilization increases GC overhead and pause risk"));
		scratch.addRecommendation(new TuningRecommendation("Review heap sizing and GC pause targets", "jvm-gc",
				"-Xms<size> -Xmx<size> -XX:MaxGCPauseMillis=<ms>", "Stabilize footprint and reduce GC frequency",
				"Mis-sized heaps can hide leaks; validate with load tests", "Requires memory budget sign-off"));
	}
}
