package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Top-level result for offline heap retention analysis.")
public record HeapRetentionAnalysisResult(
		@JsonPropertyDescription("Whether the retention analysis completed successfully.") boolean analysisSucceeded,
		@JsonPropertyDescription("Engine identifier used to produce the result.") String engine,
		@JsonPropertyDescription("Warnings, degradations, or partial-result notes.") List<String> warnings,
		@JsonPropertyDescription("Empty on success; populated when the analysis could not complete.") String errorMessage,
		@JsonPropertyDescription("Structured retention payload for MCP clients.") HeapRetentionSummary retentionSummary,
		@JsonPropertyDescription("Human-readable Markdown summary for UI display or logs.") String summaryMarkdown) {

	public HeapRetentionAnalysisResult {
		engine = engine == null ? "" : engine;
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
		errorMessage = errorMessage == null ? "" : errorMessage;
		retentionSummary = retentionSummary == null ? HeapRetentionSummary.empty() : retentionSummary;
		summaryMarkdown = summaryMarkdown == null ? "" : summaryMarkdown;
	}
}
