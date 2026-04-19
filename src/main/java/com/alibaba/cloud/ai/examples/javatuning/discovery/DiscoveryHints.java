package com.alibaba.cloud.ai.examples.javatuning.discovery;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DiscoveryHints {

	private static final Pattern USER_DIR = Pattern.compile("-Duser\\.dir=([^\\s]+)");

	private static final Pattern SERVER_PORT = Pattern
		.compile("(?:^|\\s)(?:-Dserver\\.port=|--server\\.port=)(\\d+)");

	private DiscoveryHints() {
	}

	static String workDirHint(String commandLine) {
		if (commandLine == null || commandLine.isBlank()) {
			return "";
		}
		Matcher matcher = USER_DIR.matcher(commandLine);
		return matcher.find() ? matcher.group(1) : "";
	}

	static String applicationTypeHint(String mainClassOrJar, boolean springBootHint) {
		if (springBootHint) {
			return "spring-boot";
		}
		if (mainClassOrJar != null && mainClassOrJar.toLowerCase().endsWith(".jar")) {
			return "executable-jar";
		}
		return "java-main";
	}

	static List<Integer> portHints(String commandLine) {
		if (commandLine == null || commandLine.isBlank()) {
			return List.of();
		}
		Matcher matcher = SERVER_PORT.matcher(commandLine);
		List<Integer> ports = new ArrayList<>();
		while (matcher.find()) {
			ports.add(Integer.parseInt(matcher.group(1)));
		}
		return List.copyOf(ports);
	}
}
