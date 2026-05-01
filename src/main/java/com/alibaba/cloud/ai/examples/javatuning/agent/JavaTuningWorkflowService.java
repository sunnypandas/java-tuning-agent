package com.alibaba.cloud.ai.examples.javatuning.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.MemoryGcDiagnosisEngine;
import com.alibaba.cloud.ai.examples.javatuning.advice.SuspectedCodeHotspot;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReportFormatter;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.DiagnosisWindow;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidenceMerger;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidenceRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ResourceBudgetEvidence;
import com.alibaba.cloud.ai.examples.javatuning.source.LocalSourceHotspotFinder;
import com.alibaba.cloud.ai.examples.javatuning.source.SourceHotspotCorrelationService;

public class JavaTuningWorkflowService {

	private final JvmRuntimeCollector collector;

	private final MemoryGcDiagnosisEngine diagnosisEngine;

	private final SourceHotspotCorrelationService sourceHotspotCorrelationService;

	public JavaTuningWorkflowService(JvmRuntimeCollector collector, MemoryGcDiagnosisEngine diagnosisEngine,
			LocalSourceHotspotFinder sourceHotspotFinder) {
		this(collector, diagnosisEngine, new SourceHotspotCorrelationService(sourceHotspotFinder));
	}

	public JavaTuningWorkflowService(JvmRuntimeCollector collector, MemoryGcDiagnosisEngine diagnosisEngine,
			SourceHotspotCorrelationService sourceHotspotCorrelationService) {
		this.collector = collector;
		this.diagnosisEngine = diagnosisEngine;
		this.sourceHotspotCorrelationService = sourceHotspotCorrelationService;
	}

	public MemoryGcEvidencePack collectEvidence(MemoryGcEvidenceRequest request) {
		return collector.collectMemoryGcEvidence(request);
	}

	public TuningAdviceReport generateAdvice(TuningAdviceRequest request) {
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(request.runtimeSnapshot(), request.classHistogramHint(),
				null, List.of(), List.of(), null, null)
			.withBaselineEvidence(request.baselineEvidence())
			.withJfrSummary(request.jfrSummary())
			.withRepeatedSamplingResult(request.repeatedSamplingResult())
			.withResourceBudgetEvidence(request.resourceBudgetEvidence());
		return generateAdviceFromEvidence(evidence, request.codeContextSummary(), request.environment(),
				request.optimizationGoal());
	}

	/**
	 * Fills nullable evidence fields before diagnosis — same semantics as {@link MemoryGcEvidenceMerger}.
	 */
	public MemoryGcEvidencePack mergeEvidenceAttachments(MemoryGcEvidencePack evidence,
			RepeatedSamplingResult repeatedSamplingResult, JfrSummary jfrSummary,
			ResourceBudgetEvidence resourceBudgetEvidence, MemoryGcEvidencePack baselineEvidence) {
		return MemoryGcEvidenceMerger.merge(evidence, repeatedSamplingResult, jfrSummary, resourceBudgetEvidence,
				baselineEvidence);
	}

	/**
	 * Run diagnosis and optional source correlation on an existing evidence pack (lightweight snapshot,
	 * histogram-inclusive pack from {@link #collectEvidence}, or merged inputs).
	 */
	public TuningAdviceReport generateAdviceFromEvidence(MemoryGcEvidencePack evidence,
			CodeContextSummary codeContextSummary, String environment, String optimizationGoal) {
		evidence = ensureDiagnosisWindow(evidence);
		TuningAdviceReport base = diagnosisEngine.diagnose(evidence, codeContextSummary, environment, optimizationGoal);
		List<Path> roots = codeContextSummary.sourceRoots().stream().map(Path::of).toList();
		List<SuspectedCodeHotspot> hotspots = sourceHotspotCorrelationService.correlate(roots, evidence,
				codeContextSummary.candidatePackages());
		TuningAdviceReport merged = new TuningAdviceReport(base.findings(), base.recommendations(), hotspots,
				base.missingData(), base.nextSteps(), base.confidence(), base.confidenceReasons(), "");
		String summary = TuningAdviceReportFormatter.toMarkdown(merged);
		summary = appendDiagnosisContextSectionIfAny(evidence, summary);
		summary = appendHeapShallowSectionIfAny(evidence, summary);
		summary = appendHeapRetentionSectionIfAny(evidence, summary);
		summary = appendKeyDeltasSectionIfAny(evidence, summary);
		return new TuningAdviceReport(merged.findings(), merged.recommendations(), merged.suspectedCodeHotspots(),
				merged.missingData(), merged.nextSteps(), merged.confidence(), merged.confidenceReasons(), summary);
	}

