package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OfflineDraftValidator {

	private static final Pattern LEADING_PID = Pattern.compile("(?m)^\\s*(\\d+)\\s*:");

	private static final Pattern TARGET_PID = Pattern.compile("(?m)\\btargetPid:\\s*(\\d+)\\b");

	public OfflineDraftValidationResult validate(OfflineBundleDraft draft, boolean proceedWithMissingRequired) {
		List<String> missing = new ArrayList<>();
		if (isBlank(draft.jvmIdentityText())) {
			missing.add("B1");
		}
		if (isBlank(draft.jdkInfoText())) {
			missing.add("B2");
		}
		if (isBlank(draft.runtimeSnapshotText())) {
			missing.add("B3");
		}
		if (!draft.classHistogram().isPresent()) {
			missing.add("B4");
		}
		if (!draft.threadDump().isPresent()) {
			missing.add("B5");
		}
		if (isBlank(draft.heapDumpAbsolutePath())) {
			missing.add("B6");
		}
		List<String> degradation = new ArrayList<>();
		if (!missing.isEmpty()) {
			degradation.add("必选材料不完整，分析置信度将降低。");
		}
		if (!draft.nativeMemorySummary().isPresent()) {
			degradation.add("建议补充 nativeMemorySummary（文件路径或文本），以支持堆外/元空间诊断。");
		}
		addPidConsistencyWarnings(draft, degradation);
		boolean allowed = proceedWithMissingRequired || missing.isEmpty();
		String prompt = buildPromptZh(missing, draft);
		return new OfflineDraftValidationResult(missing, degradation, prompt, allowed, suggestedStep(missing));
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static int suggestedStep(List<String> missing) {
		if (missing.contains("B1")) {
			return 0;
		}
		if (missing.contains("B2")) {
			return 1;
		}
		if (missing.contains("B3")) {
			return 2;
		}
		if (missing.contains("B4")) {
			return 3;
		}
		if (missing.contains("B5")) {
			return 4;
		}
		if (missing.contains("B6")) {
			return 5;
		}
		return 6;
	}

	private static String buildPromptZh(List<String> missing, OfflineBundleDraft draft) {
		if (missing.isEmpty()) {
			return "必选材料已齐，可生成分析报告或补充推荐项。";
		}
		return "请补全必选项：" + String.join("、", missing) + "。";
	}

	private static void addPidConsistencyWarnings(OfflineBundleDraft draft, List<String> degradation) {
		Long identityPid = firstLong(LEADING_PID, draft.jvmIdentityText());
		Long runtimePid = firstLong(TARGET_PID, draft.runtimeSnapshotText());
		if (identityPid != null && runtimePid != null && !identityPid.equals(runtimePid)) {
			degradation.add("B1 JVM identity PID (" + identityPid + ") differs from B3 runtime targetPid ("
					+ runtimePid + "); confirm the offline bundle belongs to one JVM.");
		}

		Long histogramPid = artifactLeadingPid(draft.classHistogram(), "B4 class histogram", degradation);
		Long threadDumpPid = artifactLeadingPid(draft.threadDump(), "B5 thread dump", degradation);
		if (histogramPid != null && threadDumpPid != null && !histogramPid.equals(threadDumpPid)) {
			degradation.add("B4 class histogram PID (" + histogramPid + ") differs from B5 thread dump PID ("
					+ threadDumpPid + "); confirm both artifacts came from the same JVM.");
		}
	}

	private static Long artifactLeadingPid(OfflineArtifactSource source, String label, List<String> degradation) {
		try {
			return firstLong(LEADING_PID, OfflineTextLoader.load(source));
		}
		catch (IOException ex) {
			degradation.add("Unable to read " + label + " while checking PID consistency: " + ex.getMessage());
			return null;
		}
	}

	private static Long firstLong(Pattern pattern, String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		Matcher matcher = pattern.matcher(text);
		if (!matcher.find()) {
			return null;
		}
		try {
			return Long.parseLong(matcher.group(1));
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
