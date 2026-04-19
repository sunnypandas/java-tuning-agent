package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClassHistogramParser {

	private static final Pattern ENTRY_PATTERN = Pattern
		.compile("^\\s*(\\d+):\\s+(\\d+)\\s+(\\d+)\\s+(.+?)\\s*$");

	public ClassHistogramSummary parse(String output) {
		if (output == null || output.isBlank()) {
			return new ClassHistogramSummary(List.of(), 0L, 0L);
		}

		List<ClassHistogramEntry> entries = new ArrayList<>();
		long totalInstances = 0L;
		long totalBytes = 0L;
		for (String rawLine : output.split("\\R")) {
			String line = rawLine.trim();
			if (line.isEmpty() || line.startsWith("num") || line.startsWith("-")) {
				continue;
			}
			Matcher matcher = ENTRY_PATTERN.matcher(line);
			if (!matcher.matches()) {
				continue;
			}
			long rank = Long.parseLong(matcher.group(1));
			long instances = Long.parseLong(matcher.group(2));
			long bytes = Long.parseLong(matcher.group(3));
			String className = matcher.group(4).trim();
			entries.add(new ClassHistogramEntry(rank, instances, bytes, className));
			totalInstances += instances;
			totalBytes += bytes;
		}

		return new ClassHistogramSummary(entries, totalInstances, totalBytes);
	}

}
