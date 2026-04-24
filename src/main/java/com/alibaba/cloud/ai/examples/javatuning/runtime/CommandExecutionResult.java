package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record CommandExecutionResult(List<String> command, int exitCode, String output, boolean timedOut,
		boolean truncated, long elapsedMs, String failureMessage) {

	public CommandExecutionResult {
		command = command == null ? List.of() : List.copyOf(command);
		output = output == null ? "" : output;
		failureMessage = failureMessage == null ? "" : failureMessage;
	}

	public boolean succeeded() {
		return exitCode == 0 && !timedOut && failureMessage.isBlank();
	}

}
