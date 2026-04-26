package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.stream.Collectors;

import com.alibaba.cloud.ai.examples.javatuning.runtime.GcLogSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

public final class GcLogInsightsRule implements DiagnosisRule {

	public static final String LONG_PAUSE_TITLE = "Imported GC log shows long pauses";

	public static final String FULL_GC_TITLE = "Imported GC log contains Full GC pauses";

	public static final String EVACUATION_PRESSURE_TITLE = "Imported GC log shows evacuation pressure";

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		GcLogSummary summary = evidence.gcLogSummary();
		if (summary == null || !summary.hasPauseData()) {
			return;
		}
		String causes = summary.topCauses().isEmpty() ? "none"
				: summary.topCauses()
					.entrySet()
					.stream()
					.map(entry -> entry.getKey() + "=" + entry.getValue())
					.collect(Collectors.joining(", "));
		if (summary.maxPauseMs() >= 500.0d) {
			scratch.addFinding(new TuningFinding(LONG_PAUSE_TITLE, "high",
					"pauseEvents=" + summary.pauseEventCount() + " maxPauseMs="
							+ String.format("%.1f", summary.maxPauseMs()) + " totalPauseMs="
							+ String.format("%.1f", summary.totalPauseMs()) + " causes=" + causes,
					"gc-log", "Long stop-the-world pauses can dominate tail latency during the captured window"));
			scratch.addRecommendation(new TuningRecommendation("Correlate long pauses with allocation and retention evidence",
					"evidence", "Run short JFR allocation profiling or inspect heap dump around the same incident window",
					"Separates allocation spikes from retained growth", "Requires representative incident-time artifacts",
					"Use timestamps from the imported GC log"));
			scratch.addNextStep("Use JFR allocation profiling or a heap dump from the same window to explain long GC pauses");
		}
		if (summary.fullPauseCount() > 0) {
			scratch.addFinding(new TuningFinding(FULL_GC_TITLE, "high",
					"fullPauseCount=" + summary.fullPauseCount() + " maxHeapBeforeBytes="
							+ valueOrUnknown(summary.maxHeapBeforeBytes()) + " minHeapAfterBytes="
							+ valueOrUnknown(summary.minHeapAfterBytes()),
					"gc-log", "Full GC pauses usually indicate compaction pressure, allocation failure, or explicit GC"));
			scratch.addNextStep("Check whether Full GC aligns with allocation spikes, humongous allocations, or heap occupancy growth");
		}
		if (summary.humongousAllocationCount() > 0 || summary.toSpaceExhaustedCount() > 0) {
			scratch.addFinding(new TuningFinding(EVACUATION_PRESSURE_TITLE, "medium",
					"humongousAllocationCount=" + summary.humongousAllocationCount() + " toSpaceExhaustedCount="
							+ summary.toSpaceExhaustedCount(),
					"gc-log", "G1 evacuation pressure can lead to longer pauses or Full GC fallback"));
			scratch.addNextStep("Review large object allocation sites and G1 region sizing before changing GC flags");
		}
	}

	private static String valueOrUnknown(Long value) {
		return value == null ? "unknown" : Long.toString(value);
	}

}
