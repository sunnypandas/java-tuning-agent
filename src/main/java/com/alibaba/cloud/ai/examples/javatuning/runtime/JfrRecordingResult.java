package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JfrRecordingResult(long pid, String jfrPath, long fileSizeBytes, long startedAtEpochMs, long elapsedMs,
		List<String> commandsRun, JfrSummary summary, List<String> warnings, List<String> missingData) {

	public JfrRecordingResult {
		commandsRun = commandsRun == null ? List.of() : List.copyOf(commandsRun);
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
		missingData = missingData == null ? List.of() : List.copyOf(missingData);
	}
}
