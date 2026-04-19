package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JvmCollectionMetadata(List<String> commandsRun, long collectedAtEpochMs, long elapsedMs,
		boolean privilegedCollection) {
}
