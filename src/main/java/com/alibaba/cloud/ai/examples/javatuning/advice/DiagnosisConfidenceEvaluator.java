package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

public final class DiagnosisConfidenceEvaluator {

	public record ConfidenceResult(String confidence, List<String> confidenceReasons) {
	}

	public ConfidenceResult evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		List<String> reasons = new ArrayList<>();
		reasons.add("Evidence includes structured jcmd/jstat-derived runtime fields");
		if (evidence.classHistogram() != null && !evidence.classHistogram().entries().isEmpty()) {
			reasons.add("Class histogram present: stronger signal for retention-style issues");
		}
		else {
			reasons.add("No class histogram: retention conclusions rely on runtime counters only");
		}
		if (evidence.threadDump() != null && evidence.threadDump().threadCount() > 0) {
			reasons.add("Thread dump present: deadlock and BLOCKED-thread heuristics were applied");
		}
		else {
			reasons.add("No parsed thread dump: lock/deadlock signals not evaluated");
		}
		if (!context.sourceRoots().isEmpty()) {
			reasons.add("Local source roots were provided for optional code correlation");
		}
		else {
			reasons.add("No local source roots: code hotspots rely on class names only");
		}
		if (!evidence.missingData().isEmpty()) {
			reasons.add("Collection reported missing fragments: " + String.join(", ", evidence.missingData()));
		}
		if (evidence.heapDumpPath() != null && !evidence.heapDumpPath().isBlank()) {
			reasons.add("Heap dump file captured (GC.heap_dump); use MAT/VisualVM dominator tree on the returned path");
		}

		boolean leak = scratch.findings()
			.stream()
			.anyMatch(f -> "Suspected retained-object leak".equals(f.title()));
		boolean heap = scratch.findings().stream().anyMatch(f -> "High heap pressure".equals(f.title()));
		boolean deadlock = scratch.findings()
			.stream()
			.anyMatch(f -> ThreadDumpInsightsRule.DEADLOCK_FINDING_TITLE.equals(f.title()));

		String confidence;
		if (deadlock) {
			confidence = "high";
			reasons.add("Java-level deadlock is explicit in the thread dump");
		}
		else if (leak && evidence.classHistogram() != null && !evidence.classHistogram().entries().isEmpty()) {
			confidence = "high";
			reasons.add("Dominant histogram types support a retention hypothesis");
		}
		else if (heap) {
			boolean histo = evidence.classHistogram() != null && !evidence.classHistogram().entries().isEmpty();
			confidence = histo ? "high" : "medium";
			reasons.add("Heap utilization rule fired with measurable utilization");
		}
		else if (scratch.findings().isEmpty() && !scratch.missingData().isEmpty()) {
			confidence = "low";
			reasons.add("Evidence gaps were flagged before strong conclusions are possible");
		}
		else if (scratch.findings().isEmpty()) {
			confidence = "low";
			reasons.add("No strong memory/GC findings matched the current evidence shape");
		}
		else {
			confidence = "medium";
			reasons.add("Heuristic rules produced findings without histogram-backed retention proof");
		}
		return new ConfidenceResult(confidence, List.copyOf(reasons));
	}
}
