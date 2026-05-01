package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.MemoryGcDiagnosisEngine;
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
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.source.LocalSourceHotspotFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineMcpToolsRetentionTest {

	@Test
	void generateOfflineTuningAdviceDeepRequestsRetentionAnalyzer() {
		OfflineBundleDraft draft = draftWithHeap("C:/tmp/demo.hprof");
		CodeContextSummary ctx = new CodeContextSummary(List.of(), Map.of(), List.of(), List.of(), List.of("com.demo"));
		MemoryGcEvidencePack basePack = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(),
				"C:/tmp/demo.hprof", null);
		HeapRetentionAnalysisResult retentionResult = new HeapRetentionAnalysisResult(true, "dominator-style",
				List.of("retention warning"), "", sampleSummary(), "retention markdown");
		TuningAdviceReport advice = new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(),
				"high", List.of("ok"), "summary");
		RecordingRetentionAnalyzer analyzer = new RecordingRetentionAnalyzer(retentionResult);
		RecordingWorkflowService workflowService = new RecordingWorkflowService(advice);
		OfflineAnalysisService service = new OfflineAnalysisService(
				new StubOfflineDraftValidator(validResult()), new StubOfflineEvidenceAssembler(basePack), analyzer,
				workflowService);
		OfflineMcpTools tools = tools(service, analyzer);

		TuningAdviceReport report = tools.generateOfflineTuningAdvice(ctx, draft, "local", "diagnose", "deep",
				"approved", true);

		assertThat(report.confidence()).isEqualTo("high");
		assertThat(workflowService.lastEvidence.heapRetentionAnalysis()).isEqualTo(retentionResult);
		assertThat(workflowService.lastContext).isEqualTo(ctx);
		assertThat(workflowService.lastEnvironment).isEqualTo("local");
		assertThat(workflowService.lastOptimizationGoal).isEqualTo("diagnose");
		assertThat(analyzer.calls).containsExactly(
				new RetentionCall(Path.of("C:/tmp/demo.hprof"), null, null, "deep", List.of(), List.of("com.demo")));
	}

	@Test
	void generateOfflineTuningAdviceBalancedSkipsRetentionAnalyzer() {
		OfflineBundleDraft draft = draftWithHeap("C:/tmp/demo.hprof");
		CodeContextSummary ctx = new CodeContextSummary(List.of(), Map.of(), List.of(), List.of(), List.of("com.demo"));
		MemoryGcEvidencePack basePack = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(),
				"C:/tmp/demo.hprof", null);
		TuningAdviceReport advice = new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(),
				"high", List.of("ok"), "summary");
		RecordingRetentionAnalyzer analyzer = new RecordingRetentionAnalyzer(null);
		RecordingWorkflowService workflowService = new RecordingWorkflowService(advice);
		OfflineAnalysisService service = new OfflineAnalysisService(
				new StubOfflineDraftValidator(validResult()), new StubOfflineEvidenceAssembler(basePack), analyzer,
				workflowService);
		OfflineMcpTools tools = tools(service, analyzer);

		TuningAdviceReport report = tools.generateOfflineTuningAdvice(ctx, draft, "local", "diagnose", null,
				"approved", true);

		assertThat(report.confidence()).isEqualTo("high");
		assertThat(workflowService.lastEvidence.heapRetentionAnalysis()).isNull();
		assertThat(analyzer.calls).isEmpty();
	}

	@Test
	void generateOfflineTuningAdviceDeepWithoutHeapSkipsRetentionAnalyzerAndWarns() {
		OfflineBundleDraft draft = draftWithHeap("");
		CodeContextSummary ctx = new CodeContextSummary(List.of(), Map.of(), List.of(), List.of(), List.of("com.demo"));
		MemoryGcEvidencePack basePack = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(),
				null, null);
		TuningAdviceReport advice = new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(),
				"high", List.of("ok"), "summary");
		RecordingRetentionAnalyzer analyzer = new RecordingRetentionAnalyzer(null);
		RecordingWorkflowService workflowService = new RecordingWorkflowService(advice);
		OfflineAnalysisService service = new OfflineAnalysisService(
				new StubOfflineDraftValidator(validResult()), new StubOfflineEvidenceAssembler(basePack), analyzer,
				workflowService);
		OfflineMcpTools tools = tools(service, analyzer);

		TuningAdviceReport report = tools.generateOfflineTuningAdvice(ctx, draft, "local", "diagnose", "deep",
				"   ", true);

		assertThat(report.confidence()).isEqualTo("high");
		assertThat(workflowService.lastEvidence.heapRetentionAnalysis()).isNull();
		assertThat(workflowService.lastEvidence.missingData()).contains("heapRetentionAnalysis");
		assertThat(workflowService.lastEvidence.warnings()).anyMatch(w -> w.contains("retention evidence was skipped"));
		assertThat(analyzer.calls).isEmpty();
	}

	@Test
	void generateOfflineTuningAdviceDeepWithFailedRetentionMarksMissingData() {
		OfflineBundleDraft draft = draftWithHeap("C:/tmp/missing.hprof");
		CodeContextSummary ctx = new CodeContextSummary(List.of(), Map.of(), List.of(), List.of(), List.of("com.demo"));
		MemoryGcEvidencePack basePack = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(),
				"C:/tmp/missing.hprof", null);
		HeapRetentionAnalysisResult failedRetention = new HeapRetentionAnalysisResult(false, "dominator-style",
				List.of("analyzer warning"), "file missing", sampleSummary(), "");
		TuningAdviceReport advice = new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(),
				"medium", List.of("ok"), "summary");
		RecordingRetentionAnalyzer analyzer = new RecordingRetentionAnalyzer(failedRetention);
		RecordingWorkflowService workflowService = new RecordingWorkflowService(advice);
		OfflineAnalysisService service = new OfflineAnalysisService(
				new StubOfflineDraftValidator(validResult()), new StubOfflineEvidenceAssembler(basePack), analyzer,
				workflowService);
		OfflineMcpTools tools = tools(service, analyzer);

		TuningAdviceReport report = tools.generateOfflineTuningAdvice(ctx, draft, "local", "diagnose", "deep",
				"approved", true);

		assertThat(report.confidence()).isEqualTo("medium");
		assertThat(workflowService.lastEvidence.heapRetentionAnalysis()).isNull();
		assertThat(workflowService.lastEvidence.missingData()).contains("heapRetentionAnalysis");
		assertThat(workflowService.lastEvidence.warnings()).anyMatch(w -> w.contains("Deep retention analysis failed"));
		assertThat(workflowService.lastEvidence.warnings()).anyMatch(w -> w.contains("analyzer warning"));
		assertThat(analyzer.calls).containsExactly(new RetentionCall(Path.of("C:/tmp/missing.hprof"), null, null,
				"deep", List.of(), List.of("com.demo")));
	}

	@Test
	void analyzeOfflineHeapRetentionDelegatesToAnalyzer() {
		HeapRetentionAnalysisResult retentionResult = new HeapRetentionAnalysisResult(true, "shark", List.of(), "",
				sampleSummary(), "markdown");
		RecordingRetentionAnalyzer analyzer = new RecordingRetentionAnalyzer(retentionResult);
		OfflineMcpTools tools = new OfflineMcpTools(null, heapDumpChunkRepository(), heapDumpSummarizer(), analyzer);

		var result = tools.analyzeOfflineHeapRetention("C:/tmp/demo.hprof", 12, 8000, "balanced",
				List.of("byte[]"), List.of("com.demo"));

		assertThat(result.analysisSucceeded()).isTrue();
		assertThat(analyzer.calls).containsExactly(new RetentionCall(Path.of("C:/tmp/demo.hprof"), 12, 8000,
				"balanced", List.of("byte[]"), List.of("com.demo")));
	}

	private static OfflineMcpTools tools(OfflineAnalysisService service, HeapRetentionAnalyzer analyzer) {
		return new OfflineMcpTools(service, heapDumpChunkRepository(), heapDumpSummarizer(), analyzer);
	}

	private static HeapDumpChunkRepository heapDumpChunkRepository() {
		return new HeapDumpChunkRepository(Path.of(System.getProperty("java.io.tmpdir"))
			.resolve("offline-mcp-tools-retention-test-unused"));
	}

	private static SharkHeapDumpSummarizer heapDumpSummarizer() {
		return new SharkHeapDumpSummarizer(10, 1_000);
	}

	private static OfflineDraftValidationResult validResult() {
		return new OfflineDraftValidationResult(List.of(), List.of(), "", true, 6);
	}

	private static OfflineBundleDraft draftWithHeap(String heapDumpAbsolutePath) {
		return new OfflineBundleDraft("pid=123", "jdk=21", "garbage-first heap total 1K, used 1K",
				new OfflineArtifactSource(null, null), new OfflineArtifactSource(null, null), heapDumpAbsolutePath,
				false, false, false, null, null, null, null, null, null, Map.of());
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

	private record RetentionCall(Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars, String analysisDepth,
			List<String> focusTypes, List<String> focusPackages) {
	}

	private static final class RecordingRetentionAnalyzer implements HeapRetentionAnalyzer {

		private final List<RetentionCall> calls = new ArrayList<>();

		private final HeapRetentionAnalysisResult result;

		private RecordingRetentionAnalyzer(HeapRetentionAnalysisResult result) {
			this.result = result;
		}

		@Override
		public HeapRetentionAnalysisResult analyze(Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars,
				String analysisDepth, List<String> focusTypes, List<String> focusPackages) {
			calls.add(new RetentionCall(heapDumpPath, topObjectLimit, maxOutputChars, analysisDepth,
					focusTypes == null ? List.of() : List.copyOf(focusTypes),
					focusPackages == null ? List.of() : List.copyOf(focusPackages)));
			return result;
		}

	}

	private static final class StubOfflineDraftValidator extends OfflineDraftValidator {

		private final OfflineDraftValidationResult result;

		private StubOfflineDraftValidator(OfflineDraftValidationResult result) {
			this.result = result;
		}

		@Override
		public OfflineDraftValidationResult validate(OfflineBundleDraft draft, boolean proceedWithMissingRequired) {
			return result;
		}

	}

	private static final class StubOfflineEvidenceAssembler extends OfflineEvidenceAssembler {

		private final MemoryGcEvidencePack evidencePack;

		private StubOfflineEvidenceAssembler(MemoryGcEvidencePack evidencePack) {
			super(null, null, null, null, false);
			this.evidencePack = evidencePack;
		}

		@Override
		public MemoryGcEvidencePack build(OfflineBundleDraft draft) {
			return evidencePack;
		}

	}

	private static final class RecordingWorkflowService extends JavaTuningWorkflowService {

		private final TuningAdviceReport report;

		private MemoryGcEvidencePack lastEvidence;

		private CodeContextSummary lastContext;

		private String lastEnvironment;

		private String lastOptimizationGoal;

		private RecordingWorkflowService(TuningAdviceReport report) {
			super((pid, request) -> stubSnapshot(pid), MemoryGcDiagnosisEngine.firstVersion(),
					new LocalSourceHotspotFinder());
			this.report = report;
		}

		@Override
		public TuningAdviceReport generateAdviceFromEvidence(MemoryGcEvidencePack evidence,
				CodeContextSummary codeContextSummary, String environment, String optimizationGoal) {
			this.lastEvidence = evidence;
			this.lastContext = codeContextSummary;
			this.lastEnvironment = environment;
			this.lastOptimizationGoal = optimizationGoal;
			return report;
		}

	}

}
