package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapDumpShallowSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapShallowClassEntry;

/**
 * MCP-facing view of a local {@code .hprof} Shark summary (Markdown + shallow leaders).
 */
public record HeapDumpFileSummaryResult(String summaryMarkdown, boolean truncated, String errorMessage,
		List<HeapShallowClassEntry> topByShallowBytes, long totalTrackedShallowBytes) {

	public HeapDumpFileSummaryResult {
		summaryMarkdown = summaryMarkdown == null ? "" : summaryMarkdown;
		errorMessage = errorMessage == null ? "" : errorMessage;
		topByShallowBytes = topByShallowBytes == null ? List.of() : List.copyOf(topByShallowBytes);
	}

	public boolean success() {
		return errorMessage.isEmpty();
	}

	public static HeapDumpFileSummaryResult from(HeapDumpShallowSummary summary) {
		return new HeapDumpFileSummaryResult(summary.summaryMarkdown(), summary.truncated(), summary.errorMessage(),
				summary.topByShallowBytes(), summary.totalTrackedShallowBytes());
	}
}
