package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
		ThreadDumpSummary threadDump, List<String> missingData, List<String> warnings, String heapDumpPath,
		HeapDumpShallowSummary heapShallowSummary, HeapRetentionAnalysisResult heapRetentionAnalysis) {

	public MemoryGcEvidencePack {
		missingData = List.copyOf(missingData);
		warnings = List.copyOf(warnings);
	}

	public MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
			ThreadDumpSummary threadDump, List<String> missingData, List<String> warnings, String heapDumpPath,
			HeapDumpShallowSummary heapShallowSummary) {
		this(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath, heapShallowSummary, null);
	}

	public MemoryGcEvidencePack withHeapRetentionAnalysis(HeapRetentionAnalysisResult heapRetentionAnalysis) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, heapRetentionAnalysis);
	}

}