	private static MemoryGcEvidencePack ensureDiagnosisWindow(MemoryGcEvidencePack evidence) {
		if (evidence.diagnosisWindow() != null) {
			return evidence;
		}
		DiagnosisWindow window = derivedWindow(evidence);
		return window == null ? evidence : evidence.withDiagnosisWindow(window);
	}

	private static DiagnosisWindow derivedWindow(MemoryGcEvidencePack evidence) {
		DiagnosisWindow window = null;
		if (evidence.repeatedSamplingResult() != null && !evidence.repeatedSamplingResult().samples().isEmpty()) {
			window = merge(window, windowFromRepeated(evidence.repeatedSamplingResult()));
		}
		if (evidence.jfrSummary() != null) {
			window = merge(window, windowFromJfr(evidence.jfrSummary()));
		}
		return window;
	}

	private static DiagnosisWindow merge(DiagnosisWindow current, DiagnosisWindow next) {
		if (next == null) {
			return current;
		}
		return current == null ? next : current.merge(next);
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

	private static String appendDiagnosisContextSectionIfAny(MemoryGcEvidencePack evidence, String summary) {
		DiagnosisWindow window = evidence.diagnosisWindow();
		if (window == null) {
			return summary;
		}
		StringBuilder sb = new StringBuilder(summary).append("\n\n## Diagnosis Context\n\n");
		if (!window.caseId().isBlank()) {
			sb.append("- case: ").append(window.caseId()).append('\n');
		}
		if (!window.sourceLabel().isBlank()) {
			sb.append("- source: ").append(window.sourceLabel()).append('\n');
		}
		if (window.startEpochMs() > 0L || window.endEpochMs() > 0L) {
			sb.append("- window: ").append(window.startEpochMs()).append(" -> ").append(window.endEpochMs());
			long durationMs = Math.max(0L, window.endEpochMs() - window.startEpochMs());
			if (durationMs > 0L) {
				sb.append(" (").append(durationMs / 1000L).append("s)");
			}
			sb.append('\n');
		}
		if (window.collectedAtEpochMs() > 0L) {
			sb.append("- collectedAtEpochMs: ").append(window.collectedAtEpochMs()).append('\n');
		}
		return sb.toString().trim() + '\n';
	}

	private static String appendHeapShallowSectionIfAny(MemoryGcEvidencePack evidence, String summary) {
		if (evidence.heapShallowSummary() == null) {
			return summary;
		}
		if (!evidence.heapShallowSummary().errorMessage().isEmpty()) {
			return summary + "\n\n### Heap dump shallow summary (failed)\n\n" + evidence.heapShallowSummary()
					.errorMessage();
		}
		if (evidence.heapShallowSummary().summaryMarkdown().isEmpty()) {
			return summary;
		}
		return summary + "\n\n" + evidence.heapShallowSummary().summaryMarkdown();
	}

	private static String appendHeapRetentionSectionIfAny(MemoryGcEvidencePack evidence, String summary) {
		if (evidence.heapRetentionAnalysis() == null || evidence.heapRetentionAnalysis().summaryMarkdown().isBlank()) {
			return summary;
		}
		return summary + "\n\n" + evidence.heapRetentionAnalysis().summaryMarkdown().trim();
	}

	private static String appendKeyDeltasSectionIfAny(MemoryGcEvidencePack evidence, String summary) {
		if (evidence.baselineEvidence() == null) {
			return summary;
		}
		String heapDelta = heapDeltaLine(evidence.snapshot(), evidence.baselineEvidence().snapshot());
		String gcDelta = gcDeltaLine(evidence.snapshot(), evidence.baselineEvidence().snapshot());
		String nativeDelta = nativeDeltaLine(evidence.nativeMemorySummary(), evidence.baselineEvidence().nativeMemorySummary());
		String nativeCategoryDelta = nativeCategoryDeltaLine(evidence.nativeMemorySummary(),
				evidence.baselineEvidence().nativeMemorySummary());
		return summary + "\n\n## Key Deltas\n\n- " + heapDelta + "\n- " + gcDelta + "\n- " + nativeDelta + "\n- "
				+ nativeCategoryDelta;
	}

	private static String heapDeltaLine(JvmRuntimeSnapshot current, JvmRuntimeSnapshot baseline) {
		if (current == null || baseline == null || current.memory() == null || baseline.memory() == null) {
			return "heap used: unavailable";
		}
		long deltaBytes = current.memory().heapUsedBytes() - baseline.memory().heapUsedBytes();
		return "heap used: " + humanDelta(deltaBytes) + " (" + toMiB(baseline.memory().heapUsedBytes())
				+ " MiB baseline -> " + toMiB(current.memory().heapUsedBytes()) + " MiB)";
	}

	private static String gcDeltaLine(JvmRuntimeSnapshot current, JvmRuntimeSnapshot baseline) {
		if (current == null || baseline == null || current.gc() == null || baseline.gc() == null) {
			return "GC events/time: unavailable";
		}
		long youngCountDelta = current.gc().youngGcCount() - baseline.gc().youngGcCount();
		long fullCountDelta = current.gc().fullGcCount() - baseline.gc().fullGcCount();
		long youngTimeDelta = current.gc().youngGcTimeMs() - baseline.gc().youngGcTimeMs();
		long fullTimeDelta = current.gc().fullGcTimeMs() - baseline.gc().fullGcTimeMs();
		return "GC events/time: young " + signed(youngCountDelta) + " events, full " + signed(fullCountDelta)
				+ " events; time young " + signed(youngTimeDelta) + " ms, full " + signed(fullTimeDelta) + " ms";
	}

	private static String nativeDeltaLine(NativeMemorySummary current, NativeMemorySummary baseline) {
		if (current == null || baseline == null) {
			return "native committed: unavailable";
		}
		long committedDelta = current.totalCommittedBytes() - baseline.totalCommittedBytes();
		return "native committed: " + humanDelta(committedDelta) + " (" + toMiB(baseline.totalCommittedBytes())
				+ " MiB baseline -> " + toMiB(current.totalCommittedBytes()) + " MiB)";
	}

	private static String nativeCategoryDeltaLine(NativeMemorySummary current, NativeMemorySummary baseline) {
		if (current == null) {
			return "native category growth: unavailable";
		}
		Map<String, NativeMemorySummary.CategoryGrowth> growth = Map.of();
		if (baseline != null && !current.categories().isEmpty() && !baseline.categories().isEmpty()) {
			growth = computeCategoryGrowthFromBaseline(current.categories(), baseline.categories());
		}
		if (growth.isEmpty()) {
			growth = current.categoryGrowth();
		}
		if (growth.isEmpty()) {
			return "native category growth: unavailable";
		}
		List<Map.Entry<String, NativeMemorySummary.CategoryGrowth>> ordered = growth.entrySet()
			.stream()
			.sorted(Comparator.comparingLong((Map.Entry<String, NativeMemorySummary.CategoryGrowth> entry) -> Math
				.abs(entry.getValue().committedDeltaBytes())).reversed().thenComparing(Map.Entry::getKey))
			.limit(3)
			.toList();
		List<String> lines = new ArrayList<>();
		for (Map.Entry<String, NativeMemorySummary.CategoryGrowth> entry : ordered) {
			lines.add(titleCase(entry.getKey()) + " " + humanDelta(entry.getValue().committedDeltaBytes()) + " committed ("
					+ humanDelta(entry.getValue().reservedDeltaBytes()) + " reserved)");
		}
		return "native category growth: " + String.join(", ", lines);
	}

	private static Map<String, NativeMemorySummary.CategoryGrowth> computeCategoryGrowthFromBaseline(
			Map<String, NativeMemorySummary.CategoryUsage> current, Map<String, NativeMemorySummary.CategoryUsage> baseline) {
		java.util.LinkedHashMap<String, NativeMemorySummary.CategoryGrowth> growth = new java.util.LinkedHashMap<>();
		for (Map.Entry<String, NativeMemorySummary.CategoryUsage> entry : current.entrySet()) {
			NativeMemorySummary.CategoryUsage base = baseline.get(entry.getKey());
			if (base == null) {
				continue;
			}
			long reservedDelta = entry.getValue().reservedBytes() - base.reservedBytes();
			long committedDelta = entry.getValue().committedBytes() - base.committedBytes();
			if (reservedDelta == 0L && committedDelta == 0L) {
				continue;
			}
			growth.put(entry.getKey(), new NativeMemorySummary.CategoryGrowth(reservedDelta, committedDelta));
		}
		return growth;
	}

	private static String titleCase(String value) {
		if (value == null || value.isBlank()) {
			return "Unknown";
		}
		String lower = value.toLowerCase(Locale.ROOT);
		if ("nio".equals(lower) || "gc".equals(lower) || "jit".equals(lower)) {
			return lower.toUpperCase(Locale.ROOT);
		}
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	private static String humanDelta(long bytes) {
		return signed(toMiB(bytes)) + " MiB";
	}

	private static String signed(long value) {
		return value >= 0 ? "+" + value : String.valueOf(value);
	}

	private static long toMiB(long bytes) {
		return Math.round(bytes / (1024.0d * 1024.0d));
	}
}
