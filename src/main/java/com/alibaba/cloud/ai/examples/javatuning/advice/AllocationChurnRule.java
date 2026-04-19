package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

public final class AllocationChurnRule implements DiagnosisRule {

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		JvmGcSnapshot gc = evidence.snapshot().gc();
		JvmMemorySnapshot memory = evidence.snapshot().memory();
		if (gc == null || memory == null || memory.heapMaxBytes() <= 0L) {
			return;
		}
		double heapRatio = (double) memory.heapUsedBytes() / (double) memory.heapMaxBytes();
		if (gc.youngGcCount() < 800L || gc.youngGcTimeMs() < 2_000L) {
			return;
		}
		if (heapRatio >= 0.85d) {
			return;
		}
		scratch.addFinding(new TuningFinding("High young-GC churn", "medium",
				"YGC=" + gc.youngGcCount() + " YGCT_ms=" + gc.youngGcTimeMs() + " heapUtilization="
						+ String.format("%.2f", heapRatio),
				"rule-based", "Frequent young collections with moderate heap headroom suggest short-lived allocation pressure"));
		scratch.addRecommendation(new TuningRecommendation("Reduce per-request allocation hot spots", "application",
				"Batch I/O, reuse buffers, trim logging payloads, review serialization",
				"Lowers allocation rate and GC CPU",
				"May require profiling to locate hotspots", "Validate under representative load"));
	}
}
