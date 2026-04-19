package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

public final class GcStrategyMismatchRule implements DiagnosisRule {

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		JvmMemorySnapshot memory = evidence.snapshot().memory();
		if (memory == null || memory.xmsBytes() == null || memory.xmxBytes() == null || memory.xmxBytes() <= 0L) {
			return;
		}
		long xms = memory.xmsBytes();
		long xmx = memory.xmxBytes();
		double spread = (double) Math.abs(xmx - xms) / (double) xmx;
		if (spread <= 0.15d) {
			return;
		}
		scratch.addFinding(new TuningFinding("Heap min/max spread may amplify resizing work", "medium",
				"Xms_bytes=" + xms + " Xmx_bytes=" + xmx + " relativeSpread=" + String.format("%.2f", spread),
				"rule-based", "Large Xms/Xmx gaps can cause additional heap resizing and less predictable footprint"));
		scratch.addRecommendation(new TuningRecommendation("Align minimum and maximum heap for steady-state services",
				"jvm-gc", "-Xms=<same-as-Xmx> -Xmx=<same-as-Xmx>", "Stabilizes heap geometry and GC behavior",
				"Startup footprint increases if Xms rises", "Confirm container memory limits first"));
	}
}
