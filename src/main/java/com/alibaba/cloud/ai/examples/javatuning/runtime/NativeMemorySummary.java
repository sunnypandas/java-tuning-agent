package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;
import java.util.Map;

public record NativeMemorySummary(long totalReservedBytes, long totalCommittedBytes, Long directReservedBytes,
		Long directCommittedBytes, Long classReservedBytes, Long classCommittedBytes,
		Map<String, CategoryUsage> categories, Map<String, CategoryGrowth> categoryGrowth, List<String> warnings) {

	public NativeMemorySummary {
		categories = categories == null ? Map.of() : Map.copyOf(categories);
		categoryGrowth = categoryGrowth == null ? Map.of() : Map.copyOf(categoryGrowth);
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
	}

	public NativeMemorySummary(long totalReservedBytes, long totalCommittedBytes, Long directReservedBytes,
			Long directCommittedBytes, Long classReservedBytes, Long classCommittedBytes, List<String> warnings) {
		this(totalReservedBytes, totalCommittedBytes, directReservedBytes, directCommittedBytes, classReservedBytes,
				classCommittedBytes, Map.of(), Map.of(), warnings);
	}

	public boolean hasTotals() {
		return totalReservedBytes > 0L || totalCommittedBytes > 0L;
	}

	public record CategoryUsage(long reservedBytes, long committedBytes) {
	}

	public record CategoryGrowth(long reservedDeltaBytes, long committedDeltaBytes) {
	}

}
