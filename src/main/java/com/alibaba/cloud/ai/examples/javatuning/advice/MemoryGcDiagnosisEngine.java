package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

public final class MemoryGcDiagnosisEngine {

	private final List<DiagnosisRule> rules;

	private final DiagnosisConfidenceEvaluator confidenceEvaluator;

	public MemoryGcDiagnosisEngine(List<DiagnosisRule> rules, DiagnosisConfidenceEvaluator confidenceEvaluator) {
		this.rules = List.copyOf(rules);
		this.confidenceEvaluator = confidenceEvaluator;
	}

	public static MemoryGcDiagnosisEngine firstVersion() {
		return new MemoryGcDiagnosisEngine(
				List.of(new HighHeapPressureRule(), new RepeatedSamplingTrendRule(), new SuspectedLeakRule(),
						new HeapDumpShallowDominanceRule(), new HeapRetentionInsightsRule(), new GcLogInsightsRule(),
						new ThreadDumpInsightsRule(), new AllocationChurnRule(), new GcStrategyMismatchRule(),
						new EvidenceGapRule()),
				new DiagnosisConfidenceEvaluator());
	}

	public TuningAdviceReport diagnose(MemoryGcEvidencePack evidence, CodeContextSummary context, String environment,
			String optimizationGoal) {
		DiagnosisScratch scratch = new DiagnosisScratch();
		for (DiagnosisRule rule : rules) {
			rule.evaluate(evidence, context, scratch);
		}
		DiagnosisConfidenceEvaluator.ConfidenceResult conf = confidenceEvaluator.evaluate(evidence, context, scratch);
		List<String> missing = new ArrayList<>(scratch.missingData());
		missing.addAll(evidence.missingData());
		List<String> next = new ArrayList<>(scratch.nextSteps());
		if (next.isEmpty() && scratch.findings().isEmpty()) {
			next.add("Compare jstat -gcutil samples over a steady load window");
		}
		return new TuningAdviceReport(scratch.findings(), scratch.recommendations(), List.of(), List.copyOf(missing),
				List.copyOf(next), conf.confidence(), conf.confidenceReasons(), "");
	}
}
