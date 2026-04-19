package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;

public record TuningAdviceRequest(JvmRuntimeSnapshot runtimeSnapshot, CodeContextSummary codeContextSummary,
		String environment, String optimizationGoal, ClassHistogramSummary classHistogramHint) {

	public TuningAdviceRequest(JvmRuntimeSnapshot runtimeSnapshot, CodeContextSummary codeContextSummary,
			String environment, String optimizationGoal) {
		this(runtimeSnapshot, codeContextSummary, environment, optimizationGoal, null);
	}
}
