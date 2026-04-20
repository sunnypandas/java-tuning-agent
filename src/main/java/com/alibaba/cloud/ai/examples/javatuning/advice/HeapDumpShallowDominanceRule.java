package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapDumpShallowSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapShallowClassEntry;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HistogramClassNames;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

/**
 * Uses structured shallow totals from an indexed {@code .hprof} (when present) to flag a single dominant type.
 */
public final class HeapDumpShallowDominanceRule implements DiagnosisRule {

	private static final List<String> JVM_INTERNAL_PREFIXES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");

	private static boolean isJvmInternal(String className) {
		if (className == null || className.isBlank()) {
			return true;
		}
		String n = HistogramClassNames.stripModuleSuffix(className);
		for (String prefix : JVM_INTERNAL_PREFIXES) {
			if (n.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		HeapDumpShallowSummary heap = evidence.heapShallowSummary();
		if (heap == null || !heap.analysisSucceeded() || heap.topByShallowBytes().isEmpty()) {
			return;
		}
		HeapShallowClassEntry top = heap.topByShallowBytes().get(0);
		if (isJvmInternal(top.className())) {
			return;
		}
		String normalized = HistogramClassNames.stripModuleSuffix(top.className());
		if (HistogramClassNames.isAnyArrayDescriptor(normalized) && !HistogramClassNames.isPrimitiveArrayDescriptor(normalized)) {
			return;
		}
		if (top.approxSharePercent() < 30.0d) {
			return;
		}
		scratch.addFinding(new TuningFinding("Dominant shallow type in heap dump", "high",
				"hprof shallow leader=" + normalized + " shallowBytes=" + top.shallowBytes() + " approxSharePercent="
						+ String.format("%.2f", top.approxSharePercent()),
				"indexed-heap-shallow",
				"A single type accounting for a large shallow share often merits allocation/retention review"));
		scratch.addRecommendation(new TuningRecommendation(
				"Trace allocation and retention for the dominant heap-dump shallow type", "code-review",
				"Cross-check with class histogram timing; inspect dominator paths in MAT if needed",
				"Targets the largest shallow contributor seen in the dump",
				"Shallow share is not retained-size; MAT may reorder leaders", "Validate with allocation profiling"));
		if (HistogramClassNames.isPrimitiveArrayDescriptor(normalized)) {
			scratch.addNextStep("In MAT or similar, inspect dominator paths for large " + normalized
					+ " and merge paths to GC roots");
		}
		else {
			scratch.addNextStep("Open the type in MAT dominator tree or compare with live histogram sampling for " + normalized);
		}
	}
}
