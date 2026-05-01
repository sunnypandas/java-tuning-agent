package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.List;

public record HeapDumpCleanupResult(long deletedUploadCount, long deletedBytes, long retainedUploadCount,
		List<String> warnings) {

	public HeapDumpCleanupResult {
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
	}
}
