package com.alibaba.cloud.ai.examples.javatuning.offline;

/**
 * Lifecycle states for asynchronous offline analysis jobs.
 */
public enum OfflineAnalysisJobStatus {

	QUEUED,

	RUNNING,

	SUCCEEDED,

	FAILED,

	CANCELLED

}
