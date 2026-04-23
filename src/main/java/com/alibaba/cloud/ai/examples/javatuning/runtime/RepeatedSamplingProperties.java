package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record RepeatedSamplingProperties(int defaultSampleCount, long defaultIntervalMillis, int maxSampleCount,
		long maxTotalDurationMillis) {

	public static RepeatedSamplingProperties defaults() {
		return new RepeatedSamplingProperties(3, 10_000L, 20, 300_000L);
	}

}
