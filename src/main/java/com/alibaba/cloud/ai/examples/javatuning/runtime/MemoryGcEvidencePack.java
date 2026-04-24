package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
		ThreadDumpSummary threadDump, List<String> missingData, List<String> warnings, String heapDumpPath,
		HeapDumpShallowSummary heapShallowSummary, HeapRetentionAnalysisResult heapRetentionAnalysis,
		RepeatedSamplingResult repeatedSamplingResult) {

	public MemoryGcEvidencePack {
		missingData = List.copyOf(missingData);
		warnings = List.copyOf(warnings);
	}

	public MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
			ThreadDumpSummary threadDump, List<String> missingData, List<String> warnings, String heapDumpPath,
			HeapDumpShallowSummary heapShallowSummary) {
		this(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath, heapShallowSummary, null, null);
	}

	public MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
			ThreadDumpSummary threadDump, List<String> missingData, List<String> warnings, String heapDumpPath,
			HeapDumpShallowSummary heapShallowSummary, HeapRetentionAnalysisResult heapRetentionAnalysis) {
		this(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath, heapShallowSummary,
				heapRetentionAnalysis, null);
	}

	public MemoryGcEvidencePack withHeapRetentionAnalysis(HeapRetentionAnalysisResult heapRetentionAnalysis) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, heapRetentionAnalysis, repeatedSamplingResult);
	}

	public MemoryGcEvidencePack withRepeatedSamplingResult(RepeatedSamplingResult repeatedSamplingResult) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, heapRetentionAnalysis, repeatedSamplingResult);
	}

}
