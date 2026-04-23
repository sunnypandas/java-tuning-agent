package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.LongConsumer;

import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapDumpSummarizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SafeJvmRuntimeCollectorRepeatedSamplingTest {

	private static final SharkHeapDumpSummarizer TEST_HEAP_SUMMARIZER = new SharkHeapDumpSummarizer(40, 32000);

	@Test
	void shouldKeepSuccessfulSamplesWhenOneSampleFails() {
		ScriptedCommandExecutor executor = new ScriptedCommandExecutor();
		executor.addStandardSample("123", 100, 40.0, 1, 10, 0, 0);
		executor.addFailureForNextHeapInfo("attach failed");
		executor.addStandardSample("123", 180, 55.0, 2, 20, 0, 0);

		SafeJvmRuntimeCollector collector = collector(executor, millis -> {
		});

		RepeatedSamplingResult result = collector
			.collectRepeated(new RepeatedSamplingRequest(123L, 3, 500L, true, true, ""));

		assertThat(result.samples()).hasSize(2);
		assertThat(result.warnings()).anyMatch(w -> w.contains("sample 2") && w.contains("attach failed"));
		assertThat(result.missingData()).contains("sample[1]");
	}

	private static SafeJvmRuntimeCollector collector(CommandExecutor executor, LongConsumer sleeper) {
		return new SafeJvmRuntimeCollector(executor, RuntimeCollectionPolicy.safeReadonly(), TEST_HEAP_SUMMARIZER, false,
				RepeatedSamplingProperties.defaults(), sleeper);
	}

	private static final class ScriptedCommandExecutor implements CommandExecutor {

		private final Deque<Object> heapInfos = new ArrayDeque<>();

		private final Deque<String> gcUtils = new ArrayDeque<>();

		private final Deque<String> classOutputs = new ArrayDeque<>();

		private final Deque<String> perfOutputs = new ArrayDeque<>();

		private String flags = "-XX:+UseG1GC -Xms512m -Xmx1024m";

		private String version = "123:\nOpenJDK 64-Bit Server VM (test)";

		void addStandardSample(String pid, long heapUsedMb, double oldPercent, long ygc, long ygctMs, long fgc,
				long fgctMs) {
			heapInfos.add(heapInfo(pid, heapUsedMb));
			gcUtils.add(gcUtil(oldPercent, ygc, ygctMs, fgc, fgctMs));
			classOutputs.add(classOutput(1000 + ygc));
			perfOutputs.add(perfOutput(20 + ygc));
		}

		void addFailureForNextHeapInfo(String message) {
			heapInfos.add(new IllegalStateException(message));
		}

		@Override
		public String run(List<String> command) {
			if (command.equals(List.of("jcmd", "123", "VM.flags"))) {
				return flags;
			}
			if (command.equals(List.of("jcmd", "123", "VM.version"))) {
				return version;
			}
			if (command.equals(List.of("jcmd", "123", "GC.heap_info"))) {
				return nextValue(heapInfos);
			}
			if (command.equals(List.of("jstat", "-gcutil", "123"))) {
				return nextValue(gcUtils);
			}
			if (command.equals(List.of("jstat", "-class", "123"))) {
				return nextValue(classOutputs);
			}
			if (command.equals(List.of("jcmd", "123", "PerfCounter.print"))) {
				return nextValue(perfOutputs);
			}
			throw new IllegalArgumentException("Unexpected command: " + command);
		}

		private static String nextValue(Deque<?> values) {
			Object next = values.removeFirst();
			if (next instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			return Objects.toString(next, "");
		}

		private static String heapInfo(String pid, long heapUsedMb) {
			return """
					%s:
					garbage-first heap total 1048576K, used %dK
					G1 Old Generation
					  used 256000K
					Metaspace       used 8192K, committed 9216K, reserved 65536K
					""".formatted(pid, heapUsedMb * 1024L);
		}

		private static String gcUtil(double oldPercent, long ygc, long ygctMs, long fgc, long fgctMs) {
			return """
					  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
					  0.00   0.00  12.34  %.2f  92.21   88.12     %d    %.3f      %d    %.3f      -       -     1.690
					""".formatted(oldPercent, ygc, ygctMs / 1000.0d, fgc, fgctMs / 1000.0d);
		}

		private static String classOutput(long loadedClasses) {
			return """
					Loaded  Bytes  Unloaded  Bytes     Time
					  %d  24.00        0     0.00       0.01
					""".formatted(loadedClasses);
		}

		private static String perfOutput(long threadsLive) {
			return "java.threads.live            " + threadsLive + "\n";
		}

	}

}
