package com.alibaba.cloud.ai.examples.javatuning.offline;

import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;

/**
 * Pollable view of a background offline analysis job.
 */
public record OfflineAnalysisJobSnapshot(String jobId, String jobType, OfflineAnalysisJobStatus status,
		int progressPercent, long pollIntervalMillis, long createdAtEpochMs, long startedAtEpochMs,
		long completedAtEpochMs, String message, HeapRetentionAnalysisResult result, String errorMessage) {

	public OfflineAnalysisJobSnapshot {
		jobId = jobId == null ? "" : jobId;
		jobType = jobType == null ? "" : jobType;
		status = status == null ? OfflineAnalysisJobStatus.QUEUED : status;
		message = message == null ? "" : message;
		errorMessage = errorMessage == null ? "" : errorMessage;
		progressPercent = Math.max(0, Math.min(100, progressPercent));
	}

}
