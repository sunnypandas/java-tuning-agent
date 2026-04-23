package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record CommandExecutionOptions(long timeoutMs, int maxOutputBytes) {

	public static CommandExecutionOptions defaults() {
		return new CommandExecutionOptions(15_000L, 8 * 1024 * 1024);
	}

	public CommandExecutionOptions {
		if (timeoutMs <= 0L) {
			throw new IllegalArgumentException("timeoutMs must be positive");
		}
		if (maxOutputBytes <= 0) {
			throw new IllegalArgumentException("maxOutputBytes must be positive");
		}
	}

}
