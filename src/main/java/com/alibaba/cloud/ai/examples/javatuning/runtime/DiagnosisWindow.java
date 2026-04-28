package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record DiagnosisWindow(String caseId, String sourceLabel, long startEpochMs, long endEpochMs,
		long collectedAtEpochMs) {

	public DiagnosisWindow {
		caseId = caseId == null ? "" : caseId.trim();
		sourceLabel = sourceLabel == null ? "" : sourceLabel.trim();
		if (endEpochMs > 0L && startEpochMs > 0L && endEpochMs < startEpochMs) {
			long previousStart = startEpochMs;
			startEpochMs = endEpochMs;
			endEpochMs = previousStart;
		}
	}

	public static DiagnosisWindow fromSnapshot(JvmRuntimeSnapshot snapshot, String sourceLabel) {
		if (snapshot == null || snapshot.collectionMetadata() == null) {
			return null;
		}
		long collectedAt = snapshot.collectionMetadata().collectedAtEpochMs();
		long elapsed = Math.max(0L, snapshot.collectionMetadata().elapsedMs());
		long start = collectedAt > 0L ? collectedAt : 0L;
		long end = start > 0L ? start + elapsed : 0L;
		return new DiagnosisWindow("", sourceLabel, start, end, collectedAt);
	}

	public DiagnosisWindow merge(DiagnosisWindow other) {
		if (other == null) {
			return this;
		}
		long start = minPositive(startEpochMs, other.startEpochMs());
		long end = Math.max(endEpochMs, other.endEpochMs());
		long collected = Math.max(collectedAtEpochMs, other.collectedAtEpochMs());
		String mergedCase = !caseId.isBlank() ? caseId : other.caseId();
		String mergedSource = mergeLabel(sourceLabel, other.sourceLabel());
		return new DiagnosisWindow(mergedCase, mergedSource, start, end, collected);
	}

	private static long minPositive(long left, long right) {
		if (left <= 0L) {
			return Math.max(0L, right);
		}
		if (right <= 0L) {
			return left;
		}
		return Math.min(left, right);
	}

	private static String mergeLabel(String left, String right) {
		if (left == null || left.isBlank()) {
			return right == null ? "" : right;
		}
		if (right == null || right.isBlank() || left.equals(right)) {
			return left;
		}
		return left + "," + right;
	}
}
