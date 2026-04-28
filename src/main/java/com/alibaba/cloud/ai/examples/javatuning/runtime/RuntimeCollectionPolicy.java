package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

public final class RuntimeCollectionPolicy {

	public static RuntimeCollectionPolicy safeReadonly() {
		return new RuntimeCollectionPolicy();
	}

	public static CollectionRequest safeReadonlyRequest() {
		return new CollectionRequest(false, false, false, false, null);
	}

	public record CollectionRequest(boolean includeThreadDump, boolean includeClassHistogram, boolean includeJfr,
			boolean includeHeapDump, String confirmationToken) {

		public static CollectionRequest safeReadonly() {
			return new CollectionRequest(false, false, false, false, null);
		}

	}

	void validate(CollectionRequest request) {
		boolean privileged = request.includeThreadDump() || request.includeClassHistogram() || request.includeJfr()
				|| request.includeHeapDump();
		if (privileged && (request.confirmationToken() == null || request.confirmationToken().isBlank())) {
			throw new IllegalArgumentException("Privileged diagnostics require confirmationToken");
		}
	}

	void validate(MemoryGcEvidenceRequest request) {
		validate(new CollectionRequest(request.includeThreadDump(), request.includeClassHistogram(), false,
				request.includeHeapDump(), request.confirmationToken()));
		if (request.includeHeapDump()) {
			validateHeapDumpOutputPath(request.heapDumpOutputPath());
		}
	}

	private void validateHeapDumpOutputPath(String heapDumpOutputPath) {
		if (heapDumpOutputPath == null || heapDumpOutputPath.isBlank()) {
			throw new IllegalArgumentException("heapDumpOutputPath is required when includeHeapDump is true");
		}
		Path rawPath = Path.of(heapDumpOutputPath.trim());
		if (!rawPath.isAbsolute()) {
			throw new IllegalArgumentException("heapDumpOutputPath must be absolute");
		}
		Path output = rawPath.normalize();
		if (!output.toString().toLowerCase().endsWith(".hprof")) {
			throw new IllegalArgumentException("heapDumpOutputPath must end in .hprof");
		}
		Path parent = output.getParent();
		if (parent == null || !Files.isDirectory(parent)) {
			throw new IllegalArgumentException("heapDumpOutputPath parent directory must already exist");
		}
		if (Files.exists(output)) {
			throw new IllegalArgumentException("heapDumpOutputPath already exists: " + output);
		}
	}

}
