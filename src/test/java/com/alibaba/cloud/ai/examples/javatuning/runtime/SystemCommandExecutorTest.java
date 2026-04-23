package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.time.Duration;
import java.nio.file.Files;
import java.util.List;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SystemCommandExecutorTest {

	@Test
	void shouldAllowWhitelistedJvmCommands() {
		SystemCommandExecutor executor = new SystemCommandExecutor(List.of("jps", "jcmd", "jstat"));

		assertThat(executor.validate(List.of("jcmd", "123", "VM.version"))).isTrue();
	}

	@Test
	void shouldRejectNonWhitelistedCommands() {
		SystemCommandExecutor executor = new SystemCommandExecutor(List.of("jps", "jcmd", "jstat"));

		assertThatThrownBy(() -> executor.run(List.of("bash", "-lc", "rm -rf /")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("not in whitelist");
	}

	@Test
	void defaultExecuteShouldRejectUnsupportedOptionsPath() {
		CommandExecutor executor = command -> "ok";

		assertThatThrownBy(() -> executor.execute(List.of("cmd"), new CommandExecutionOptions(100L, 1024)))
			.isInstanceOf(UnsupportedOperationException.class)
			.hasMessageContaining("CommandExecutionOptions");
	}

	@Test
	void shouldTimeOutAndKillLongRunningCommand() {
		SystemCommandExecutor executor = executorWithJavaWhitelist(2_000, 1024);
		Path pidFile = createTempFile("command-executor-pid-", ".txt");

		CommandExecutionResult result = executor.execute(
				fixtureCommand("sleepWithPidFile", pidFile.toString(), "10000"),
				new CommandExecutionOptions(2_000L, 1024));

		assertThat(result.timedOut()).isTrue();
		assertThat(result.exitCode()).isEqualTo(-1);
		assertThat(result.failureMessage()).contains("timed out");
		assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(1L);
		TrackedProcess trackedProcess = parseTrackedProcess(readText(pidFile));
		assertThat(trackedProcess.pid()).isPositive();
		assertThat(waitForTrackedProcessGone(trackedProcess, Duration.ofSeconds(5))).isTrue();
	}

	@Test
	void shouldCaptureAllSuccessfulOutputWithoutTruncation() {
		SystemCommandExecutor executor = executorWithJavaWhitelist(5_000, 64);

		CommandExecutionResult result = executor.execute(fixtureCommand("chunkedLarge", "65536"),
				new CommandExecutionOptions(5_000L, 128 * 1024));

		assertThat(result.timedOut()).isFalse();
		assertThat(result.exitCode()).isEqualTo(0);
		assertThat(result.truncated()).isFalse();
		assertThat(result.output()).hasSize(65_536);
		assertThat(result.output()).isEqualTo("x".repeat(65_536));
	}

	@Test
	void runShouldThrowWithStructuredFailureMessageForNonZeroExit() {
		SystemCommandExecutor executor = executorWithJavaWhitelist(5_000, 1024);

		assertThatThrownBy(() -> executor.run(fixtureCommand("exit", "7")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("exited with code 7");
	}

	private static SystemCommandExecutor executorWithJavaWhitelist(long timeoutMs, int maxOutputBytes) {
		String javaExe = javaExecutable();
		return new SystemCommandExecutor(List.of(javaExe), timeoutMs, maxOutputBytes);
	}

	private static List<String> fixtureCommand(String mode, String... values) {
		String[] arguments = new String[5 + values.length];
		arguments[0] = javaExecutable();
		arguments[1] = "-cp";
		arguments[2] = System.getProperty("java.class.path");
		arguments[3] = "com.alibaba.cloud.ai.examples.javatuning.runtime.CommandExecutorFixtureMain";
		arguments[4] = mode;
		if (values.length > 0) {
			System.arraycopy(values, 0, arguments, 5, values.length);
		}
		return List.of(arguments);
	}

	private static String javaExecutable() {
		String suffix = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
		return Path.of(System.getProperty("java.home"), "bin", suffix).toString();
	}

	private static TrackedProcess parseTrackedProcess(String output) {
		String[] parts = output.strip().split(",");
		if (parts.length != 2) {
			throw new IllegalStateException("Expected pid and start time but got: " + output);
		}
		return new TrackedProcess(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
	}

	private static String readText(Path path) {
		try {
			return Files.readString(path);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to read " + path, ex);
		}
	}

	private static Path createTempFile(String prefix, String suffix) {
		try {
			return Files.createTempFile(prefix, suffix);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Failed to create temp file", ex);
		}
	}

	private static boolean waitForTrackedProcessGone(TrackedProcess process, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (isSpecificProcessGone(process)) {
				return true;
			}
			try {
				Thread.sleep(25L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return isSpecificProcessGone(process);
	}

	private static boolean isSpecificProcessGone(TrackedProcess process) {
		return ProcessHandle.of(process.pid()).map(handle -> {
			if (!handle.isAlive()) {
				return true;
			}
			String commandLine = handle.info().commandLine().orElse("");
			long currentStart = handle.info().startInstant().map(instant -> instant.toEpochMilli()).orElse(-1L);
			return !commandLine.contains("CommandExecutorFixtureMain") || currentStart != process.startMillis();
		}).orElse(true);
	}

	private record TrackedProcess(long pid, long startMillis) {
	}

}
