package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record JvmGcSnapshot(String collector, long youngGcCount, long youngGcTimeMs, long fullGcCount,
		long fullGcTimeMs, Double oldUsagePercent, Double metaspaceUtilPercent,
		Double compressedClassSpaceUtilPercent) {

	public JvmGcSnapshot(String collector, long youngGcCount, long youngGcTimeMs, long fullGcCount,
			long fullGcTimeMs, Double oldUsagePercent) {
		this(collector, youngGcCount, youngGcTimeMs, fullGcCount, fullGcTimeMs, oldUsagePercent, null, null);
	}

}
