package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

/**
 * Structured shallow heap statistics plus bounded Markdown produced when a {@code .hprof} is parsed locally (Shark).
 *
 * @param topByShallowBytes ranked shallow leaders (typically top N); may be empty on failure
 * @param totalTrackedShallowBytes sum of shallow bytes over instances + arrays in the dump
 * @param truncated whether Markdown hit the character cap
 * @param summaryMarkdown human-readable block (may be empty when {@link #errorMessage()} is set)
 * @param errorMessage empty on success; non-empty when summarization was not produced
 */
public record HeapDumpShallowSummary(List<HeapShallowClassEntry> topByShallowBytes, long totalTrackedShallowBytes,
		boolean truncated, String summaryMarkdown, String errorMessage) {

	public HeapDumpShallowSummary {
		topByShallowBytes = topByShallowBytes == null ? List.of() : List.copyOf(topByShallowBytes);
		summaryMarkdown = summaryMarkdown == null ? "" : summaryMarkdown;
		errorMessage = errorMessage == null ? "" : errorMessage;
	}

	public boolean analysisSucceeded() {
		return errorMessage.isEmpty();
	}
}
