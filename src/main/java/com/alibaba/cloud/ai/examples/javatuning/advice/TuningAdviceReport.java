package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;

public record TuningAdviceReport(List<TuningFinding> findings, List<TuningRecommendation> recommendations,
		List<SuspectedCodeHotspot> suspectedCodeHotspots, List<String> missingData, List<String> nextSteps,
		String confidence, List<String> confidenceReasons, String formattedSummary) {

	public TuningAdviceReport {
		suspectedCodeHotspots = List.copyOf(suspectedCodeHotspots);
		confidenceReasons = List.copyOf(confidenceReasons);
		formattedSummary = formattedSummary == null ? "" : formattedSummary;
	}
}
