package com.alibaba.cloud.ai.examples.javatuning.advice;

public record SuspectedCodeHotspot(String className, String fileHint, String suspicionReason, String evidenceLink,
		String confidence) {
}
