package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
		ThreadDumpSummary threadDump, List<String> missingData, List<String> warnings, String heapDumpPath,
		HeapDumpShallowSummary heapShallowSummary, HeapRetentionAnalysisResult heapRetentionAnalysis,
		RepeatedSamplingResult repeatedSamplingResult, GcLogSummary gcLogSummary) {

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
				heapRetentionAnalysis, null, null);
	}

	public MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
			ThreadDumpSummary threadDump, List<String> missingData, List<String> warnings, String heapDumpPath,
			HeapDumpShallowSummary heapShallowSummary, HeapRetentionAnalysisResult heapRetentionAnalysis,
			RepeatedSamplingResult repeatedSamplingResult) {
		this(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath, heapShallowSummary,
				heapRetentionAnalysis, repeatedSamplingResult, null);
	}

	public MemoryGcEvidencePack withHeapRetentionAnalysis(HeapRetentionAnalysisResult heapRetentionAnalysis) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, heapRetentionAnalysis, repeatedSamplingResult, gcLogSummary);
	}

	public MemoryGcEvidencePack withRepeatedSamplingResult(RepeatedSamplingResult repeatedSamplingResult) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, heapRetentionAnalysis, repeatedSamplingResult, gcLogSummary);
	}

	public MemoryGcEvidencePack withGcLogSummary(GcLogSummary gcLogSummary) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, heapRetentionAnalysis, repeatedSamplingResult, gcLogSummary);
	}

}
