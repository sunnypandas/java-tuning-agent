package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record JfrHeapSample(Long timestampEpochMs, Long beforeUsedBytes, Long afterUsedBytes, Long heapUsedBytes,
		Long heapCommittedBytes) {
}
