package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrAllocationSummary(long totalAllocationBytesApprox, List<JfrCountAndBytes> topAllocatedClasses,
		List<JfrStackAggregate> topAllocationStacks, long allocationEventCount) {

	public JfrAllocationSummary {
		topAllocatedClasses = List.copyOf(topAllocatedClasses);
		topAllocationStacks = List.copyOf(topAllocationStacks);
	}

}
