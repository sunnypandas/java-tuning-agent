package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.Comparator;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SuspectedHolderSummary;

public final class HeapRetentionInsightsRule implements DiagnosisRule {

	public static final String FINDING_TITLE = "Heap retention evidence points to a likely holder";

	private static final int MAX_WARNINGS_IN_EVIDENCE = 2;

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		HeapRetentionAnalysisResult retention = evidence.heapRetentionAnalysis();
		if (retention == null || !retention.analysisSucceeded()) {
			return;
		}

		HeapRetentionSummary summary = retention.retentionSummary();
		if (summary == null || !summary.analysisSucceeded() || summary.suspectedHolders().isEmpty()) {
			return;
		}

		SuspectedHolderSummary holder = summary.suspectedHolders()
			.stream()
			.filter(it -> it.reachableSubgraphBytesApprox() > 0L || it.retainedBytesApprox() != null)
			.max(Comparator.comparingLong(HeapRetentionInsightsRule::holderSignalBytes)
				.thenComparing(SuspectedHolderSummary::holderType))
			.orElse(summary.suspectedHolders().get(0));

		boolean strongerRetainedStyleSignal = isStrongerRetainedStyleSignal(retention);
		String evidenceText = buildEvidenceText(retention, holder, strongerRetainedStyleSignal);
		String impact = strongerRetainedStyleSignal
				? "A holder-oriented retained-style approximation suggests long-lived reachability and is worth reviewing against allocation history or cache lifecycle."
				: "A retention hint suggests long-lived reachability, but the result is approximate and should be validated before changing code.";

		scratch.addFinding(new TuningFinding(FINDING_TITLE, "medium", evidenceText, "retention-hint", impact));
		scratch.addRecommendation(new TuningRecommendation(
				"Inspect the reported holder and reference path for the retained graph", "memory-retention",
				"Trace the holder type and field path back to the owning cache, singleton, or static field with a retained-style approximation mindset.",
				"Helps confirm whether the reported holder is intentionally long-lived or unintentionally retaining data",
				"Approximate retention hints can overstate or understate the true retained size",
				"Best when a heap dump or retention analysis is already available"));

		String nextStep = "Review " + holder.holderType() + " -> " + safe(holder.exampleFieldPath())
				+ " and compare against allocation history or cache lifecycle; treat this as retained-style approximation evidence.";
		scratch.addNextStep(nextStep);
		if (!strongerRetainedStyleSignal) {
			scratch.addNextStep("Treat the warning text as a fallback limit: Shark-style results show reachability hints, not MAT-exact retained size.");
		}
	}

	private static boolean isStrongerRetainedStyleSignal(HeapRetentionAnalysisResult retention) {
		return "dominator-style".equalsIgnoreCase(retention.engine()) && retention.warnings().isEmpty();
	}

	private static long holderSignalBytes(SuspectedHolderSummary holder) {
		return holder.retainedBytesApprox() != null ? holder.retainedBytesApprox()
				: holder.reachableSubgraphBytesApprox();
	}

	private static String buildEvidenceText(HeapRetentionAnalysisResult retention, SuspectedHolderSummary holder,
			boolean strongerRetainedStyleSignal) {
		StringBuilder sb = new StringBuilder();
		sb.append("engine=").append(safe(retention.engine()));
		if (!retention.warnings().isEmpty()) {
			sb.append(" warnings=").append(limitWarnings(retention.warnings()));
		}
		sb.append(" holderType=").append(safe(holder.holderType()));
		sb.append(" holderRole=").append(safe(holder.holderRole()));
		sb.append(" exampleFieldPath=").append(safe(holder.exampleFieldPath()));
		sb.append(" exampleTargetType=").append(safe(holder.exampleTargetType()));
		sb.append(" reachableSubgraphBytesApprox=").append(holder.reachableSubgraphBytesApprox());
		if (holder.retainedBytesApprox() != null) {
			sb.append(" retainedBytesApprox=").append(holder.retainedBytesApprox());
		}
		else {
			sb.append(" retainedBytesApprox=unavailable");
		}
		sb.append(" note=").append(strongerRetainedStyleSignal
				? "holder-oriented retained-style approximation evidence from a completed dominator-style pass"
				: "retention hint from an approximate or fallback analysis");
		return sb.toString();
	}

	private static String limitWarnings(List<String> warnings) {
		if (warnings.size() <= MAX_WARNINGS_IN_EVIDENCE) {
			return String.join(" | ", warnings);
		}
		List<String> sample = warnings.subList(0, MAX_WARNINGS_IN_EVIDENCE);
		return String.join(" | ", sample) + " (+" + (warnings.size() - MAX_WARNINGS_IN_EVIDENCE) + " more warnings)";
	}

	private static String safe(String value) {
		return value == null ? "" : value;
	}
}
