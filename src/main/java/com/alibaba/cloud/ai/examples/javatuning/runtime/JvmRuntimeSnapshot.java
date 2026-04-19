package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JvmRuntimeSnapshot(long pid, JvmMemorySnapshot memory, JvmGcSnapshot gc, List<String> vmFlags,
		String jvmVersion, Long threadCount, Long loadedClassCount, JvmCollectionMetadata collectionMetadata,
		List<String> warnings) {
}
