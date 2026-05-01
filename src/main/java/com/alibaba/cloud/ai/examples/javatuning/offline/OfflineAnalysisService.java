package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.agent.JavaTuningWorkflowService;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

/**
 * Validates offline drafts and produces tuning advice from imported artifacts.
 */
public class OfflineAnalysisService {

	private final OfflineDraftValidator validator;

	private final OfflineEvidenceAssembler evidenceAssembler;

	private final HeapRetentionAnalyzer heapRetentionAnalyzer;

	private final JavaTuningWorkflowService workflowService;

	private final OfflineTargetConsistencyAnalyzer targetConsistencyAnalyzer;

	public OfflineAnalysisService(OfflineDraftValidator validator, OfflineEvidenceAssembler evidenceAssembler,
			HeapRetentionAnalyzer heapRetentionAnalyzer, JavaTuningWorkflowService workflowService) {
		this(validator, evidenceAssembler, heapRetentionAnalyzer, workflowService, new OfflineTargetConsistencyAnalyzer());
	}

	OfflineAnalysisService(OfflineDraftValidator validator, OfflineEvidenceAssembler evidenceAssembler,
			HeapRetentionAnalyzer heapRetentionAnalyzer, JavaTuningWorkflowService workflowService,
			OfflineTargetConsistencyAnalyzer targetConsistencyAnalyzer) {
		this.validator = validator;
		this.evidenceAssembler = evidenceAssembler;
		this.heapRetentionAnalyzer = heapRetentionAnalyzer;
		this.workflowService = workflowService;
		this.targetConsistencyAnalyzer = targetConsistencyAnalyzer;
	}

	public OfflineDraftValidationResult validate(OfflineBundleDraft draft, boolean proceedWithMissingRequired) {
		return validator.validate(draft, proceedWithMissingRequired);
	}

	public TuningAdviceReport generateOfflineAdvice(OfflineBundleDraft draft, CodeContextSummary codeContextSummary,
			String environment, String optimizationGoal, String analysisDepth, String confirmationToken,
			boolean proceedWithMissingRequired) {
		OfflineDraftValidationResult validation = validator.validate(draft, proceedWithMissingRequired);
		if (!validation.allowedToProceed()) {
			throw new IllegalArgumentException(
					"离线草稿未通过校验：缺失必选项 " + validation.missingRequired() + "。可将 proceedWithMissingRequired 设为 true 以降级继续，或使用 validateOfflineAnalysisDraft 查看详情。");
		}
		if (!offlinePrivilegedConsentOk(draft, confirmationToken)) {
			throw new IllegalArgumentException(
					"使用导入的类直方图、线程栈或堆转储进行分析需要非空的 confirmationToken（与在线模式特权采集相同语义）。");
		}
		CodeContextSummary ctx = codeContextSummary == null ? CodeContextSummary.empty() : codeContextSummary;
		OfflineTargetConsistencyResult targetConsistency = targetConsistencyAnalyzer.analyze(draft, ctx);
		MemoryGcEvidencePack base = evidenceAssembler.build(draft);
		List<String> missing = new ArrayList<>(base.missingData());
		List<String> warnings = new ArrayList<>(base.warnings());
		warnings.addAll(targetConsistency.warnings());
		if (!targetConsistency.targetMatched()) {
			missing.add("offlineTargetConsistency");
		}
		appendRecommendedAbsenceNotes(draft, missing);
		HeapRetentionAnalysisResult heapRetentionAnalysis = null;
		String normalizedDepth = normalizeAnalysisDepth(analysisDepth);
		String heapDumpPath = base.heapDumpPath();
		if ("deep".equals(normalizedDepth)) {
			if (heapDumpPath == null || heapDumpPath.isBlank()) {
				missing.add("heapRetentionAnalysis");
				warnings.add(
						"Deep offline advice requested but heapDumpAbsolutePath is blank; retention evidence was skipped.");
			}
			else {
				HeapRetentionAnalysisResult retention = heapRetentionAnalyzer.analyze(Path.of(heapDumpPath.trim()), null,
						null, "deep", List.of(), ctx.candidatePackages());
				warnings.addAll(retention.warnings());
				if (retention.analysisSucceeded()) {
					heapRetentionAnalysis = retention;
				}
				else {
					missing.add("heapRetentionAnalysis");
					String failureMessage = retention.errorMessage().isBlank()
							? "Deep retention analysis failed without an error message."
							: "Deep retention analysis failed: " + retention.errorMessage();
					warnings.add(failureMessage);
				}
			}
		}
		MemoryGcEvidencePack pack = new MemoryGcEvidencePack(base.snapshot(), base.classHistogram(), base.threadDump(),
				missing, warnings, heapDumpPath, base.heapShallowSummary(), heapRetentionAnalysis)
			.withGcLogSummary(base.gcLogSummary())
			.withNativeMemorySummary(base.nativeMemorySummary())
			.withRepeatedSamplingResult(base.repeatedSamplingResult())
			.withResourceBudgetEvidence(base.resourceBudgetEvidence())
			.withDiagnosisWindow(base.diagnosisWindow());
		return workflowService.generateAdviceFromEvidence(pack, ctx, environment, optimizationGoal);
	}

	private static boolean offlinePrivilegedConsentOk(OfflineBundleDraft draft, String confirmationToken) {
		boolean usesHistogram = draft.classHistogram().isPresent();
		boolean usesThreadDump = draft.threadDump().isPresent();
		boolean usesHeap = draft.heapDumpAbsolutePath() != null && !draft.heapDumpAbsolutePath().isBlank();
		boolean privileged = usesHistogram || usesThreadDump || usesHeap;
		if (!privileged) {
			return true;
		}
		return confirmationToken != null && !confirmationToken.isBlank();
	}

	private static String normalizeAnalysisDepth(String analysisDepth) {
		if (analysisDepth == null || analysisDepth.isBlank()) {
			return "balanced";
		}
		return analysisDepth.trim().toLowerCase(Locale.ROOT);
	}

	private static void appendRecommendedAbsenceNotes(OfflineBundleDraft draft, List<String> missing) {
		if (draft.explicitlyNoGcLog()) {
			missing.add("recommended: gcLog explicitly marked absent for this run");
		}
		if (draft.explicitlyNoAppLog()) {
			missing.add("recommended: appLog explicitly marked absent for this run");
		}
		if (draft.explicitlyNoRepeatedSamples()) {
			missing.add("recommended: repeatedSamples explicitly marked absent for this run");
		}
	}

}
