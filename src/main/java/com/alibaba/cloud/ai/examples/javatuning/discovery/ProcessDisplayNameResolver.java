package com.alibaba.cloud.ai.examples.javatuning.discovery;

import java.util.ArrayList;
import java.util.List;

public class ProcessDisplayNameResolver {

	private static final String SPRING_APPLICATION_NAME_PREFIX = "-Dspring.application.name=";

	private static final String SPRING_PROFILES_ACTIVE_PREFIX = "--spring.profiles.active=";

	public String resolveDisplayName(String commandLine) {
		String springApplicationName = resolveSpringApplicationName(commandLine);
		if (springApplicationName != null) {
			return springApplicationName;
		}

		String mainClassOrJar = firstToken(commandLine);
		int lastDot = mainClassOrJar.lastIndexOf('.');
		return lastDot >= 0 ? mainClassOrJar.substring(lastDot + 1) : mainClassOrJar;
	}

	private String resolveSpringApplicationName(String commandLine) {
		int start = commandLine.indexOf(SPRING_APPLICATION_NAME_PREFIX);
		if (start < 0) {
			return null;
		}
		int valueStart = start + SPRING_APPLICATION_NAME_PREFIX.length();
		int valueEnd = commandLine.indexOf(' ', valueStart);
		return valueEnd < 0 ? commandLine.substring(valueStart) : commandLine.substring(valueStart, valueEnd);
	}

	public List<String> resolveProfiles(String commandLine) {
		int start = commandLine.indexOf(SPRING_PROFILES_ACTIVE_PREFIX);
		if (start < 0) {
			return List.of();
		}
		int valueStart = start + SPRING_PROFILES_ACTIVE_PREFIX.length();
		int valueEnd = commandLine.indexOf(' ', valueStart);
		String profiles = valueEnd < 0 ? commandLine.substring(valueStart) : commandLine.substring(valueStart, valueEnd);
		List<String> result = new ArrayList<>();
		for (String profile : profiles.split(",")) {
			if (!profile.isBlank()) {
				result.add(profile);
			}
		}
		return result;
	}

	private String firstToken(String commandLine) {
		int space = commandLine.indexOf(' ');
		return space < 0 ? commandLine : commandLine.substring(0, space);
	}

}
