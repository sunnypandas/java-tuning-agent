package com.alibaba.cloud.ai.examples.javatuning.mcp;

/**
 * Result of {@link OfflineMcpTools#finalizeOfflineHeapDump(String, String, long)}: absolute path to the merged
 * {@code .hprof} file.
 */
public record OfflineHeapDumpFinalizeResult(String finalizeHeapDumpPath, String message) {

}
