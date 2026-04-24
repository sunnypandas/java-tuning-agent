package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record RepeatedRuntimeSample(long sampledAtEpochMs, JvmMemorySnapshot memory, JvmGcSnapshot gc,
		Long threadCount, Long loadedClassCount, List<String> warnings) {

	public RepeatedRuntimeSample {
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
	}

}
