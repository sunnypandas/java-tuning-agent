package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ClassloaderRequest(@Min(1) @Max(20_000) int loaders, @NotBlank String tag) {
}
