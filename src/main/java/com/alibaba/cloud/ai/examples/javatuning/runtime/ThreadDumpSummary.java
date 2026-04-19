package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;
import java.util.Map;

public record ThreadDumpSummary(int threadCount, Map<String, Long> threadsByState, List<String> deadlockHints) {

	public ThreadDumpSummary {
		threadsByState = Map.copyOf(threadsByState);
		deadlockHints = List.copyOf(deadlockHints);
	}
}
