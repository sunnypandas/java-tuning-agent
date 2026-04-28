package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ResourceBudgetEvidence;

public final class ResourceBudgetPressureRule implements DiagnosisRule {

	public static final String CONTAINER_MEMORY_PRESSURE_TITLE = "Resource budget pressure near container limit";

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		ResourceBudgetEvidence budget = evidence.resourceBudgetEvidence();
		if (budget == null || budget.containerMemoryLimitBytes() == null || budget.containerMemoryLimitBytes() <= 0L) {
			return;
		}
		long limit = budget.containerMemoryLimitBytes();
		double rssRatio = ratio(budget.processRssBytes(), limit);
		double estimatedRatio = ratio(budget.estimatedTotalCommittedBytes(), limit);
		if (rssRatio < 0.90d && estimatedRatio < 0.90d) {
			return;
		}
		scratch.addFinding(new TuningFinding(CONTAINER_MEMORY_PRESSURE_TITLE, "high",
				"containerLimitMb=" + mb(limit) + ", rssMb=" + mbOrUnknown(budget.processRssBytes())
						+ ", estimatedTotalCommittedMb=" + mbOrUnknown(budget.estimatedTotalCommittedBytes())
						+ ", heapCommittedMb=" + mbOrUnknown(budget.heapCommittedBytes()) + ", nativeCommittedMb="
						+ mbOrUnknown(budget.nativeCommittedBytes()) + ", estimatedThreadStackMb="
						+ mbOrUnknown(budget.estimatedThreadStackBytes()) + ", cpuQuotaCores="
						+ (budget.cpuQuotaCores() == null ? "unknown" : String.format("%.2f", budget.cpuQuotaCores())),
				"budget-based", "The JVM footprint is close to the container memory budget and may be OOM-killed before Java heap OOM diagnostics fire"));
		scratch.addRecommendation(new TuningRecommendation("Rebalance heap, native, and thread-stack budgets",
				"resource-budget",
				"Keep Xmx plus native/direct/metaspace/thread-stack headroom below the cgroup memory limit",
				"Reduces container OOM risk and makes future incidents easier to attribute",
				"Lowering Xmx can increase GC pressure if live set is unchanged",
				"Use RSS/cgroup evidence from the same incident window"));
	}

	private static double ratio(Long numerator, long denominator) {
		if (numerator == null || numerator <= 0L || denominator <= 0L) {
			return 0.0d;
		}
		return (double) numerator / (double) denominator;
	}

	private static String mbOrUnknown(Long bytes) {
		return bytes == null || bytes <= 0L ? "unknown" : Long.toString(mb(bytes));
	}

	private static long mb(long bytes) {
		return bytes / (1024L * 1024L);
	}
}
