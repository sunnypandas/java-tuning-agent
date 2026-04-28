package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.Locale;

import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrAllocationSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrExecutionSampleSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrStackAggregate;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrThreadBlockAggregate;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrThreadSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

public final class JfrInsightsRule implements DiagnosisRule {

	public static final String ALLOCATION_TITLE = "JFR shows allocation hotspots";

	public static final String CONTENTION_TITLE = "JFR shows thread contention pressure";

	public static final String EXECUTION_TITLE = "JFR shows hottest execution samples";

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		JfrSummary jfr = evidence.jfrSummary();
		if (jfr == null) {
			return;
		}
		evaluateAllocations(jfr.allocationSummary(), scratch);
		evaluateContention(jfr.threadSummary(), scratch);
		evaluateExecutionSamples(jfr.executionSampleSummary(), scratch);
	}

	private static void evaluateAllocations(JfrAllocationSummary summary, DiagnosisScratch scratch) {
		if (summary == null || summary.topAllocatedClasses().isEmpty()) {
			return;
		}
		var topClass = summary.topAllocatedClasses().get(0);
		String detail = "allocationEvents=" + summary.allocationEventCount() + " topClass=" + topClass.name() + " count="
				+ topClass.count() + " bytesApprox=" + toMiB(topClass.bytesApprox()) + " MiB";
		scratch.addFinding(new TuningFinding(ALLOCATION_TITLE, "medium", detail, "jfr",
				"High allocation churn increases GC frequency and can amplify pause pressure"));
		scratch.addRecommendation(new TuningRecommendation("Reduce allocation churn on top JFR allocation paths",
				"allocation", "Target pooled/reused objects on the hottest allocation path before tuning GC flags",
				"Lowers young-gen churn and GC frequency", "Can increase object lifecycle complexity",
				"Start with frame: " + topFrame(summary.topAllocationStacks())));
		scratch.addNextStep("Capture a focused JFR profile around " + topClass.name() + " allocation paths under peak load");
	}

	private static void evaluateContention(JfrThreadSummary summary, DiagnosisScratch scratch) {
		if (summary == null || summary.topBlockedThreads().isEmpty()) {
			return;
		}
		long contentionEvents = summary.monitorEnterEventCount() + summary.parkEventCount();
		if (contentionEvents <= 0L) {
			return;
		}
		JfrThreadBlockAggregate topBlocked = summary.topBlockedThreads().get(0);
		String severity = summary.maxBlockedMs() >= 100.0d ? "high" : "medium";
		String detail = "monitorEvents=" + summary.monitorEnterEventCount() + " parkEvents=" + summary.parkEventCount()
				+ " maxBlockedMs=" + formatMs(summary.maxBlockedMs()) + " topThread=" + topBlocked.threadName();
		scratch.addFinding(new TuningFinding(CONTENTION_TITLE, severity, detail, "jfr",
				"Monitor/park contention can directly cap throughput and inflate latency"));
		scratch.addRecommendation(new TuningRecommendation("Prioritize lock-contention reduction on hottest blocked threads",
				"threads", "Refactor critical sections or lock granularity before increasing thread counts",
				"Improves throughput and latency under contention", "Refactoring lock scopes may require behavior validation",
				"Start with thread: " + topBlocked.threadName()));
		scratch.addNextStep("Inspect synchronized/park-heavy call paths for " + topBlocked.threadName()
				+ " and compare blocked time before/after changes");
	}

	private static void evaluateExecutionSamples(JfrExecutionSampleSummary summary, DiagnosisScratch scratch) {
		if (summary == null || summary.sampleCount() <= 0L || summary.topMethods().isEmpty()) {
			return;
		}
		JfrStackAggregate hottest = summary.topMethods().get(0);
		String detail = "sampleCount=" + summary.sampleCount() + " hottestFrame=" + hottest.frame() + " frameSamples="
				+ hottest.count();
		scratch.addFinding(new TuningFinding(EXECUTION_TITLE, "medium", detail, "jfr",
				"Execution samples expose CPU hotspots that often co-occur with memory and GC pressure"));
		scratch.addRecommendation(new TuningRecommendation("Optimize hottest execution path before JVM flag changes", "cpu",
				"Use method-level profiling and micro-benchmarks for the top sampled frames", "Reduces end-to-end latency and CPU load",
				"Requires representative load tests for validation", "Start with frame: " + hottest.frame()));
		scratch.addNextStep("Correlate hottest sampled frames with endpoint latency and allocation spikes from the same window");
	}

	private static String topFrame(java.util.List<JfrStackAggregate> stacks) {
		if (stacks == null || stacks.isEmpty()) {
			return "unknown";
		}
		return stacks.get(0).frame();
	}

	private static long toMiB(long bytes) {
		return Math.round(bytes / (1024.0d * 1024.0d));
	}

	private static String formatMs(double value) {
		return String.format(Locale.ROOT, "%.1f", value);
	}

}
