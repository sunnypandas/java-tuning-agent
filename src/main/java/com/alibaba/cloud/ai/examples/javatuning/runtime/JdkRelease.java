package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public enum JdkRelease {

	JDK_11(11), JDK_17(17), JDK_21(21), JDK_25(25), LEGACY(-1), UNKNOWN(0);

	private static final Pattern LEGACY_VERSION_PATTERN = Pattern.compile("(?<!\\d)1\\.(\\d{1,2})(?:\\.\\d+)?");

	private static final Pattern VERSION_PATTERN = Pattern.compile("(?<!\\d)(\\d{1,2})(?:\\.\\d+)?");

	private final int major;

	JdkRelease(int major) {
		this.major = major;
	}

	public int major() {
		return major;
	}

	public static JdkRelease fromJvmVersion(String jvmVersion) {
		if (jvmVersion == null || jvmVersion.isBlank()) {
			return UNKNOWN;
		}
		Matcher legacy = LEGACY_VERSION_PATTERN.matcher(jvmVersion);
		if (legacy.find()) {
			int parsed = Integer.parseInt(legacy.group(1));
			if (parsed >= 8 && parsed <= 30) {
				return fromMajor(parsed);
			}
		}
		Matcher matcher = VERSION_PATTERN.matcher(jvmVersion);
		while (matcher.find()) {
			int parsed = Integer.parseInt(matcher.group(1));
			if (parsed >= 8 && parsed <= 30) {
				return fromMajor(parsed);
			}
		}
		return UNKNOWN;
	}

	private static JdkRelease fromMajor(int parsed) {
		if (parsed <= 0) {
			return UNKNOWN;
		}
		if (parsed < 11) {
			return LEGACY;
		}
		if (parsed < 17) {
			return JDK_11;
		}
		if (parsed < 21) {
			return JDK_17;
		}
		if (parsed < 25) {
			return JDK_21;
		}
		return JDK_25;
	}

}
