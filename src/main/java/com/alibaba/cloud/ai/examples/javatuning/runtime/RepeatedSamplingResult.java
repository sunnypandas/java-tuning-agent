package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record RepeatedSamplingResult(long pid, List<RepeatedRuntimeSample> samples, List<String> warnings,
		List<String> missingData, long startedAtEpochMs, long elapsedMs) {

	public RepeatedSamplingResult {
		samples = samples == null ? List.of() : List.copyOf(samples);
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
		missingData = missingData == null ? List.of() : List.copyOf(missingData);
	}

}
