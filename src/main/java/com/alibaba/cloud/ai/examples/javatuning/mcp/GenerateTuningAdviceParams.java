package com.alibaba.cloud.ai.examples.javatuning.mcp;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;

/**
 * Grouping record for tests and callers; the MCP tool exposes the same fields as individual
 * {@link JavaTuningMcpTools#generateTuningAdvice} parameters so JSON clients bind reliably.
 * <p>
 * When {@code collectClassHistogram}, {@code collectThreadDump}, or {@code includeHeapDump} is true, a non-blank
 * {@code confirmationToken} is required (same policy as {@code collectMemoryGcEvidence}).
 */
public record GenerateTuningAdviceParams(CodeContextSummary codeContextSummary, long pid, String environment,
		String optimizationGoal, boolean collectClassHistogram, boolean collectThreadDump, boolean includeHeapDump,
		String heapDumpOutputPath, String confirmationToken) {

	public GenerateTuningAdviceParams {
		environment = environment == null ? "" : environment;
		optimizationGoal = optimizationGoal == null ? "" : optimizationGoal;
		confirmationToken = confirmationToken == null ? "" : confirmationToken;
		heapDumpOutputPath = heapDumpOutputPath == null ? "" : heapDumpOutputPath.trim();
	}
}
