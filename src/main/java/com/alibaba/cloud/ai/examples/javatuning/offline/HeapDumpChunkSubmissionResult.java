package com.alibaba.cloud.ai.examples.javatuning.offline;

/**
 * Result of storing one heap-dump chunk; callers pass {@code uploadId} on subsequent calls until
 * {@code finalizeOfflineHeapDump}.
 */
public record HeapDumpChunkSubmissionResult(String uploadId, String message) {

}
