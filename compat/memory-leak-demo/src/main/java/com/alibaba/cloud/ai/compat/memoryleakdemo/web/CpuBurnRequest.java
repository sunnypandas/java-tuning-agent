package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CpuBurnRequest(@Min(1) @Max(300) int durationSeconds, @Min(1) @Max(16) int workerThreads) {
}
