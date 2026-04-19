package com.alibaba.cloud.ai.examples.javatuning.runtime;

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
		if (request.includeHeapDump() && request.heapDumpOutputPath().isBlank()) {
			throw new IllegalArgumentException("heapDumpOutputPath is required when includeHeapDump is true");
		}
	}

}
