package com.alibaba.cloud.ai.examples.javatuning.discovery;

import java.util.List;

public record JavaApplicationDescriptor(long pid, String displayName, String mainClassOrJar, String commandLine,
		String workDirHint, String userHint, String jvmVersionHint, String applicationTypeHint, boolean springBootHint,
		List<String> profilesHint, List<Integer> portHints, String discoverySource, String discoveryConfidence) {
}
