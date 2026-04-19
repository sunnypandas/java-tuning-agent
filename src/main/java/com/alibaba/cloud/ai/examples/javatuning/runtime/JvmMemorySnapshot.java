package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record JvmMemorySnapshot(long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes,
		Long oldGenUsedBytes, Long oldGenCommittedBytes, Long metaspaceUsedBytes, Long xmsBytes, Long xmxBytes) {
}
