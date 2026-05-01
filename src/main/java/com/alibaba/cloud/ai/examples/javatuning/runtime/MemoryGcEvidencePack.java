package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

/** Primary evidence container for live and merged diagnosis inputs; use {@link MemoryGcEvidenceMerger} to combine optional attachments. */
public record MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
		ThreadDumpSummary threadDump, List<String> missingData, List<String> warnings, String heapDumpPath,
		HeapDumpShallowSummary heapShallowSummary, NativeMemorySummary nativeMemorySummary,
		HeapRetentionAnalysisResult heapRetentionAnalysis,
		RepeatedSamplingResult repeatedSamplingResult, GcLogSummary gcLogSummary, JfrSummary jfrSummary,
		MemoryGcEvidencePack baselineEvidence, DiagnosisWindow diagnosisWindow,
		ResourceBudgetEvidence resourceBudgetEvidence) {

	public MemoryGcEvidencePack {
		missingData = missingData == null ? List.of() : List.copyOf(missingData);
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
		baselineEvidence = sanitizeBaseline(baselineEvidence);
	}

	public MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
		ThreadDumpSummary threadDump, List<String> missingData, List<String> warnings, String heapDumpPath,
		HeapDumpShallowSummary heapShallowSummary) {
		this(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath, heapShallowSummary, null, null,
				null, null, null, null, null, null);
	}

	public MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
			ThreadDumpSummary threadDump, List<String> missingData, List<String> warnings, String heapDumpPath,
			HeapDumpShallowSummary heapShallowSummary, HeapRetentionAnalysisResult heapRetentionAnalysis) {
		this(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath, heapShallowSummary,
				null, heapRetentionAnalysis, null, null, null, null, null, null);
	}

	public MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
			ThreadDumpSummary threadDump, List<String> missingData, List<String> warnings, String heapDumpPath,
			HeapDumpShallowSummary heapShallowSummary, HeapRetentionAnalysisResult heapRetentionAnalysis,
			RepeatedSamplingResult repeatedSamplingResult) {
		this(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath, heapShallowSummary,
				null, heapRetentionAnalysis, repeatedSamplingResult, null, null, null, null, null);
	}

	public MemoryGcEvidencePack withNativeMemorySummary(NativeMemorySummary nativeMemorySummary) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, nativeMemorySummary, heapRetentionAnalysis, repeatedSamplingResult, gcLogSummary,
				jfrSummary,
				baselineEvidence, diagnosisWindow, resourceBudgetEvidence);
	}

	public MemoryGcEvidencePack withHeapRetentionAnalysis(HeapRetentionAnalysisResult heapRetentionAnalysis) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, nativeMemorySummary, heapRetentionAnalysis, repeatedSamplingResult, gcLogSummary,
				jfrSummary,
				baselineEvidence, diagnosisWindow, resourceBudgetEvidence);
	}

	public MemoryGcEvidencePack withRepeatedSamplingResult(RepeatedSamplingResult repeatedSamplingResult) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, nativeMemorySummary, heapRetentionAnalysis, repeatedSamplingResult, gcLogSummary,
				jfrSummary,
				baselineEvidence, diagnosisWindow, resourceBudgetEvidence);
	}

	public MemoryGcEvidencePack withGcLogSummary(GcLogSummary gcLogSummary) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, nativeMemorySummary, heapRetentionAnalysis, repeatedSamplingResult, gcLogSummary,
				jfrSummary,
				baselineEvidence, diagnosisWindow, resourceBudgetEvidence);
	}

	public MemoryGcEvidencePack withJfrSummary(JfrSummary jfrSummary) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, nativeMemorySummary, heapRetentionAnalysis, repeatedSamplingResult, gcLogSummary,
				jfrSummary,
				baselineEvidence, diagnosisWindow, resourceBudgetEvidence);
	}

	public MemoryGcEvidencePack withBaselineEvidence(MemoryGcEvidencePack baselineEvidence) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, nativeMemorySummary, heapRetentionAnalysis, repeatedSamplingResult, gcLogSummary,
				jfrSummary,
				baselineEvidence, diagnosisWindow, resourceBudgetEvidence);
	}

	public MemoryGcEvidencePack withDiagnosisWindow(DiagnosisWindow diagnosisWindow) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, nativeMemorySummary, heapRetentionAnalysis, repeatedSamplingResult, gcLogSummary,
				jfrSummary, baselineEvidence, diagnosisWindow, resourceBudgetEvidence);
	}

	public MemoryGcEvidencePack withResourceBudgetEvidence(ResourceBudgetEvidence resourceBudgetEvidence) {
		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, missingData, warnings, heapDumpPath,
				heapShallowSummary, nativeMemorySummary, heapRetentionAnalysis, repeatedSamplingResult, gcLogSummary,
				jfrSummary, baselineEvidence, diagnosisWindow, resourceBudgetEvidence);
	}

	private static MemoryGcEvidencePack sanitizeBaseline(MemoryGcEvidencePack baselineEvidence) {
		if (baselineEvidence == null || baselineEvidence.baselineEvidence() == null) {
			return baselineEvidence;
		}
		return new MemoryGcEvidencePack(baselineEvidence.snapshot(), baselineEvidence.classHistogram(),
				baselineEvidence.threadDump(), baselineEvidence.missingData(), baselineEvidence.warnings(),
				baselineEvidence.heapDumpPath(), baselineEvidence.heapShallowSummary(),
				baselineEvidence.nativeMemorySummary(), baselineEvidence.heapRetentionAnalysis(),
				baselineEvidence.repeatedSamplingResult(), baselineEvidence.gcLogSummary(), baselineEvidence.jfrSummary(),
				null, baselineEvidence.diagnosisWindow(), baselineEvidence.resourceBudgetEvidence());
	}

}
