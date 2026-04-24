package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapDumpSummarizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeJvmRuntimeCollectorJfrTest {

	private static final SharkHeapDumpSummarizer TEST_HEAP_SUMMARIZER = new SharkHeapDumpSummarizer(40, 32000);

	@TempDir
	Path tempDir;

	@Test
	void shouldRejectMissingConfirmationBeforeRunningCommands() {
		RecordingCommandExecutor executor = new RecordingCommandExecutor();
		SafeJvmRuntimeCollector collector = testCollector(executor);
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> collector.recordJfr(new JfrRecordingRequest(123L, 10, "profile", output.toString(),
				100, ""))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confirmationToken");
		assertThat(executor.commands).isEmpty();
	}

	@Test
	void shouldReturnMissingDataWhenJfrStartIsUnsupported() {
		RecordingCommandExecutor executor = new RecordingCommandExecutor();
		executor.nextResult = new CommandExecutionResult(List.of("jcmd", "123", "help", "JFR.start"), 1,
				"Unknown diagnostic command", false, false, 5L, "Unknown diagnostic command");
		SafeJvmRuntimeCollector collector = testCollector(executor);
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		JfrRecordingResult result = collector
			.recordJfr(new JfrRecordingRequest(123L, 10, "profile", output.toString(), 100, "confirmed"));

		assertThat(result.jfrPath()).isNull();
		assertThat(result.summary()).isNull();
		assertThat(result.missingData()).contains("jfrSupport", "jfrRecording");
		assertThat(result.warnings()).anyMatch(w -> w.contains("not available") || w.contains("Unknown"));
		assertThat(result.commandsRun()).containsExactly("jcmd 123 help JFR.start");
	}

	@Test
	void shouldRunOneShotRecordingWithDynamicTimeoutAndParseFile() throws Exception {
		RecordingCommandExecutor executor = new RecordingCommandExecutor();
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();
		executor.fileToCreateAfterSecondCommand = output;
		executor.results.add(new CommandExecutionResult(List.of("jcmd", "123", "help", "JFR.start"), 0,
				"JFR.start\nSyntax", false, false, 5L, ""));
		executor.results.add(new CommandExecutionResult(List.of("jcmd", "123", "JFR.start"), 0, "Started recording",
				false, false, 10L, ""));
		JfrSummary summary = emptySummary();
		SafeJvmRuntimeCollector collector = testCollector(executor, (path, maxEvents) -> {
			assertThat(path).isEqualTo(output);
			assertThat(maxEvents).isEqualTo(50);
			return summary;
		});

		JfrRecordingResult result = collector
			.recordJfr(new JfrRecordingRequest(123L, 12, "default", output.toString(), 50, "confirmed"));

		assertThat(result.jfrPath()).isEqualTo(output.toString());
		assertThat(result.fileSizeBytes()).isGreaterThan(0L);
		assertThat(result.summary()).isSameAs(summary);
		assertThat(result.missingData()).isEmpty();
		assertThat(result.commandsRun()).containsExactly("jcmd 123 help JFR.start",
				"jcmd 123 JFR.start name=java-tuning-agent-123-" + result.startedAtEpochMs()
						+ " settings=default duration=12s filename=" + output + " disk=true");
		assertThat(executor.options.get(1).timeoutMs()).isEqualTo(12_000L);
	}

	@Test
	void shouldReturnMissingFileWhenRecordingCompletesButFileDoesNotExist() {
		RecordingCommandExecutor executor = new RecordingCommandExecutor();
		Path output = tempDir.resolve("missing.jfr").toAbsolutePath().normalize();
		executor.results.add(new CommandExecutionResult(List.of("jcmd", "123", "help", "JFR.start"), 0,
				"JFR.start\nSyntax", false, false, 5L, ""));
		executor.results.add(new CommandExecutionResult(List.of("jcmd", "123", "JFR.start"), 0, "Started recording",
				false, false, 10L, ""));
		SafeJvmRuntimeCollector collector = testCollector(executor);

		JfrRecordingResult result = collector
			.recordJfr(new JfrRecordingRequest(123L, 10, "profile", output.toString(), 100, "confirmed"));

		assertThat(result.jfrPath()).isNull();
		assertThat(result.summary()).isNull();
		assertThat(result.missingData()).contains("jfrFile", "jfrSummary");
		assertThat(result.warnings()).anyMatch(w -> w.contains("file was not found"));
	}

	private static SafeJvmRuntimeCollector testCollector(CommandExecutor executor) {
		return testCollector(executor, (path, maxEvents) -> emptySummary());
	}

	private static SafeJvmRuntimeCollector testCollector(CommandExecutor executor, JfrSummaryParserAdapter parser) {
		return new SafeJvmRuntimeCollector(executor, RuntimeCollectionPolicy.safeReadonly(), TEST_HEAP_SUMMARIZER,
				false, RepeatedSamplingProperties.defaults(), new JfrRecordingProperties(30, 5, 300, 0L, 200_000, 10),
				parser, SafeJvmRuntimeCollector::sleepUncheckedForTests);
	}

	private static JfrSummary emptySummary() {
		return new JfrSummary(null, null, null, new JfrGcSummary(0, 0, 0, List.of(), List.of()),
				new JfrAllocationSummary(0, List.of(), List.of(), 0), new JfrThreadSummary(0, 0, 0, List.of()),
				new JfrExecutionSampleSummary(0, List.of()), java.util.Map.of(), List.of());
	}

	private static final class RecordingCommandExecutor implements CommandExecutor {

		private final List<List<String>> commands = new ArrayList<>();

		private final List<CommandExecutionOptions> options = new ArrayList<>();

		private final List<CommandExecutionResult> results = new ArrayList<>();

		private CommandExecutionResult nextResult;

		private Path fileToCreateAfterSecondCommand;

		@Override
		public String run(List<String> command) {
			throw new UnsupportedOperationException("JFR should use structured execute");
		}

		@Override
		public CommandExecutionResult execute(List<String> command, CommandExecutionOptions options) {
			this.commands.add(command);
			this.options.add(options);
			if (fileToCreateAfterSecondCommand != null && this.commands.size() == 2) {
				try {
					Files.writeString(fileToCreateAfterSecondCommand, "not-a-real-jfr-but-parser-is-stubbed");
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}
			if (nextResult != null) {
				return nextResult;
			}
			if (!results.isEmpty()) {
				return results.remove(0);
			}
			return new CommandExecutionResult(command, 0, "", false, false, 1L, "");
		}

	}

}
