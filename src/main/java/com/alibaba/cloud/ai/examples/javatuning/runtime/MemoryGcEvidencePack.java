package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
		ThreadDumpSummary threadDump, List<String> missingData, List<String> warnings, String heapDumpPath) {

	public MemoryGcEvidencePack {
		missingData = List.copyOf(missingData);
		warnings = List.copyOf(warnings);
	}

}
