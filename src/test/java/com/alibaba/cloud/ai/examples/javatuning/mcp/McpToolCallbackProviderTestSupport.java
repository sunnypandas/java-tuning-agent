package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.nio.file.Path;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.advice.MemoryGcDiagnosisEngine;
import com.alibaba.cloud.ai.examples.javatuning.agent.JavaTuningWorkflowService;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapDumpChunkRepository;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapRetentionAnalyzer;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineAnalysisService;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineDraftValidator;
import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapDumpSummarizer;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrRecordingRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrRecordingResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidenceRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RuntimeCollectionPolicy;
import com.alibaba.cloud.ai.examples.javatuning.source.LocalSourceHotspotFinder;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

final class McpToolCallbackProviderTestSupport {

	private McpToolCallbackProviderTestSupport() {
	}

	static ToolCallbackProvider create() {
		JvmRuntimeCollector collector = new StubRuntimeCollector();
		JavaTuningWorkflowService workflowService = new JavaTuningWorkflowService(collector,
				MemoryGcDiagnosisEngine.firstVersion(), new LocalSourceHotspotFinder());
		HeapRetentionAnalyzer retentionAnalyzer = (path, topObjectLimit, maxOutputChars, analysisDepth, focusTypes,
				focusPackages) -> new HeapRetentionAnalysisResult(false, "test", List.of(), "",
						new HeapRetentionSummary(List.of(), List.of(), List.of(), List.of(),
								new HeapRetentionConfidence("low", List.of(), List.of()), "", false, List.of(), ""),
						"");

		JavaTuningMcpTools liveTools = new JavaTuningMcpTools(List::of, collector, workflowService);
		OfflineMcpTools offlineTools = new OfflineMcpTools(
				new OfflineAnalysisService(new OfflineDraftValidator(), null, retentionAnalyzer, workflowService),
				new HeapDumpChunkRepository(Path.of(System.getProperty("java.io.tmpdir"))
					.resolve("mcp-schema-contract-unused")),
				new SharkHeapDumpSummarizer(40, 32_000), retentionAnalyzer);
		return MethodToolCallbackProvider.builder().toolObjects(liveTools, offlineTools).build();
	}

	private static final class StubRuntimeCollector implements JvmRuntimeCollector {

		@Override
		public JvmRuntimeSnapshot collect(long pid, RuntimeCollectionPolicy.CollectionRequest request) {
			return snapshot(pid);
		}

		@Override
		public MemoryGcEvidencePack collectMemoryGcEvidence(MemoryGcEvidenceRequest request) {
			return new MemoryGcEvidencePack(snapshot(request.pid()), null, null, List.of(), List.of(), null, null);
		}

		@Override
		public RepeatedSamplingResult collectRepeated(RepeatedSamplingRequest request) {
			return new RepeatedSamplingResult(request.pid(), List.of(), List.of(), List.of(), 1L, 0L);
		}

		@Override
		public JfrRecordingResult recordJfr(JfrRecordingRequest request) {
			return new JfrRecordingResult(request.pid(), request.jfrOutputPath(), 0L, 0L, 0L, List.of(), null,
					List.of(), List.of());
		}

		private static JvmRuntimeSnapshot snapshot(long pid) {
			return new JvmRuntimeSnapshot(pid, new JvmMemorySnapshot(0L, 0L, 0L, null, null, null, null, null),
					new JvmGcSnapshot("unknown", 0L, 0L, 0L, 0L, null), List.of(), "", null, null,
					new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		}

	}

}
