package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record ClassHistogramSummary(List<ClassHistogramEntry> entries, long totalInstances, long totalBytes) {

	public ClassHistogramSummary {
		entries = List.copyOf(entries);
	}

}
