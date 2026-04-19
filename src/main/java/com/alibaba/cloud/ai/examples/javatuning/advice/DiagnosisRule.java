package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

@FunctionalInterface
public interface DiagnosisRule {

	void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch);
}
