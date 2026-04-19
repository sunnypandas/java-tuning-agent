package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.ArrayList;
import java.util.List;

public class OfflineDraftValidator {

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

}
