package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrThreadBlockAggregate(String threadName, long count, double totalBlockedMs, double maxBlockedMs,
		List<String> sampleStack) {

	public JfrThreadBlockAggregate {
		sampleStack = List.copyOf(sampleStack);
	}

}
