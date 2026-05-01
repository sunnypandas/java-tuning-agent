package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;

public class OfflineTargetConsistencyAnalyzer {

	private static final Pattern JAVA_COMMAND_LINE = Pattern.compile("(?m)^\\s*java_command:\\s*(\\S+)");

	private static final Pattern JAVA_COMMAND_PROPERTY = Pattern.compile("sun\\.rt\\.javaCommand\\s*=\\s*\"([^\"]+)\"");

	private static final Pattern TARGET_PID = Pattern.compile("(?m)\\btargetPid:\\s*(\\d+)\\b");

	private static final Pattern LEADING_PID = Pattern.compile("(?m)^\\s*(\\d+)\\s*:");

	public OfflineTargetConsistencyResult analyze(OfflineBundleDraft draft, CodeContextSummary context) {
		if (draft == null) {
			return new OfflineTargetConsistencyResult(true, "", List.of(), List.of());
		}
		CodeContextSummary ctx = context == null ? CodeContextSummary.empty() : context;
		List<String> warnings = new ArrayList<>();
		String identityCommand = firstMatch(JAVA_COMMAND_LINE, draft.jvmIdentityText());
		String runtimeCommand = firstMatch(JAVA_COMMAND_PROPERTY, draft.runtimeSnapshotText());
		String command = firstNonBlank(runtimeCommand, identityCommand);
		List<Long> pids = extractPids(draft, warnings);

		if (hasNoExpectedTarget(ctx) || command.isBlank()) {
			return new OfflineTargetConsistencyResult(true, command, pids, warnings);
		}

		if (matchesExpectedTarget(command, ctx)) {
			return new OfflineTargetConsistencyResult(true, command, pids, warnings);
		}

		List<String> mismatchWarnings = new ArrayList<>(warnings);
		mismatchWarnings.add("Offline evidence java_command appears to be " + command + ", but source context expects "
				+ expectedTargetDescription(ctx) + ".");
		return new OfflineTargetConsistencyResult(false, command, pids, mismatchWarnings);
	}

	private static List<Long> extractPids(OfflineBundleDraft draft, List<String> warnings) {
		Set<Long> pids = new LinkedHashSet<>();
		addPid(pids, firstMatch(LEADING_PID, draft.jvmIdentityText()));
		addPid(pids, firstMatch(TARGET_PID, draft.runtimeSnapshotText()));
		addArtifactPid(pids, draft.classHistogram(), "classHistogram", warnings);
		addArtifactPid(pids, draft.threadDump(), "threadDump", warnings);
		return List.copyOf(pids);
	}

	private static void addArtifactPid(Set<Long> pids, OfflineArtifactSource source, String label, List<String> warnings) {
		try {
			addPid(pids, firstMatch(LEADING_PID, OfflineTextLoader.load(source)));
		}
		catch (IOException ex) {
			warnings.add("Unable to read " + label + " while checking offline target consistency: " + ex.getMessage());
		}
	}

	private static void addPid(Set<Long> pids, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		try {
			pids.add(Long.parseLong(value));
		}
		catch (NumberFormatException ignored) {
			// Regex extraction should keep this unreachable; ignore malformed values defensively.
		}
	}

	private static boolean hasNoExpectedTarget(CodeContextSummary context) {
		return (context.applicationNames() == null || context.applicationNames().isEmpty())
				&& (context.candidatePackages() == null || context.candidatePackages().isEmpty());
	}

	private static boolean matchesExpectedTarget(String command, CodeContextSummary context) {
		String normalizedCommand = command.toLowerCase(Locale.ROOT);
		for (String applicationName : context.applicationNames()) {
			if (applicationName == null || applicationName.isBlank()) {
				continue;
			}
			if (normalizedCommand.contains(applicationName.trim().toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		for (String candidatePackage : context.candidatePackages()) {
			if (candidatePackage == null || candidatePackage.isBlank()) {
				continue;
			}
			if (command.startsWith(candidatePackage.trim() + ".")) {
				return true;
			}
		}
		return false;
	}

	private static String expectedTargetDescription(CodeContextSummary context) {
		List<String> expected = new ArrayList<>();
		expected.addAll(context.applicationNames());
		expected.addAll(context.candidatePackages());
		return String.join(" / ", expected.stream().filter(it -> it != null && !it.isBlank()).toList());
	}

	private static String firstMatch(Pattern pattern, String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? matcher.group(1).trim() : "";
	}

	private static String firstNonBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first.trim();
		}
		return second == null ? "" : second.trim();
	}

}
