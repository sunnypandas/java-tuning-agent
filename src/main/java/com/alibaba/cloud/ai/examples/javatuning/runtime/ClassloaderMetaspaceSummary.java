package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.Comparator;
import java.util.List;

public record ClassloaderMetaspaceSummary(List<ClassloaderMetaspaceEntry> entries, long totalClassCount,
		long totalBytes, List<String> warnings) {

	public ClassloaderMetaspaceSummary {
		entries = entries == null ? List.of() : List.copyOf(entries);
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
	}

	public List<ClassloaderMetaspaceEntry> topByClassCount(int limit) {
		return entries.stream()
			.sorted(Comparator.comparingLong(ClassloaderMetaspaceSummary::classCountValue).reversed())
			.limit(Math.max(0, limit))
			.toList();
	}

	public List<ClassloaderMetaspaceEntry> topByBytes(int limit) {
		return entries.stream()
			.sorted(Comparator.comparingLong(ClassloaderMetaspaceSummary::bytesValue).reversed())
			.limit(Math.max(0, limit))
			.toList();
	}

	private static long classCountValue(ClassloaderMetaspaceEntry entry) {
		return entry.classCount() == null ? 0L : entry.classCount();
	}

	private static long bytesValue(ClassloaderMetaspaceEntry entry) {
		return entry.bytes() == null ? 0L : entry.bytes();
	}

}
