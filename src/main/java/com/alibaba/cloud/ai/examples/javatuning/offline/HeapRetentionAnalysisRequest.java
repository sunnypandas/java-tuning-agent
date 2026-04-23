package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Normalized request contract for offline heap retention analysis.")
public record HeapRetentionAnalysisRequest(
		@JsonPropertyDescription("Heap dump path to inspect.") Path heapDumpPath,
		@JsonPropertyDescription("Upper bound for the number of objects to analyze.") Integer topObjectLimit,
		@JsonPropertyDescription("Maximum Markdown output size.") Integer maxOutputChars,
		@JsonPropertyDescription("Requested depth: fast, balanced, or deep.") String analysisDepth,
		@JsonPropertyDescription("Types to prioritize when selecting candidate objects.") List<String> focusTypes,
		@JsonPropertyDescription("Packages to prioritize when selecting candidate objects.") List<String> focusPackages) {

	public HeapRetentionAnalysisRequest {
		analysisDepth = normalizeDepth(analysisDepth);
		focusTypes = focusTypes == null ? List.of() : List.copyOf(focusTypes);
		focusPackages = focusPackages == null ? List.of() : List.copyOf(focusPackages);
	}

	static String normalizeDepth(String analysisDepth) {
		if (analysisDepth == null || analysisDepth.isBlank()) {
			return "balanced";
		}
		return analysisDepth.trim().toLowerCase(Locale.ROOT);
	}

}
