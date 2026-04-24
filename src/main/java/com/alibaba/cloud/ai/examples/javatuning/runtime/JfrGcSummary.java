package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrGcSummary(long gcCount, double totalGcPauseMs, double maxGcPauseMs, List<JfrCount> topGcCauses,
		List<JfrHeapSample> heapBeforeAfterSamples) {

	public JfrGcSummary {
		topGcCauses = List.copyOf(topGcCauses);
		heapBeforeAfterSamples = List.copyOf(heapBeforeAfterSamples);
	}

}
