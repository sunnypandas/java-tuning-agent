package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

public record JfrWorkloadResponse(int durationSeconds, int workerThreads, int payloadBytes, long allocationLoops,
		long contentionLoops, String hint) {
}
