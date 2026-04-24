package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;
import java.util.Map;

public record JfrSummary(Long recordingStartEpochMs, Long recordingEndEpochMs, Long durationMs,
		JfrGcSummary gcSummary, JfrAllocationSummary allocationSummary, JfrThreadSummary threadSummary,
		JfrExecutionSampleSummary executionSampleSummary, Map<String, Long> eventCounts,
		List<String> parserWarnings) {

	public JfrSummary {
		eventCounts = Map.copyOf(eventCounts);
		parserWarnings = List.copyOf(parserWarnings);
	}

}
