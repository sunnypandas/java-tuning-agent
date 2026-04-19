package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramEntry;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HistogramClassNames;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

public final class SuspectedLeakRule implements DiagnosisRule {

	private static final List<String> JVM_INTERNAL_PREFIXES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

	private static boolean isJvmInternal(String className) {
		if (className == null || className.isBlank()) {
			return true;
		}
		String n = HistogramClassNames.stripModuleSuffix(className);
		// Do not treat primitive arrays like [B as "internal": they often dominate legitimate app payloads.
		for (String prefix : JVM_INTERNAL_PREFIXES) {
			if (n.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		ClassHistogramSummary histogram = evidence.classHistogram();
		if (histogram == null || histogram.entries().isEmpty() || histogram.totalBytes() <= 0L) {
			return;
		}
		ClassHistogramEntry topApp = null;
		long topBytes = 0L;
		for (ClassHistogramEntry e : histogram.entries()) {
			if (isJvmInternal(e.className())) {
				continue;
			}
			String normalized = HistogramClassNames.stripModuleSuffix(e.className());
			if (HistogramClassNames.isAnyArrayDescriptor(normalized) && !HistogramClassNames.isPrimitiveArrayDescriptor(normalized)) {
				continue;
			}
			if (e.bytes() > topBytes) {
				topBytes = e.bytes();
				topApp = e;
			}
		}
		if (topApp == null) {
			return;
		}
		double share = (double) topApp.bytes() / (double) histogram.totalBytes();
		if (share < 0.30d) {
			return;
		}
		String dominant = HistogramClassNames.stripModuleSuffix(topApp.className());
		scratch.addFinding(new TuningFinding("Suspected retained-object leak", "high",
				"classHistogram dominantType=" + dominant + " retainedBytes=" + topApp.bytes() + " shareOfHistogram="
						+ String.format("%.2f", share),
				"inferred-from-evidence",
				"A small set of types retaining a large share of live heap often indicates accumulation or caches"));
		scratch.addRecommendation(new TuningRecommendation(
				"Investigate lifecycle and retention for dominant histogram types", "code-review",
				"Trace allocation paths; verify caches/singletons/static collections are bounded",
				"Reduces unexpected long-lived retention",
				"False positives possible for legitimate large graphs", "Pair with allocation profiling if unsure"));
		if (HistogramClassNames.isPrimitiveArrayDescriptor(dominant)) {
			scratch.addNextStep(
					"Heap dump + dominator tree: jcmd <pid> GC.heap_dump <file>.hprof then inspect retained byte[] (MAT, VisualVM, or JProfiler merge paths to GC roots)");
			scratch.addNextStep(
					"Optional allocation profiling: JFR ObjectAllocation* events or Async Profiler (-e alloc) to find hot byte[] allocation sites");
		}
		else {
			scratch.addNextStep("Compare two histograms over time or capture allocation stacks for " + dominant);
		}
	}
}