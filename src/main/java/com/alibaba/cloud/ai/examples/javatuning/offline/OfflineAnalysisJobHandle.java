package com.alibaba.cloud.ai.examples.javatuning.offline;

/**
 * Small response returned immediately after starting a background analysis job.
 */
public record OfflineAnalysisJobHandle(String jobId, OfflineAnalysisJobStatus status, long pollIntervalMillis,
		String message) {

	public OfflineAnalysisJobHandle {
		jobId = jobId == null ? "" : jobId;
		status = status == null ? OfflineAnalysisJobStatus.QUEUED : status;
		message = message == null ? "" : message;
	}

}
