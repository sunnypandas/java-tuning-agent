package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JstatClassLoadedParser {

	private static final Pattern DATA_LINE = Pattern.compile("^\\s*(\\d+)\\s+");

	public Long parseLoadedClasses(String output) {
		if (output == null || output.isBlank()) {
			return null;
		}
		boolean afterHeader = false;
		for (String rawLine : output.split("\\R")) {
			String line = rawLine.trim();
			if (line.isEmpty()) {
				continue;
			}
			if (line.startsWith("Loaded") && line.contains("Bytes")) {
				afterHeader = true;
				continue;
			}
			if (!afterHeader) {
				continue;
			}
			Matcher matcher = DATA_LINE.matcher(line);
			if (matcher.find()) {
				return Long.parseLong(matcher.group(1));
			}
		}
		return null;
	}
}
