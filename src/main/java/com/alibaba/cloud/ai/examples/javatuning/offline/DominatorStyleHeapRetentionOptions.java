package com.alibaba.cloud.ai.examples.javatuning.offline;

public record DominatorStyleHeapRetentionOptions(int candidateMultiplier, int reverseDepthLimit, int forwardDepthLimit,
		int minReverseNodeLimit, int minForwardNodeLimit, int maxReverseNodeLimit, int maxForwardNodeLimit,
		int pathSearchNodeLimit) {

	public static DominatorStyleHeapRetentionOptions defaults() {
		return new DominatorStyleHeapRetentionOptions(32, 24, 8, 128_000, 256_000, 2_000_000, 4_000_000,
				16_384);
	}

	public DominatorStyleHeapRetentionOptions {
		candidateMultiplier = positiveOrDefault(candidateMultiplier, 32);
		reverseDepthLimit = positiveOrDefault(reverseDepthLimit, 24);
		forwardDepthLimit = positiveOrDefault(forwardDepthLimit, 8);
		minReverseNodeLimit = positiveOrDefault(minReverseNodeLimit, 128_000);
		minForwardNodeLimit = positiveOrDefault(minForwardNodeLimit, 256_000);
		maxReverseNodeLimit = Math.max(positiveOrDefault(maxReverseNodeLimit, 2_000_000), minReverseNodeLimit);
		maxForwardNodeLimit = Math.max(positiveOrDefault(maxForwardNodeLimit, 4_000_000), minForwardNodeLimit);
		pathSearchNodeLimit = positiveOrDefault(pathSearchNodeLimit, 16_384);
	}

	private static int positiveOrDefault(int value, int defaultValue) {
		return value > 0 ? value : defaultValue;
	}

}
