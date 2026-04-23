package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SystemCommandExecutor implements CommandExecutor {

	private final List<String> whitelist;
	private final long defaultTimeoutMs;
	private final int defaultMaxOutputBytes;

	public SystemCommandExecutor(List<String> whitelist) {
		this(whitelist, 15_000L, 8 * 1024 * 1024);
	}

	public SystemCommandExecutor(List<String> whitelist, long defaultTimeoutMs, int defaultMaxOutputBytes) {
		this.whitelist = List.copyOf(whitelist);
		this.defaultTimeoutMs = defaultTimeoutMs;
		this.defaultMaxOutputBytes = defaultMaxOutputBytes;
	}

	boolean validate(List<String> command) {
		return command != null && !command.isEmpty() && whitelist.contains(command.get(0));
	}

	@Override
	public String run(List<String> command) {
		CommandExecutionResult result = execute(command,
				new CommandExecutionOptions(defaultTimeoutMs, defaultMaxOutputBytes));
		if (!result.succeeded()) {
			throw new IllegalStateException(result.failureMessage());
		}
		return result.output();
	}

	@Override
	public CommandExecutionResult execute(List<String> command, CommandExecutionOptions options) {
		if (!validate(command)) {
			throw new IllegalArgumentException("Command " + command + " is not in whitelist");
		}
		long started = System.currentTimeMillis();
		Process process = null;
		Thread reader = null;
		try {
			process = new ProcessBuilder(command).redirectErrorStream(true).start();
			Process currentProcess = process;
			BundledOutput output = new BundledOutput(options.maxOutputBytes());
			reader = new Thread(() -> readProcessOutput(currentProcess.getInputStream(), output));
			reader.setDaemon(true);
			reader.start();
			boolean completed = process.waitFor(options.timeoutMs(), TimeUnit.MILLISECONDS);
			boolean timedOut = !completed;
			if (timedOut) {
				destroyProcess(process);
			}
			joinReader(reader);
			long elapsed = Math.max(0L, System.currentTimeMillis() - started);
			String text = output.text();
			boolean truncated = output.truncated();
			int exitCode = timedOut ? -1 : process.exitValue();
			String failureMessage = "";
			if (timedOut) {
				failureMessage = "Command " + command + " timed out after " + options.timeoutMs() + " ms";
			}
			else if (exitCode != 0) {
				failureMessage = "Command " + command + " exited with code " + exitCode + ": " + text;
			}
			return new CommandExecutionResult(command, exitCode, text, timedOut, truncated, elapsed, failureMessage);
		}
		catch (IOException ex) {
			return new CommandExecutionResult(command, -1, "", false, false,
					Math.max(0L, System.currentTimeMillis() - started), "Failed to execute command " + command + ": "
							+ ex.getMessage());
		}
		catch (InterruptedException ex) {
			if (process != null) {
				destroyProcess(process);
			}
			try {
				joinReader(reader);
			}
			catch (InterruptedException joinEx) {
				Thread.currentThread().interrupt();
			}
			Thread.currentThread().interrupt();
			return new CommandExecutionResult(command, -1, "", false, false,
					Math.max(0L, System.currentTimeMillis() - started),
					"Interrupted while executing command " + command + ": " + ex.getMessage());
		}
		finally {
			if (process != null) {
				closeQuietly(process.getOutputStream());
			}
			if (reader != null && reader.isAlive()) {
				reader.interrupt();
			}
		}
	}

	private static void readProcessOutput(InputStream inputStream, BundledOutput output) {
		try (InputStream in = inputStream) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) != -1) {
				output.append(buffer, read);
			}
		}
		catch (IOException ignored) {
			// Process completion or destruction can close the stream underneath us.
		}
	}

	private static void closeQuietly(Closeable stream) {
		if (stream == null) {
			return;
		}
		try {
			stream.close();
		}
		catch (IOException ignored) {
			// Best-effort shutdown for stream readers.
		}
	}

	private static void destroyProcess(Process process) {
		ProcessHandle handle = process.toHandle();
		handle.descendants().forEach(ProcessHandle::destroyForcibly);
		process.destroyForcibly();
		if (isWindows()) {
			runTaskKill(process.pid());
		}
		awaitProcessExit(handle, TimeUnit.SECONDS.toMillis(5));
	}

	private static boolean awaitProcessExit(ProcessHandle handle, long timeoutMs) {
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		while (System.nanoTime() < deadline) {
			if (!handle.isAlive()) {
				return true;
			}
			try {
				Thread.sleep(25L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return !handle.isAlive();
			}
		}
		return !handle.isAlive();
	}

	private static void runTaskKill(long pid) {
		try {
			String windowsDir = System.getenv("windir");
			String taskKill = windowsDir == null ? "taskkill" : windowsDir + "\\System32\\taskkill.exe";
			new ProcessBuilder(taskKill, "/F", "/T", "/PID", Long.toString(pid)).start().waitFor();
		}
		catch (IOException ignored) {
			// Fall back to the Java process API when taskkill is unavailable.
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}

	private static void joinReader(Thread reader) throws InterruptedException {
		if (reader != null) {
			reader.join(TimeUnit.SECONDS.toMillis(5));
		}
	}

	private static final class BundledOutput {

		private final ByteArrayOutputStream captured;
		private final int maxBytes;
		private int totalBytes;

		private BundledOutput(int maxBytes) {
			this.captured = new ByteArrayOutputStream(Math.max(32, Math.min(maxBytes, 8192)));
			this.maxBytes = maxBytes;
		}

		private synchronized void append(byte[] bytes, int length) {
			totalBytes += length;
			int remaining = maxBytes - captured.size();
			if (remaining <= 0) {
				return;
			}
			captured.write(bytes, 0, Math.min(length, remaining));
		}

		private synchronized String text() {
			return captured.toString(StandardCharsets.UTF_8);
		}

		private synchronized boolean truncated() {
			return totalBytes > captured.size();
		}
	}

}
