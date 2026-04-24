package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrExecutionSampleSummary(long sampleCount, List<JfrStackAggregate> topMethods) {

	public JfrExecutionSampleSummary {
		topMethods = List.copyOf(topMethods);
	}

}
