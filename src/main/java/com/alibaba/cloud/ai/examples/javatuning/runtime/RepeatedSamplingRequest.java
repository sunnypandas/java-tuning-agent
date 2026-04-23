package com.alibaba.cloud.ai.examples.javatuning.runtime;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Request for repeated safe read-only JVM runtime sampling.")
public record RepeatedSamplingRequest(
		@JsonPropertyDescription("Target JVM process id.") long pid,
		@JsonPropertyDescription("Number of samples; blank uses server default.") Integer sampleCount,
		@JsonPropertyDescription("Interval between samples in milliseconds; blank uses server default.") Long intervalMillis,
		@JsonPropertyDescription("Whether to include live thread count when available.") boolean includeThreadCount,
		@JsonPropertyDescription("Whether to include loaded class count when available.") boolean includeClassCount,
		@JsonPropertyDescription("Reserved for future privileged repeated modes; not required for safe read-only P0 sampling.") String confirmationToken) {

	public RepeatedSamplingRequest normalized(RepeatedSamplingProperties properties) {
		int count = sampleCount == null ? properties.defaultSampleCount() : sampleCount;
		long interval = intervalMillis == null ? properties.defaultIntervalMillis() : intervalMillis;
		if (count < 2 || count > properties.maxSampleCount()) {
			throw new IllegalArgumentException("sampleCount must be between 2 and " + properties.maxSampleCount());
		}
		if (interval < 500L || interval > 60_000L) {
			throw new IllegalArgumentException("intervalMillis must be between 500 and 60000");
		}
		long plannedDuration = Math.max(0L, count - 1L) * interval;
		if (plannedDuration > properties.maxTotalDurationMillis()) {
			throw new IllegalArgumentException(
					"sampling window exceeds max-total-duration " + properties.maxTotalDurationMillis());
		}
		return new RepeatedSamplingRequest(pid, count, interval, includeThreadCount, includeClassCount,
				confirmationToken == null ? "" : confirmationToken.trim());
	}

}
