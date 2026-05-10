package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;
import java.util.Map;

public record ThreadDumpSummary(int threadCount, Map<String, Long> threadsByState, List<String> deadlockHints,
		List<ThreadCpuSample> topCpuThreads) {

	public ThreadDumpSummary(int threadCount, Map<String, Long> threadsByState, List<String> deadlockHints) {
		this(threadCount, threadsByState, deadlockHints, List.of());
	}

	public ThreadDumpSummary {
		threadsByState = Map.copyOf(threadsByState);
		deadlockHints = List.copyOf(deadlockHints);
		topCpuThreads = topCpuThreads == null ? List.of() : List.copyOf(topCpuThreads);
	}
}
