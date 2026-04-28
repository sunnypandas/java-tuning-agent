package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ResourceBudgetEvidence;

public record TuningAdviceRequest(JvmRuntimeSnapshot runtimeSnapshot, CodeContextSummary codeContextSummary,
		String environment, String optimizationGoal, ClassHistogramSummary classHistogramHint,
		MemoryGcEvidencePack baselineEvidence, JfrSummary jfrSummary, RepeatedSamplingResult repeatedSamplingResult,
		ResourceBudgetEvidence resourceBudgetEvidence) {

	public TuningAdviceRequest(JvmRuntimeSnapshot runtimeSnapshot, CodeContextSummary codeContextSummary,
			String environment, String optimizationGoal) {
		this(runtimeSnapshot, codeContextSummary, environment, optimizationGoal, null, null, null, null, null);
	}

	public TuningAdviceRequest(JvmRuntimeSnapshot runtimeSnapshot, CodeContextSummary codeContextSummary,
			String environment, String optimizationGoal, ClassHistogramSummary classHistogramHint) {
		this(runtimeSnapshot, codeContextSummary, environment, optimizationGoal, classHistogramHint, null, null, null,
				null);
	}

	public TuningAdviceRequest(JvmRuntimeSnapshot runtimeSnapshot, CodeContextSummary codeContextSummary,
			String environment, String optimizationGoal, ClassHistogramSummary classHistogramHint,
			MemoryGcEvidencePack baselineEvidence) {
		this(runtimeSnapshot, codeContextSummary, environment, optimizationGoal, classHistogramHint, baselineEvidence,
				null, null, null);
	}

	public TuningAdviceRequest(JvmRuntimeSnapshot runtimeSnapshot, CodeContextSummary codeContextSummary,
			String environment, String optimizationGoal, ClassHistogramSummary classHistogramHint,
			MemoryGcEvidencePack baselineEvidence, JfrSummary jfrSummary) {
		this(runtimeSnapshot, codeContextSummary, environment, optimizationGoal, classHistogramHint, baselineEvidence,
				jfrSummary, null, null);
	}
}
