package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public interface CommandExecutor {

	/**
	 * Execute a local JVM diagnostic command and return its combined output.
	 * @param command ordered command tokens
	 * @return stdout and stderr combined as a single string
	 */
	String run(List<String> command);

	default CommandExecutionResult execute(List<String> command, CommandExecutionOptions options) {
		throw new UnsupportedOperationException(
				"CommandExecutor.execute(List, CommandExecutionOptions) requires an implementation that can honor options");
	}

}
