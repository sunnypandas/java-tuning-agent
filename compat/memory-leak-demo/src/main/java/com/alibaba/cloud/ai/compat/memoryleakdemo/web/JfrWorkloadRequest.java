package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record JfrWorkloadRequest(@Min(1) @Max(120) int durationSeconds, @Min(1) @Max(16) int workerThreads,
		@Min(64) @Max(1_048_576) int payloadBytes) {
}
