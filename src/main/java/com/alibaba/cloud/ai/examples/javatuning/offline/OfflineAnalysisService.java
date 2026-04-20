package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.agent.JavaTuningWorkflowService;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;

/**
 * Validates offline drafts and produces tuning advice from imported artifacts.
 */
public class OfflineAnalysisService {

	private final OfflineDraftValidator validator;

	private final OfflineEvidenceAssembler evidenceAssembler;

	private final JavaTuningWorkflowService workflowService;

	public OfflineAnalysisService(OfflineDraftValidator validator, OfflineEvidenceAssembler evidenceAssembler,
			JavaTuningWorkflowService workflowService) {
		this.validator = validator;
		this.evidenceAssembler = evidenceAssembler;
		this.workflowService = workflowService;
	}

	public OfflineDraftValidationResult validate(OfflineBundleDraft draft, boolean proceedWithMissingRequired) {
		return validator.validate(draft, proceedWithMissingRequired);
	}

	public TuningAdviceReport generateOfflineAdvice(OfflineBundleDraft draft, CodeContextSummary codeContextSummary,
			String environment, String optimizationGoal, String confirmationToken,
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
		MemoryGcEvidencePack base = evidenceAssembler.build(draft);
		List<String> missing = new ArrayList<>(base.missingData());
		appendRecommendedAbsenceNotes(draft, missing);
		MemoryGcEvidencePack pack = new MemoryGcEvidencePack(base.snapshot(), base.classHistogram(), base.threadDump(),
				missing, base.warnings(), base.heapDumpPath(), base.heapShallowSummary());
		return workflowService.generateAdviceFromEvidence(pack, codeContextSummary, environment, optimizationGoal);
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
