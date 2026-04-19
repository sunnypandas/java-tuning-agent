package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class SystemCommandExecutor implements CommandExecutor {

	private final List<String> whitelist;

	public SystemCommandExecutor(List<String> whitelist) {
		this.whitelist = List.copyOf(whitelist);
	}

	boolean validate(List<String> command) {
		return command != null && !command.isEmpty() && whitelist.contains(command.get(0));
	}

	@Override
	public String run(List<String> command) {
		if (!validate(command)) {
			throw new IllegalArgumentException("Command " + command + " is not in whitelist");
		}
		try {
			Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int exitCode = process.waitFor();
			if (exitCode != 0) {
				throw new IllegalStateException("Command " + command + " exited with code " + exitCode + ": " + output);
			}
			return output;
		}
		catch (IOException ex) {
			throw new IllegalStateException("Failed to execute command " + command, ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while executing command " + command, ex);
		}
	}

}
