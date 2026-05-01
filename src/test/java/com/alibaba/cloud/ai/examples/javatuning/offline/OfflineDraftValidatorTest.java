package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineDraftValidatorTest {

	private final OfflineDraftValidator validator = new OfflineDraftValidator();

	@Test
	void emptyDraft_lists_required_ids_and_blocks_until_degradation_allowed() {
		OfflineBundleDraft draft = OfflineBundleDraft.empty();
		OfflineDraftValidationResult r = validator.validate(draft, false);
		assertThat(r.missingRequired()).isNotEmpty();
		assertThat(r.allowedToProceed()).isFalse();
		assertThat(r.nextPromptZh()).contains("必选");
	}

	@Test
	void proceedWithDegradation_true_allows_continue_despite_gaps() {
		OfflineBundleDraft draft = OfflineBundleDraft.empty();
		OfflineDraftValidationResult r = validator.validate(draft, true);
		assertThat(r.allowedToProceed()).isTrue();
		assertThat(r.degradationWarnings()).isNotEmpty();
		assertThat(r.degradationWarnings()).anyMatch(w -> w.contains("nativeMemorySummary"));
	}

	@Test
	void warnsWhenIdentityPidDiffersFromRuntimeTargetPid() {
		OfflineBundleDraft draft = completeDraft("1961:", "targetPid: 98662", "1961:", "1961:");

		OfflineDraftValidationResult r = validator.validate(draft, false);

		assertThat(r.allowedToProceed()).isTrue();
		assertThat(r.degradationWarnings()).anyMatch(w -> w.contains("B1") && w.contains("B3")
				&& w.contains("1961") && w.contains("98662"));
	}

	@Test
	void warnsWhenHistogramPidDiffersFromThreadDumpPid() {
		OfflineBundleDraft draft = completeDraft("1961:", "targetPid: 1961", "1961:", "98662:");

		OfflineDraftValidationResult r = validator.validate(draft, false);

		assertThat(r.allowedToProceed()).isTrue();
		assertThat(r.degradationWarnings()).anyMatch(w -> w.contains("B4") && w.contains("B5")
				&& w.contains("1961") && w.contains("98662"));
	}

	private static OfflineBundleDraft completeDraft(String identityText, String runtimeText, String histogramText,
			String threadDumpText) {
		return new OfflineBundleDraft(identityText, "JDK 25.0.3", runtimeText,
				new OfflineArtifactSource(null, histogramText), new OfflineArtifactSource(null, threadDumpText),
				"/tmp/demo.hprof", true, true, false, null, null, null, "", "", "/tmp/repeated.json", Map.of());
	}

}
