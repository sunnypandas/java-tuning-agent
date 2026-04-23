package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.agent.JavaTuningWorkflowService;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapDumpChunkRepository;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapRetentionAnalyzer;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineAnalysisService;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineArtifactSource;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineBundleDraft;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineDraftValidationResult;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineDraftValidator;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineEvidenceAssembler;
import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapDumpSummarizer;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OfflineMcpToolsRetentionTest {

	@Test
	void generateOfflineTuningAdviceDeepRequestsRetentionAnalyzer() {
		HeapRetentionAnalyzer analyzer = mock(HeapRetentionAnalyzer.class);
		OfflineDraftValidator validator = mock(OfflineDraftValidator.class);
		OfflineEvidenceAssembler assembler = mock(OfflineEvidenceAssembler.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);
		OfflineAnalysisService service = new OfflineAnalysisService(validator, assembler, analyzer, workflowService);
		OfflineMcpTools tools = new OfflineMcpTools(service, mock(HeapDumpChunkRepository.class),
				mock(SharkHeapDumpSummarizer.class), analyzer);
		OfflineBundleDraft draft = draftWithHeap("C:/tmp/demo.hprof");
		CodeContextSummary ctx = new CodeContextSummary(List.of(), Map.of(), List.of(), List.of(), List.of("com.demo"));
		MemoryGcEvidencePack basePack = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(),
				"C:/tmp/demo.hprof", null);
		HeapRetentionAnalysisResult retentionResult = new HeapRetentionAnalysisResult(true, "dominator-style",
				List.of("retention warning"), "", sampleSummary(), "retention markdown");
		TuningAdviceReport advice = new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(),
				"high", List.of("ok"), "summary");

		given(validator.validate(draft, true)).willReturn(new OfflineDraftValidationResult(List.of(), List.of(), "", true,
				6));
		given(assembler.build(draft)).willReturn(basePack);
		given(analyzer.analyze(Path.of("C:/tmp/demo.hprof"), null, null, "deep", List.of(), List.of("com.demo")))
			.willReturn(retentionResult);
		ArgumentCaptor<MemoryGcEvidencePack> packCaptor = ArgumentCaptor.forClass(MemoryGcEvidencePack.class);
		given(workflowService.generateAdviceFromEvidence(any(MemoryGcEvidencePack.class), eq(ctx), eq("local"),
				eq("diagnose"))).willReturn(advice);

		TuningAdviceReport report = tools.generateOfflineTuningAdvice(ctx, draft, "local", "diagnose", "deep",
				"approved", true);

		assertThat(report.confidence()).isEqualTo("high");
		verify(workflowService).generateAdviceFromEvidence(packCaptor.capture(), eq(ctx), eq("local"),
				eq("diagnose"));
		assertThat(packCaptor.getValue().heapRetentionAnalysis()).isEqualTo(retentionResult);
		verify(analyzer).analyze(Path.of("C:/tmp/demo.hprof"), null, null, "deep", List.of(), List.of("com.demo"));
	}

	@Test
	void generateOfflineTuningAdviceBalancedSkipsRetentionAnalyzer() {
		HeapRetentionAnalyzer analyzer = mock(HeapRetentionAnalyzer.class);
		OfflineDraftValidator validator = mock(OfflineDraftValidator.class);
		OfflineEvidenceAssembler assembler = mock(OfflineEvidenceAssembler.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);
		OfflineAnalysisService service = new OfflineAnalysisService(validator, assembler, analyzer, workflowService);
		OfflineMcpTools tools = new OfflineMcpTools(service, mock(HeapDumpChunkRepository.class),
				mock(SharkHeapDumpSummarizer.class), analyzer);
		OfflineBundleDraft draft = draftWithHeap("C:/tmp/demo.hprof");
		CodeContextSummary ctx = new CodeContextSummary(List.of(), Map.of(), List.of(), List.of(), List.of("com.demo"));
		MemoryGcEvidencePack basePack = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(),
				"C:/tmp/demo.hprof", null);
		TuningAdviceReport advice = new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(),
				"high", List.of("ok"), "summary");

		given(validator.validate(draft, true)).willReturn(new OfflineDraftValidationResult(List.of(), List.of(), "", true,
				6));
		given(assembler.build(draft)).willReturn(basePack);
		given(workflowService.generateAdviceFromEvidence(any(MemoryGcEvidencePack.class), eq(ctx), eq("local"),
				eq("diagnose"))).willReturn(advice);

		TuningAdviceReport report = tools.generateOfflineTuningAdvice(ctx, draft, "local", "diagnose", null,
				"approved", true);

		assertThat(report.confidence()).isEqualTo("high");
		ArgumentCaptor<MemoryGcEvidencePack> packCaptor = ArgumentCaptor.forClass(MemoryGcEvidencePack.class);
		verify(workflowService).generateAdviceFromEvidence(packCaptor.capture(), eq(ctx), eq("local"),
				eq("diagnose"));
		assertThat(packCaptor.getValue().heapRetentionAnalysis()).isNull();
		verify(analyzer, never()).analyze(any(), any(), any(), anyString(), any(), any());
	}

	@Test
	void generateOfflineTuningAdviceDeepWithoutHeapSkipsRetentionAnalyzerAndWarns() {
		HeapRetentionAnalyzer analyzer = mock(HeapRetentionAnalyzer.class);
		OfflineDraftValidator validator = mock(OfflineDraftValidator.class);
		OfflineEvidenceAssembler assembler = mock(OfflineEvidenceAssembler.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);
		OfflineAnalysisService service = new OfflineAnalysisService(validator, assembler, analyzer, workflowService);
		OfflineMcpTools tools = new OfflineMcpTools(service, mock(HeapDumpChunkRepository.class),
				mock(SharkHeapDumpSummarizer.class), analyzer);
		OfflineBundleDraft draft = draftWithHeap("");
		CodeContextSummary ctx = new CodeContextSummary(List.of(), Map.of(), List.of(), List.of(), List.of("com.demo"));
		MemoryGcEvidencePack basePack = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(),
				null, null);
		TuningAdviceReport advice = new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(),
				"high", List.of("ok"), "summary");

		given(validator.validate(draft, true)).willReturn(new OfflineDraftValidationResult(List.of(), List.of(), "", true,
				6));
		given(assembler.build(draft)).willReturn(basePack);
		given(workflowService.generateAdviceFromEvidence(any(MemoryGcEvidencePack.class), eq(ctx), eq("local"),
				eq("diagnose"))).willReturn(advice);

		TuningAdviceReport report = tools.generateOfflineTuningAdvice(ctx, draft, "local", "diagnose", "deep",
				"   ", true);

		assertThat(report.confidence()).isEqualTo("high");
		ArgumentCaptor<MemoryGcEvidencePack> packCaptor = ArgumentCaptor.forClass(MemoryGcEvidencePack.class);
		verify(workflowService).generateAdviceFromEvidence(packCaptor.capture(), eq(ctx), eq("local"),
				eq("diagnose"));
		assertThat(packCaptor.getValue().heapRetentionAnalysis()).isNull();
		assertThat(packCaptor.getValue().missingData()).contains("heapRetentionAnalysis");
		assertThat(packCaptor.getValue().warnings()).anyMatch(w -> w.contains("retention evidence was skipped"));
		verify(analyzer, never()).analyze(any(), any(), any(), anyString(), any(), any());
	}

	@Test
	void generateOfflineTuningAdviceDeepWithFailedRetentionMarksMissingData() {
		HeapRetentionAnalyzer analyzer = mock(HeapRetentionAnalyzer.class);
		OfflineDraftValidator validator = mock(OfflineDraftValidator.class);
		OfflineEvidenceAssembler assembler = mock(OfflineEvidenceAssembler.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);
		OfflineAnalysisService service = new OfflineAnalysisService(validator, assembler, analyzer, workflowService);
		OfflineMcpTools tools = new OfflineMcpTools(service, mock(HeapDumpChunkRepository.class),
				mock(SharkHeapDumpSummarizer.class), analyzer);
		OfflineBundleDraft draft = draftWithHeap("C:/tmp/missing.hprof");
		CodeContextSummary ctx = new CodeContextSummary(List.of(), Map.of(), List.of(), List.of(), List.of("com.demo"));
		MemoryGcEvidencePack basePack = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(),
				"C:/tmp/missing.hprof", null);
		HeapRetentionAnalysisResult failedRetention = new HeapRetentionAnalysisResult(false, "dominator-style",
				List.of("analyzer warning"), "file missing", sampleSummary(), "");
		TuningAdviceReport advice = new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(),
				"medium", List.of("ok"), "summary");

		given(validator.validate(draft, true)).willReturn(new OfflineDraftValidationResult(List.of(), List.of(), "", true,
				6));
		given(assembler.build(draft)).willReturn(basePack);
		given(analyzer.analyze(Path.of("C:/tmp/missing.hprof"), null, null, "deep", List.of(),
				List.of("com.demo"))).willReturn(failedRetention);
		given(workflowService.generateAdviceFromEvidence(any(MemoryGcEvidencePack.class), eq(ctx), eq("local"),
				eq("diagnose"))).willReturn(advice);

		TuningAdviceReport report = tools.generateOfflineTuningAdvice(ctx, draft, "local", "diagnose", "deep",
				"approved", true);

		assertThat(report.confidence()).isEqualTo("medium");
		ArgumentCaptor<MemoryGcEvidencePack> packCaptor = ArgumentCaptor.forClass(MemoryGcEvidencePack.class);
		verify(workflowService).generateAdviceFromEvidence(packCaptor.capture(), eq(ctx), eq("local"),
				eq("diagnose"));
		assertThat(packCaptor.getValue().heapRetentionAnalysis()).isNull();
		assertThat(packCaptor.getValue().missingData()).contains("heapRetentionAnalysis");
		assertThat(packCaptor.getValue().warnings()).anyMatch(w -> w.contains("Deep retention analysis failed"));
		assertThat(packCaptor.getValue().warnings()).anyMatch(w -> w.contains("analyzer warning"));
		verify(analyzer).analyze(Path.of("C:/tmp/missing.hprof"), null, null, "deep", List.of(), List.of("com.demo"));
	}

	@Test
	void analyzeOfflineHeapRetentionDelegatesToAnalyzer() {
		HeapRetentionAnalyzer analyzer = mock(HeapRetentionAnalyzer.class);
		given(analyzer.analyze(any(), eq(12), eq(8000), eq("balanced"), eq(List.of("byte[]")),
				eq(List.of("com.demo")))).willReturn(new HeapRetentionAnalysisResult(true, "shark", List.of(), "",
						sampleSummary(), "markdown"));

		OfflineMcpTools tools = new OfflineMcpTools(mock(OfflineAnalysisService.class),
				mock(HeapDumpChunkRepository.class), mock(SharkHeapDumpSummarizer.class), analyzer);

		var result = tools.analyzeOfflineHeapRetention("C:/tmp/demo.hprof", 12, 8000, "balanced",
				List.of("byte[]"), List.of("com.demo"));

		assertThat(result.analysisSucceeded()).isTrue();
		verify(analyzer).analyze(Path.of("C:/tmp/demo.hprof"), 12, 8000, "balanced", List.of("byte[]"),
				List.of("com.demo"));
	}

	private static OfflineBundleDraft draftWithHeap(String heapDumpAbsolutePath) {
		return new OfflineBundleDraft("pid=123", "jdk=21", "garbage-first heap total 1K, used 1K",
				new OfflineArtifactSource(null, null), new OfflineArtifactSource(null, null), heapDumpAbsolutePath,
				false, false, false, null, null, null, Map.of());
	}

	private static JvmRuntimeSnapshot stubSnapshot(long pid) {
		return new JvmRuntimeSnapshot(pid, new JvmMemorySnapshot(0L, 0L, 0L, null, null, null, null, null),
				new JvmGcSnapshot("unknown", 0L, 0L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
	}

	private static HeapRetentionSummary sampleSummary() {
		return new HeapRetentionSummary(List.of(), List.of(), List.of(), List.of(),
				new HeapRetentionConfidence("medium", List.of(), List.of()), "markdown", true, List.of(), "");
	}

}
