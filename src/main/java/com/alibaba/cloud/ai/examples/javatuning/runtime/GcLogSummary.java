package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;
import java.util.Map;

public record GcLogSummary(int pauseEventCount, int youngPauseCount, int fullPauseCount, int concurrentPauseCount,
		double maxPauseMs, double totalPauseMs, Long maxHeapBeforeBytes, Long minHeapAfterBytes,
		int humongousAllocationCount, int toSpaceExhaustedCount, Map<String, Long> topCauses, List<String> warnings) {

	public GcLogSummary {
		topCauses = topCauses == null ? Map.of() : Map.copyOf(topCauses);
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
	}

	public boolean hasPauseData() {
		return pauseEventCount > 0;
	}

}
