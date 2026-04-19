package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

public record ChurnResponse(int iterations, int payloadBytes, long elapsedMs, long approxAllocatedMb,
		String hint) {
}
