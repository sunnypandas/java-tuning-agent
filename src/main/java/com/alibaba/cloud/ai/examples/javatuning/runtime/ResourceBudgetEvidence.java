package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record ResourceBudgetEvidence(Long containerMemoryLimitBytes, Long processRssBytes, Double cpuQuotaCores,
		Long heapCommittedBytes, Long heapMaxBytes, Long nativeCommittedBytes, Long estimatedThreadStackBytes,
		Long estimatedTotalCommittedBytes, List<String> warnings, List<String> missingData) {

	public ResourceBudgetEvidence {
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
		missingData = missingData == null ? List.of() : List.copyOf(missingData);
	}

	public boolean hasAnyMemorySignal() {
		return positive(containerMemoryLimitBytes) || positive(processRssBytes) || positive(heapCommittedBytes)
				|| positive(heapMaxBytes) || positive(nativeCommittedBytes) || positive(estimatedThreadStackBytes)
				|| positive(estimatedTotalCommittedBytes);
	}

	private static boolean positive(Long value) {
		return value != null && value > 0L;
	}
}
