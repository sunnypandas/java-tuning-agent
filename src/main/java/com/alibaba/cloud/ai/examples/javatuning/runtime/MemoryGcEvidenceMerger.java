package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Merges optional evidence fragments into a primary {@link MemoryGcEvidencePack} for
 * {@code generateTuningAdviceFromEvidence} and similar flows. Fills {@code null} fields only; does not replace
 * existing non-null attachments. Merges warnings, missing data, and diagnosis window coverage.
 */
public final class MemoryGcEvidenceMerger {

	private MemoryGcEvidenceMerger() {
	}

	public static MemoryGcEvidencePack merge(MemoryGcEvidencePack base, RepeatedSamplingResult repeatedSamplingResult,
			JfrSummary jfrSummary, ResourceBudgetEvidence resourceBudgetEvidence, MemoryGcEvidencePack baselineEvidence) {
		Objects.requireNonNull(base, "base evidence must not be null");
		boolean tookRepeated = base.repeatedSamplingResult() == null && repeatedSamplingResult != null;
		boolean tookJfr = base.jfrSummary() == null && jfrSummary != null;
		boolean tookResource = base.resourceBudgetEvidence() == null && resourceBudgetEvidence != null;
		boolean tookBaseline = base.baselineEvidence() == null && baselineEvidence != null;

		RepeatedSamplingResult repeatedOut = tookRepeated ? repeatedSamplingResult : base.repeatedSamplingResult();
		JfrSummary jfrOut = tookJfr ? jfrSummary : base.jfrSummary();
		ResourceBudgetEvidence resourceOut = tookResource ? resourceBudgetEvidence : base.resourceBudgetEvidence();
		MemoryGcEvidencePack baselineOut = tookBaseline ? baselineEvidence : base.baselineEvidence();

		List<String> extraWarnings = new ArrayList<>();
		List<String> extraMissing = new ArrayList<>();
		if (tookRepeated) {
			extraWarnings.addAll(repeatedSamplingResult.warnings());
			extraMissing.addAll(repeatedSamplingResult.missingData());
		}
		if (tookJfr) {
			extraWarnings.addAll(jfrSummary.parserWarnings());
		}
		if (tookResource) {
			extraWarnings.addAll(resourceBudgetEvidence.warnings());
			extraMissing.addAll(resourceBudgetEvidence.missingData());
		}
		if (tookBaseline) {
			extraWarnings.addAll(baselineEvidence.warnings());
			extraMissing.addAll(baselineEvidence.missingData());
		}

		List<String> mergedWarnings = dedupeOrdered(base.warnings(), extraWarnings);
		List<String> mergedMissing = dedupeOrdered(base.missingData(), extraMissing);

		DiagnosisWindow mergedWindow = mergeDiagnosisWindow(base, repeatedOut, jfrOut);

		return new MemoryGcEvidencePack(base.snapshot(), base.classHistogram(), base.threadDump(), mergedMissing,
				mergedWarnings, base.heapDumpPath(), base.heapShallowSummary(), base.nativeMemorySummary(),
				base.heapRetentionAnalysis(), repeatedOut, base.gcLogSummary(), jfrOut, baselineOut, mergedWindow,
				resourceOut);
	}

	private static DiagnosisWindow mergeDiagnosisWindow(MemoryGcEvidencePack base, RepeatedSamplingResult repeated,
			JfrSummary jfr) {
		DiagnosisWindow window = base.diagnosisWindow();
		if (base.snapshot() != null) {
			window = mergeWindows(window, DiagnosisWindow.fromSnapshot(base.snapshot(), "live"));
		}
		if (repeated != null) {
			window = mergeWindows(window, windowFromRepeated(repeated));
		}
		if (jfr != null) {
			window = mergeWindows(window, windowFromJfr(jfr));
		}
		return window;
	}

	private static DiagnosisWindow mergeWindows(DiagnosisWindow left, DiagnosisWindow right) {
		if (right == null) {
			return left;
		}
		if (left == null) {
			return right;
		}
		return left.merge(right);
	}

	private static DiagnosisWindow windowFromRepeated(RepeatedSamplingResult repeated) {
		long startedAt = repeated.startedAtEpochMs();
		long endedAt = repeated.elapsedMs() > 0L && startedAt > 0L ? startedAt + repeated.elapsedMs() : startedAt;
		return new DiagnosisWindow("", "repeated-sampling", startedAt, endedAt, endedAt);
	}

	private static DiagnosisWindow windowFromJfr(JfrSummary jfr) {
		long start = jfr.recordingStartEpochMs() == null ? 0L : jfr.recordingStartEpochMs();
		long end = jfr.recordingEndEpochMs() == null ? 0L : jfr.recordingEndEpochMs();
		if (end <= 0L && start > 0L && jfr.durationMs() != null) {
			end = start + jfr.durationMs();
		}
		return new DiagnosisWindow("", "jfr", start, end, end);
	}

	private static List<String> dedupeOrdered(List<String> primary, List<String> additional) {
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		List<String> out = new ArrayList<>();
		for (String s : primary) {
			if (s == null) {
				continue;
			}
			if (seen.add(s)) {
				out.add(s);
			}
		}
		for (String s : additional) {
			if (s == null) {
				continue;
			}
			if (seen.add(s)) {
				out.add(s);
			}
		}
		return List.copyOf(out);
	}

}
