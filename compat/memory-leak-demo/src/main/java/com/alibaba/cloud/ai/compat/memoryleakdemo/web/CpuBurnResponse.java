package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

public record CpuBurnResponse(boolean running, int workerThreads, String threadNamePrefix, String hotMethod,
		long loopCount, String hint) {
}
