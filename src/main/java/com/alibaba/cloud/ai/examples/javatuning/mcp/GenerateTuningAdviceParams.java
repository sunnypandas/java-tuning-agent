package com.alibaba.cloud.ai.examples.javatuning.mcp;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ResourceBudgetEvidence;

/**
 * Grouping record for tests and callers; the MCP tool exposes the same fields as individual
 * {@link JavaTuningMcpTools#generateTuningAdvice} parameters so JSON clients bind reliably.
 * <p>
 * When {@code collectClassHistogram}, {@code collectThreadDump}, or {@code includeHeapDump} is true, a non-blank
 * {@code confirmationToken} is required (same policy as {@code collectMemoryGcEvidence}).
 */
public record GenerateTuningAdviceParams(CodeContextSummary codeContextSummary, long pid, String environment,
		String optimizationGoal, boolean collectClassHistogram, boolean collectThreadDump, boolean includeHeapDump,
		String heapDumpOutputPath, String confirmationToken, MemoryGcEvidencePack baselineEvidence,
		JfrSummary jfrSummary, RepeatedSamplingResult repeatedSamplingResult,
		ResourceBudgetEvidence resourceBudgetEvidence) {

	public GenerateTuningAdviceParams {
		environment = environment == null ? "" : environment;
		optimizationGoal = optimizationGoal == null ? "" : optimizationGoal;
		confirmationToken = confirmationToken == null ? "" : confirmationToken;
		heapDumpOutputPath = heapDumpOutputPath == null ? "" : heapDumpOutputPath.trim();
	}

	public GenerateTuningAdviceParams(CodeContextSummary codeContextSummary, long pid, String environment,
			String optimizationGoal, boolean collectClassHistogram, boolean collectThreadDump, boolean includeHeapDump,
			String heapDumpOutputPath, String confirmationToken) {
		this(codeContextSummary, pid, environment, optimizationGoal, collectClassHistogram, collectThreadDump,
				includeHeapDump, heapDumpOutputPath, confirmationToken, null, null, null, null);
	}
}
