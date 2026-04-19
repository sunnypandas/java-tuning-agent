package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

import java.util.List;

public record ValidationScenarioView(String id, String title, String description, List<String> steps,
		List<String> expectedFindingKeywords, String suggestedJvmFlags, List<String> curlExamples) {
}
