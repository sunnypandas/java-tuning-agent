package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CommandExecutorFixtureMain {

	private CommandExecutorFixtureMain() {
	}

	public static void main(String[] args) throws Exception {
		String mode = args.length > 0 ? args[0] : "";
		switch (mode) {
			case "sleep" -> Thread.sleep(Integer.parseInt(args[1]));
			case "sleepWithPid" -> {
				System.out.println(ProcessHandle.current().pid());
				System.out.flush();
				Thread.sleep(Integer.parseInt(args[1]));
			}
			case "sleepWithPidFile" -> {
				Path pidFile = Path.of(args[1]);
				int sleepMs = Integer.parseInt(args[2]);
				ProcessHandle self = ProcessHandle.current();
				long startMillis = self.info().startInstant().orElseThrow().toEpochMilli();
				Files.writeString(pidFile, self.pid() + "," + startMillis, StandardCharsets.UTF_8);
				Thread.sleep(sleepMs);
			}
			case "large" -> System.out.print("x".repeat(Integer.parseInt(args[1])));
			case "chunkedLarge" -> writeChunkedOutput(Integer.parseInt(args[1]));
			case "exit" -> System.exit(Integer.parseInt(args[1]));
			default -> System.out.print("ok");
		}
	}

	private static void writeChunkedOutput(int size) throws Exception {
		byte[] chunk = "x".repeat(Math.min(size, 8192)).getBytes(StandardCharsets.UTF_8);
		int remaining = size;
		while (remaining > 0) {
			int length = Math.min(remaining, chunk.length);
			System.out.write(chunk, 0, length);
			System.out.flush();
			remaining -= length;
		}
	}
}
