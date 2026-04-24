package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrThreadSummary(long parkEventCount, long monitorEnterEventCount, double maxBlockedMs,
		List<JfrThreadBlockAggregate> topBlockedThreads) {

	public JfrThreadSummary {
		topBlockedThreads = List.copyOf(topBlockedThreads);
	}

}
