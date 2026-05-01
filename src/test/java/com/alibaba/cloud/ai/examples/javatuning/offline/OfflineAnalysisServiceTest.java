package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.agent.JavaTuningWorkflowService;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OfflineAnalysisServiceTest {

	@Test
	void generateOfflineAdviceCarriesTargetMismatchWarningIntoEvidencePack() {
		OfflineDraftValidator validator = mock(OfflineDraftValidator.class);
		OfflineEvidenceAssembler assembler = mock(OfflineEvidenceAssembler.class);
		HeapRetentionAnalyzer retentionAnalyzer = mock(HeapRetentionAnalyzer.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);
		OfflineAnalysisService service = new OfflineAnalysisService(validator, assembler, retentionAnalyzer, workflowService);
		OfflineBundleDraft draft = wrongTargetDraft();
		CodeContextSummary context = new CodeContextSummary(List.of(), Map.of(),
				List.of("MemoryLeakDemoApplication", "memory-leak-demo"), List.of(),
				List.of("com.alibaba.cloud.ai.compat.memoryleakdemo"));
		MemoryGcEvidencePack basePack = new MemoryGcEvidencePack(stubSnapshot(98662L), null, null, List.of(), List.of(),
				null, null);
		TuningAdviceReport advice = new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(),
				"medium", List.of("stub"), "summary");

		given(validator.validate(draft, false)).willReturn(new OfflineDraftValidationResult(List.of(), List.of(), "",
				true, 6));
		given(assembler.build(draft)).willReturn(basePack);
		given(workflowService.generateAdviceFromEvidence(any(MemoryGcEvidencePack.class), eq(context), eq("local"),
				eq("diagnose"))).willReturn(advice);

		service.generateOfflineAdvice(draft, context, "local", "diagnose", "balanced", "approved", false);

		ArgumentCaptor<MemoryGcEvidencePack> packCaptor = ArgumentCaptor.forClass(MemoryGcEvidencePack.class);
		verify(workflowService).generateAdviceFromEvidence(packCaptor.capture(), eq(context), eq("local"),
				eq("diagnose"));
		assertThat(packCaptor.getValue().warnings()).anyMatch(warning -> warning.contains("java_command")
				&& warning.contains("JavaTuningAgentApplication") && warning.contains("MemoryLeakDemoApplication"));
		assertThat(packCaptor.getValue().missingData()).contains("offlineTargetConsistency");
	}

	private static OfflineBundleDraft wrongTargetDraft() {
		return new OfflineBundleDraft("""
				98662:
				java_command: com.alibaba.cloud.ai.examples.javatuning.JavaTuningAgentApplication
				""", "JDK 25.0.3", """
				targetPid: 98662
				sun.rt.javaCommand="com.alibaba.cloud.ai.examples.javatuning.JavaTuningAgentApplication"
				""", new OfflineArtifactSource(null, "98662:\nclass histogram"),
				new OfflineArtifactSource(null, "98662:\nthread dump"), "", true, true, false, null, null, null, "",
				"", "/tmp/repeated.json", Map.of());
	}

	private static JvmRuntimeSnapshot stubSnapshot(long pid) {
		return new JvmRuntimeSnapshot(pid, new JvmMemorySnapshot(0L, 0L, 0L, null, null, null, null, null),
				new JvmGcSnapshot("unknown", 0L, 0L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
	}

}
