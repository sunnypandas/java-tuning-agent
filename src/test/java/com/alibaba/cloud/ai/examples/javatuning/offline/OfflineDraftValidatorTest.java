package com.alibaba.cloud.ai.examples.javatuning.offline;

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

}
