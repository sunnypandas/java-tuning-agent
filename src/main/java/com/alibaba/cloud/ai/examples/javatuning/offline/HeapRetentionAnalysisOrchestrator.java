package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;

public final class HeapRetentionAnalysisOrchestrator implements HeapRetentionAnalyzer {

	private final HeapRetentionAnalyzer sharkAnalyzer;

	private final HeapRetentionAnalyzer heavyAnalyzer;

	public HeapRetentionAnalysisOrchestrator(HeapRetentionAnalyzer sharkAnalyzer, HeapRetentionAnalyzer heavyAnalyzer) {
		this.sharkAnalyzer = sharkAnalyzer;
		this.heavyAnalyzer = heavyAnalyzer;
	}

	@Override
	public HeapRetentionAnalysisResult analyze(Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars,
			String analysisDepth, List<String> focusTypes, List<String> focusPackages) {
		var request = new HeapRetentionAnalysisRequest(heapDumpPath, topObjectLimit, maxOutputChars, analysisDepth,
				focusTypes, focusPackages);
		return switch (request.analysisDepth()) {
			case "fast", "balanced" -> sharkAnalyzer.analyze(request.heapDumpPath(), request.topObjectLimit(),
					request.maxOutputChars(), request.analysisDepth(), request.focusTypes(), request.focusPackages());
			case "deep" -> analyzeDeep(request);
			default -> sharkAnalyzer.analyze(request.heapDumpPath(), request.topObjectLimit(),
					request.maxOutputChars(), "balanced", request.focusTypes(), request.focusPackages());
		};
	}

	private HeapRetentionAnalysisResult analyzeDeep(HeapRetentionAnalysisRequest request) {
		try {
			HeapRetentionAnalysisResult heavyResult = heavyAnalyzer.analyze(request.heapDumpPath(),
					request.topObjectLimit(), request.maxOutputChars(), "deep", request.focusTypes(),
					request.focusPackages());
			if (heavyResult.analysisSucceeded()) {
				return heavyResult;
			}
			HeapRetentionAnalysisResult sharkResult = sharkAnalyzer.analyze(request.heapDumpPath(),
					request.topObjectLimit(), request.maxOutputChars(), "deep", request.focusTypes(),
					request.focusPackages());
			return appendFallbackWarning(sharkResult, heavyResult.errorMessage());
		}
		catch (RuntimeException | Error e) {
			HeapRetentionAnalysisResult sharkResult = sharkAnalyzer.analyze(request.heapDumpPath(),
					request.topObjectLimit(), request.maxOutputChars(), "deep", request.focusTypes(),
					request.focusPackages());
			return appendFallbackWarning(sharkResult, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}
	}

	private HeapRetentionAnalysisResult appendFallbackWarning(HeapRetentionAnalysisResult sharkResult,
			String heavyFailureMessage) {
		List<String> warnings = new ArrayList<>(sharkResult.warnings());
		String fallbackWarning = "Deep retained-style analysis fallback to Shark: " + heavyFailureMessage;
		warnings.add(fallbackWarning);
		HeapRetentionSummary summary = sharkResult.retentionSummary();
		List<String> summaryWarnings = new ArrayList<>(summary.warnings());
		summaryWarnings.add(fallbackWarning);
		HeapRetentionSummary updatedSummary = new HeapRetentionSummary(summary.dominantRetainedTypes(),
				summary.suspectedHolders(), summary.retentionChains(), summary.gcRootHints(),
				summary.confidenceAndLimits(), summary.summaryMarkdown(), summary.analysisSucceeded(),
				summaryWarnings, summary.errorMessage());
		return new HeapRetentionAnalysisResult(sharkResult.analysisSucceeded(), sharkResult.engine(), warnings,
				sharkResult.errorMessage(), updatedSummary, sharkResult.summaryMarkdown());
	}

}
