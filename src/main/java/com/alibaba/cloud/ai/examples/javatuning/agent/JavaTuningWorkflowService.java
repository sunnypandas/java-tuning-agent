package com.alibaba.cloud.ai.examples.javatuning.agent;

import java.nio.file.Path;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.MemoryGcDiagnosisEngine;
import com.alibaba.cloud.ai.examples.javatuning.advice.SuspectedCodeHotspot;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReportFormatter;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidenceRequest;
import com.alibaba.cloud.ai.examples.javatuning.source.LocalSourceHotspotFinder;

public class JavaTuningWorkflowService {

	private final JvmRuntimeCollector collector;

	private final MemoryGcDiagnosisEngine diagnosisEngine;

	private final LocalSourceHotspotFinder sourceHotspotFinder;

	public JavaTuningWorkflowService(JvmRuntimeCollector collector, MemoryGcDiagnosisEngine diagnosisEngine,
			LocalSourceHotspotFinder sourceHotspotFinder) {
		this.collector = collector;
		this.diagnosisEngine = diagnosisEngine;
		this.sourceHotspotFinder = sourceHotspotFinder;
	}

	public MemoryGcEvidencePack collectEvidence(MemoryGcEvidenceRequest request) {
		return collector.collectMemoryGcEvidence(request);
	}

	public TuningAdviceReport generateAdvice(TuningAdviceRequest request) {
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(request.runtimeSnapshot(), request.classHistogramHint(),
				null, List.of(), List.of(), null, null);
		return generateAdviceFromEvidence(evidence, request.codeContextSummary(), request.environment(),
				request.optimizationGoal());
	}

	/**
	 * Run diagnosis and optional source correlation on an existing evidence pack (lightweight snapshot,
	 * histogram-inclusive pack from {@link #collectEvidence}, or merged inputs).
	 */
	public TuningAdviceReport generateAdviceFromEvidence(MemoryGcEvidencePack evidence,
			CodeContextSummary codeContextSummary, String environment, String optimizationGoal) {
		TuningAdviceReport base = diagnosisEngine.diagnose(evidence, codeContextSummary, environment, optimizationGoal);
		List<Path> roots = codeContextSummary.sourceRoots().stream().map(Path::of).toList();
		List<SuspectedCodeHotspot> hotspots = sourceHotspotFinder.hotspotsFromHistogram(roots,
				evidence.classHistogram(), codeContextSummary.candidatePackages());
		TuningAdviceReport merged = new TuningAdviceReport(base.findings(), base.recommendations(), hotspots,
				base.missingData(), base.nextSteps(), base.confidence(), base.confidenceReasons(), "");
		String summary = TuningAdviceReportFormatter.toMarkdown(merged);
		summary = appendHeapShallowSectionIfAny(evidence, summary);
		summary = appendHeapRetentionSectionIfAny(evidence, summary);
		return new TuningAdviceReport(merged.findings(), merged.recommendations(), merged.suspectedCodeHotspots(),
				merged.missingData(), merged.nextSteps(), merged.confidence(), merged.confidenceReasons(), summary);
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
}
