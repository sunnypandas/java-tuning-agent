package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.List;

public record OfflineTargetConsistencyResult(boolean targetMatched, String extractedJavaCommand,
		List<Long> extractedPids, List<String> warnings) {

	public OfflineTargetConsistencyResult {
		extractedJavaCommand = extractedJavaCommand == null ? "" : extractedJavaCommand;
		extractedPids = extractedPids == null ? List.of() : List.copyOf(extractedPids);
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
	}

}
