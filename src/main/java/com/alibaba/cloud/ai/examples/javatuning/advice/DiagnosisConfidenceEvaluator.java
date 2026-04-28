package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;

public final class DiagnosisConfidenceEvaluator {

	public record ConfidenceResult(String confidence, List<String> confidenceReasons) {
	}

	public ConfidenceResult evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		List<String> reasons = new ArrayList<>();
		reasons.add("Evidence includes structured jcmd/jstat-derived runtime fields");
		if (evidence.classHistogram() != null && !evidence.classHistogram().entries().isEmpty()) {
			reasons.add(
					"Class histogram present: single-snapshot live-object distribution; candidate signal unless corroborated by trend, JFR allocation, or retention evidence");
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
		if (evidence.repeatedSamplingResult() != null && !evidence.repeatedSamplingResult().samples().isEmpty()) {
			reasons.add("Repeated runtime samples present: trend-aware rules were applied");
		}
		if (evidence.repeatedSamplingResult() != null && evidence.repeatedSamplingResult().samples().size() < 2) {
			reasons.add("Repeated sampling had fewer than two successful samples; trend confidence is limited");
		}
		if (evidence.gcLogSummary() != null && evidence.gcLogSummary().hasPauseData()) {
			reasons.add("GC log summary present: pause-history rules were applied");
		}
		if (evidence.jfrSummary() != null) {
			reasons.add("JFR summary present: allocation/contention/execution sample rules were applied");
		}
		if (evidence.resourceBudgetEvidence() != null && evidence.resourceBudgetEvidence().hasAnyMemorySignal()) {
			reasons.add("Resource budget evidence present: cgroup/RSS/native/heap budget rules were applied");
		}
		if (!evidence.missingData().isEmpty()) {
			reasons.add("Collection reported missing fragments: " + String.join(", ", evidence.missingData()));
		}
		if (evidence.heapDumpPath() != null && !evidence.heapDumpPath().isBlank()) {
			reasons.add("Heap dump file captured (GC.heap_dump); use MAT/VisualVM dominator tree on the returned path");
		}
		if (evidence.heapShallowSummary() != null && evidence.heapShallowSummary().analysisSucceeded()
				&& evidence.heapShallowSummary().totalTrackedShallowBytes() > 0L) {
			reasons.add(
					"Heap dump was indexed (Shark); shallow-by-class candidate leaders were used in heuristics and below in the report; shallow bytes are not retained-size proof");
		}
		else if (evidence.heapShallowSummary() != null && !evidence.heapShallowSummary().errorMessage().isEmpty()) {
			reasons.add("Heap dump path was present but shallow summarization failed: " + evidence.heapShallowSummary()
					.errorMessage());
		}

		HeapRetentionAnalysisResult retention = evidence.heapRetentionAnalysis();
		boolean retentionSucceeded = retention != null && retention.analysisSucceeded();
		boolean strongerRetainedStyleSignal = retentionSucceeded && "dominator-style".equalsIgnoreCase(retention.engine())
				&& retention.warnings().isEmpty();
		if (retentionSucceeded) {
			if (strongerRetainedStyleSignal) {
				reasons.add(
						"Heap retention analysis succeeded with holder-oriented retained-style approximation evidence and holder hints");
			}
			else {
				reasons.add("Heap retention analysis succeeded with holder-oriented retained-style approximation evidence from "
						+ retention.engine());
			}
			if (!retention.warnings().isEmpty()) {
				reasons.add("Retention analysis caveats: " + retentionWarnings(retention.warnings()));
			}
		}

		boolean likelyLeak = containsFinding(scratch, "Likely retained-object leak");
		boolean histogramCandidate = containsFinding(scratch, "Dominant class-histogram candidate");
		boolean shallowCandidate = containsFinding(scratch, "Dominant shallow heap candidate");
		boolean corroboratedShallow = containsFinding(scratch, "Corroborated shallow heap dominance");
		boolean retentionFinding = containsFinding(scratch, HeapRetentionInsightsRule.FINDING_TITLE);
		boolean heap = containsFinding(scratch, "High heap pressure");
		boolean deadlock = containsFinding(scratch, ThreadDumpInsightsRule.DEADLOCK_FINDING_TITLE);

		String confidence;
		if (deadlock) {
			confidence = "high";
			reasons.add("Java-level deadlock is explicit in the thread dump");
		}
		else if (likelyLeak || corroboratedShallow) {
			confidence = "high";
			reasons.add(
					"Dominant object signal is corroborated by repeated trend, JFR allocation, or retention/deep evidence");
		}
		else if (retentionFinding) {
			confidence = retentionSucceeded ? "medium" : "low";
			reasons.add("Retention evidence exists as a holder-oriented retained-style approximation, not MAT-exact retained size");
		}
		else if (histogramCandidate || shallowCandidate) {
			confidence = "medium";
			reasons.add("Single-snapshot histogram or shallow-by-class candidate needs corroboration before leak/high confidence wording");
		}
		else if (heap) {
			confidence = "medium";
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

	private static boolean containsFinding(DiagnosisScratch scratch, String title) {
		return scratch.findings().stream().anyMatch(f -> title.equals(f.title()));
	}

	private static String retentionWarnings(List<String> warnings) {
		if (warnings.size() <= 2) {
			return String.join(" | ", warnings);
		}
		return String.join(" | ", warnings.subList(0, 2)) + " (+" + (warnings.size() - 2) + " more warnings)";
	}
}
