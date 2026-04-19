package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ThreadDumpParser {

	private static final Pattern THREAD_HEADER = Pattern.compile("^\"[^\"]+\"\\s+#\\d+");

	private static final Pattern THREAD_STATE = Pattern
		.compile("^\\s*java\\.lang\\.Thread\\.State:\\s+(\\w+)(?:\\s|\\(|$)");

	public ThreadDumpSummary parse(String output) {
		if (output == null || output.isBlank()) {
			return new ThreadDumpSummary(0, Map.of(), List.of());
		}
		int threadCount = 0;
		Map<String, Long> byState = new LinkedHashMap<>();
		for (String rawLine : output.split("\\R")) {
			String line = rawLine;
			if (THREAD_HEADER.matcher(line).find()) {
				threadCount++;
				continue;
			}
			Matcher stateMatcher = THREAD_STATE.matcher(line);
			if (stateMatcher.find()) {
				String state = stateMatcher.group(1);
				byState.merge(state, 1L, Long::sum);
			}
		}
		List<String> deadlockHints = extractDeadlockHints(output);
		return new ThreadDumpSummary(threadCount, byState, deadlockHints);
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
}
