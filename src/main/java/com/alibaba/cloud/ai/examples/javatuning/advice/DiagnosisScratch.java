package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.ArrayList;
import java.util.List;

/** Mutable accumulator passed across {@link DiagnosisRule} implementations in one run. */
public final class DiagnosisScratch {

	private final List<TuningFinding> findings = new ArrayList<>();

	private final List<TuningRecommendation> recommendations = new ArrayList<>();

	private final List<String> missingData = new ArrayList<>();

	private final List<String> nextSteps = new ArrayList<>();

	public void addFinding(TuningFinding finding) {
		findings.add(finding);
	}

	public void addRecommendation(TuningRecommendation recommendation) {
		recommendations.add(recommendation);
	}

	public void addMissingData(String marker) {
		missingData.add(marker);
	}

	public void addNextStep(String step) {
		nextSteps.add(step);
	}

	public List<TuningFinding> findings() {
		return List.copyOf(findings);
	}

	public List<TuningRecommendation> recommendations() {
		return List.copyOf(recommendations);
	}

	public List<String> missingData() {
		return List.copyOf(missingData);
	}

	public List<String> nextSteps() {
		return List.copyOf(nextSteps);
	}
}
