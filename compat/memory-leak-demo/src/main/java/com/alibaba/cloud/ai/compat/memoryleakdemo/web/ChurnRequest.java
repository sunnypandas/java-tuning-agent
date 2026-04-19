package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ChurnRequest(
		@Min(1) @Max(20_000_000) int iterations,
		@Min(64) @Max(1_048_576) int payloadBytes) {
}
