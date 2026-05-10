package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ThreadDumpParser {

	private static final int MAX_CPU_THREADS = 10;

	private static final Pattern THREAD_HEADER = Pattern.compile("^\"([^\"]+)\"\\s+#\\d+.*?cpu=([0-9]+(?:\\.[0-9]+)?)ms.*?\\bnid=([^\\s]+)");

	private static final Pattern ANY_THREAD_HEADER = Pattern.compile("^\"[^\"]+\"\\s+#\\d+");

	private static final Pattern THREAD_STATE = Pattern
		.compile("^\\s*java\\.lang\\.Thread\\.State:\\s+(\\w+)(?:\\s|\\(|$)");

	private static final Pattern STACK_FRAME = Pattern.compile("^\\s+at\\s+(.+)$");

	public ThreadDumpSummary parse(String output) {
		if (output == null || output.isBlank()) {
			return new ThreadDumpSummary(0, Map.of(), List.of(), List.of());
		}
		int threadCount = 0;
		Map<String, Long> byState = new LinkedHashMap<>();
		List<MutableThreadCpuSample> cpuSamples = new ArrayList<>();
		MutableThreadCpuSample current = null;
		for (String rawLine : output.split("\\R")) {
			String line = rawLine;
			Matcher headerMatcher = THREAD_HEADER.matcher(line);
			if (headerMatcher.find()) {
				threadCount++;
				current = new MutableThreadCpuSample(headerMatcher.group(1),
						Double.parseDouble(headerMatcher.group(2)), headerMatcher.group(3));
				cpuSamples.add(current);
				continue;
			}
			if (ANY_THREAD_HEADER.matcher(line).find()) {
				threadCount++;
				current = null;
				continue;
			}
			Matcher stateMatcher = THREAD_STATE.matcher(line);
			if (stateMatcher.find()) {
				String state = stateMatcher.group(1);
				byState.merge(state, 1L, Long::sum);
				if (current != null) {
					current.state = state;
				}
				continue;
			}
			if (current != null && current.topFrame.isBlank()) {
				Matcher frameMatcher = STACK_FRAME.matcher(line);
				if (frameMatcher.find()) {
					current.topFrame = frameMatcher.group(1);
				}
			}
		}
		List<String> deadlockHints = extractDeadlockHints(output);
		return new ThreadDumpSummary(threadCount, byState, deadlockHints, topCpuThreads(cpuSamples));
	}

	private static List<ThreadCpuSample> topCpuThreads(List<MutableThreadCpuSample> samples) {
		return samples.stream()
			.sorted(Comparator.comparingDouble(MutableThreadCpuSample::cpuTimeMs).reversed()
				.thenComparing(MutableThreadCpuSample::threadName))
			.limit(MAX_CPU_THREADS)
			.map(MutableThreadCpuSample::toSample)
			.toList();
	}

	private static List<String> extractDeadlockHints(String output) {
		List<String> hints = new ArrayList<>();
		String[] lines = output.split("\\R");
		boolean inDeadlock = false;
		for (String raw : lines) {
			String line = raw.trim();
			if (line.contains("Found one Java-level deadlock") || line.contains("Found one Java-level deadlock:")) {
				inDeadlock = true;
				hints.add(line);
				continue;
			}
			if (inDeadlock) {
				if (line.isEmpty() || line.startsWith("JNI global references")
						|| line.startsWith("Java stack information for the threads listed above")) {
					break;
				}
				if (hints.size() < 12) {
					hints.add(line);
				}
			}
		}
		return hints;
	}

	private static final class MutableThreadCpuSample {

		private final String threadName;

		private final double cpuTimeMs;

		private final String nid;

		private String state = "";

		private String topFrame = "";

		private MutableThreadCpuSample(String threadName, double cpuTimeMs, String nid) {
			this.threadName = threadName;
			this.cpuTimeMs = cpuTimeMs;
			this.nid = nid;
		}

		private String threadName() {
			return threadName;
		}

		private double cpuTimeMs() {
			return cpuTimeMs;
		}

		private ThreadCpuSample toSample() {
			return new ThreadCpuSample(threadName, cpuTimeMs, nid, state, topFrame);
		}
	}
}
