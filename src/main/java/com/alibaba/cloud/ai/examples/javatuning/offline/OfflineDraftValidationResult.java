package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.List;

public record OfflineDraftValidationResult(List<String> missingRequired, List<String> degradationWarnings,
		String nextPromptZh, boolean allowedToProceed, int suggestedStepIndex) {

	public OfflineDraftValidationResult {
		missingRequired = List.copyOf(missingRequired);
		degradationWarnings = List.copyOf(degradationWarnings);
	}

}
