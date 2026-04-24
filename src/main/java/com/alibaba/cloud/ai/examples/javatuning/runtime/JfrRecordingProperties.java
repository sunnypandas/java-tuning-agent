package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record JfrRecordingProperties(int defaultDurationSeconds, int minDurationSeconds, int maxDurationSeconds,
		long completionGraceMs, int defaultMaxSummaryEvents, int topLimit) {

	public static JfrRecordingProperties defaults() {
		return new JfrRecordingProperties(30, 5, 300, 10_000L, 200_000, 10);
	}

	public JfrRecordingProperties {
		if (defaultDurationSeconds <= 0) {
			throw new IllegalArgumentException("defaultDurationSeconds must be positive");
		}
		if (minDurationSeconds <= 0) {
			throw new IllegalArgumentException("minDurationSeconds must be positive");
		}
		if (maxDurationSeconds < minDurationSeconds) {
			throw new IllegalArgumentException("maxDurationSeconds must be >= minDurationSeconds");
		}
		if (defaultDurationSeconds < minDurationSeconds || defaultDurationSeconds > maxDurationSeconds) {
			throw new IllegalArgumentException("defaultDurationSeconds must be within configured bounds");
		}
		if (completionGraceMs < 0L) {
			throw new IllegalArgumentException("completionGraceMs must be non-negative");
		}
		if (defaultMaxSummaryEvents <= 0) {
			throw new IllegalArgumentException("defaultMaxSummaryEvents must be positive");
		}
		if (topLimit <= 0) {
			throw new IllegalArgumentException("topLimit must be positive");
		}
	}
}
