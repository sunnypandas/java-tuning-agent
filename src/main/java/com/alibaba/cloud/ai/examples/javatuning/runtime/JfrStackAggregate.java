package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrStackAggregate(String frame, long count, long bytesApprox, List<String> sampleStack) {

	public JfrStackAggregate {
		sampleStack = List.copyOf(sampleStack);
	}

}
