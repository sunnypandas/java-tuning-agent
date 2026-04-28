package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.ArrayList;
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
		List<String> corroborators = corroboratorsFor(normalized, evidence, scratch);
		boolean corroborated = !corroborators.isEmpty();
		scratch.addFinding(new TuningFinding(
				corroborated ? "Corroborated shallow heap dominance" : "Dominant shallow heap candidate",
				corroborated ? "high" : "medium",
				"hprof shallow leader=" + normalized + " shallowBytes=" + top.shallowBytes() + " approxSharePercent="
						+ String.format("%.2f", top.approxSharePercent())
						+ (corroborated ? " corroboratedBy=" + String.join(",", corroborators)
								: " retainedSize=not-proven"),
				corroborated ? "corroborated-heap-shallow" : "indexed-heap-shallow-candidate",
				corroborated
						? "A dominant shallow type plus independent evidence supports deeper allocation/retention review"
						: "A single shallow-by-class summary is a candidate signal only; shallow bytes do not prove retained ownership"));
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

	private static List<String> corroboratorsFor(String className, MemoryGcEvidencePack evidence, DiagnosisScratch scratch) {
		List<String> corroborators = new ArrayList<>();
		if (scratch.findings().stream().anyMatch(f -> RepeatedSamplingTrendRule.RISING_HEAP_TITLE.equals(f.title()))) {
			corroborators.add("repeated-heap-growth");
		}
		if (hasJfrAllocationFor(className, evidence)) {
			corroborators.add("jfr-allocation");
		}
		if (evidence.heapRetentionAnalysis() != null && evidence.heapRetentionAnalysis().analysisSucceeded()) {
			corroborators.add("heap-retention-analysis");
		}
		return corroborators;
	}

	private static boolean hasJfrAllocationFor(String className, MemoryGcEvidencePack evidence) {
		if (evidence.jfrSummary() == null || evidence.jfrSummary().allocationSummary() == null) {
			return false;
		}
		return evidence.jfrSummary().allocationSummary().topAllocatedClasses().stream().anyMatch(
				it -> HistogramClassNames.stripModuleSuffix(it.name()).equals(className));
	}
}
