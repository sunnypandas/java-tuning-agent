package com.alibaba.cloud.ai.examples.javatuning.runtime;

public class VmVersionParser {

	public String parse(String output) {
		if (output == null || output.isBlank()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		boolean skippedHeader = false;
		for (String rawLine : output.split("\\R")) {
			String line = rawLine.trim();
			if (line.isEmpty()) {
				continue;
			}
			if (!skippedHeader) {
				skippedHeader = true;
				if (line.endsWith(":") && line.chars().allMatch(c -> Character.isDigit(c) || c == ':')) {
					continue;
				}
				if (line.equalsIgnoreCase("VM version:")) {
					continue;
				}
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(line);
			if (sb.length() > 800) {
				break;
			}
		}
		return sb.toString().trim();
	}
}
