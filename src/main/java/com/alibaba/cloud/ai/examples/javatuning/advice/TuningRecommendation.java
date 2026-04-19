package com.alibaba.cloud.ai.examples.javatuning.advice;

public record TuningRecommendation(String action, String category, String configExample, String expectedBenefit,
		String risk, String preconditions) {
}
