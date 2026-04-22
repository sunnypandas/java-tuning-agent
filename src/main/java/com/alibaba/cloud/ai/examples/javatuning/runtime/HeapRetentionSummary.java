package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Structured retention payload with approximate holder, chain, and root hints.")
public record HeapRetentionSummary(
		@JsonPropertyDescription("Dominant retained-type rows ordered by strongest retained-style signal first.") List<RetainedTypeSummary> dominantRetainedTypes,
		@JsonPropertyDescription("Suspected holder rows explaining what is keeping objects alive.") List<SuspectedHolderSummary> suspectedHolders,
		@JsonPropertyDescription("Representative retention chains after collapsing noisy internals.") List<RetentionChainSummary> retentionChains,
		@JsonPropertyDescription("GC-root proximity hints rather than a full root explorer.") List<GcRootHint> gcRootHints,
		@JsonPropertyDescription("Confidence, limits, and engine notes for the retention evidence.") HeapRetentionConfidence confidenceAndLimits,
		@JsonPropertyDescription("Markdown view of the retention summary.") String summaryMarkdown,
		@JsonPropertyDescription("Whether the retention section completed successfully.") boolean analysisSucceeded,
		@JsonPropertyDescription("Warnings, skipped passes, or partial-result notes for the retention section.") List<String> warnings,
		@JsonPropertyDescription("Empty on success; populated when the retention section failed.") String errorMessage) {

	public HeapRetentionSummary {
		dominantRetainedTypes = dominantRetainedTypes == null ? List.of() : List.copyOf(dominantRetainedTypes);
		suspectedHolders = suspectedHolders == null ? List.of() : List.copyOf(suspectedHolders);
		retentionChains = retentionChains == null ? List.of() : List.copyOf(retentionChains);
		gcRootHints = gcRootHints == null ? List.of() : List.copyOf(gcRootHints);
		confidenceAndLimits = confidenceAndLimits == null ? HeapRetentionConfidence.empty() : confidenceAndLimits;
		summaryMarkdown = summaryMarkdown == null ? "" : summaryMarkdown;
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
		errorMessage = errorMessage == null ? "" : errorMessage;
	}

	public static HeapRetentionSummary empty() {
		return new HeapRetentionSummary(List.of(), List.of(), List.of(), List.of(), HeapRetentionConfidence.empty(),
				"", false, List.of(), "");
	}
}
