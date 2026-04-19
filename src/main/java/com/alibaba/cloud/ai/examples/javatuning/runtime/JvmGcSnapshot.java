package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record JvmGcSnapshot(String collector, long youngGcCount, long youngGcTimeMs, long fullGcCount,
		long fullGcTimeMs, Double oldUsagePercent) {
}
