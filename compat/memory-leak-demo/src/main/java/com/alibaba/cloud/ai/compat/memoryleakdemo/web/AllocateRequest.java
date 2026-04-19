package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record AllocateRequest(@Min(1) @Max(2000) int entries, @Min(1) @Max(1024) int payloadKb,
		@NotBlank String tag) {
}
