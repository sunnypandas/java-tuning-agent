package com.alibaba.cloud.ai.examples.javatuning.agent;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.MemoryGcDiagnosisEngine;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SuspectedHolderSummary;
import com.alibaba.cloud.ai.examples.javatuning.source.LocalSourceHotspotFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.BDDMockito.given;

class JavaTuningWorkflowServiceTest {

	@Test
	void shouldAttachHotspotsWhenHistogramAndRootsProvided() {
		ClassHistogramSummary histogram = new ClassHistogramParser().parse("""
				 1: 10 1024 com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord
				""");
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(1L,
				new JvmMemorySnapshot(1L, 1L, 1L, null, null, null, null, null),
				new JvmGcSnapshot("G1", 1L, 1L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());

		JavaTuningWorkflowService service = new JavaTuningWorkflowService(mock(JvmRuntimeCollector.class),
				MemoryGcDiagnosisEngine.firstVersion(), new LocalSourceHotspotFinder());

		CodeContextSummary ctx = new CodeContextSummary(List.of(), Map.of(), List.of(),
				List.of("compat/memory-leak-demo"), List.of("com.alibaba.cloud.ai.compat.memoryleakdemo"));
		var report = service.generateAdvice(new TuningAdviceRequest(snapshot, ctx, "local", "diagnose-memory", histogram));

		assertThat(report.suspectedCodeHotspots()).isNotEmpty();
		assertThat(report.suspectedCodeHotspots().get(0).fileHint()).contains("AllocationRecord.java");
		assertThat(report.formattedSummary()).contains("## Suspected code hotspots");
		assertThat(report.formattedSummary()).contains("AllocationRecord");
	}

	@Test
	void shouldAppendRetentionMarkdownWhenRetentionEvidenceExists() {
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(stubSnapshot(), null, null, List.of(), List.of(), null,
				null, sampleRetentionResult());

		MemoryGcDiagnosisEngine diagnosisEngine = mock(MemoryGcDiagnosisEngine.class);
		JavaTuningWorkflowService service = new JavaTuningWorkflowService(mock(JvmRuntimeCollector.class),
				diagnosisEngine, new LocalSourceHotspotFinder());
		TuningAdviceReport base = new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(), "medium",
				List.of("base confidence"), "");
		given(diagnosisEngine.diagnose(any(), any(), any(), any())).willReturn(base);

		TuningAdviceReport report = service.generateAdviceFromEvidence(evidence, CodeContextSummary.empty(), "local",
				"diagnose-memory");

		assertThat(report.formattedSummary()).contains("Heap retention analysis");
		assertThat(report.formattedSummary()).contains("Engine=dominator-style");
		assertThat(report.formattedSummary()).contains("## Confidence");
	}

	private static JvmRuntimeSnapshot stubSnapshot() {
		return new JvmRuntimeSnapshot(1L, new JvmMemorySnapshot(1L, 1L, 1L, null, null, null, null, null),
				new JvmGcSnapshot("G1", 1L, 1L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
	}

	private static HeapRetentionAnalysisResult sampleRetentionResult() {
		return new HeapRetentionAnalysisResult(true, "dominator-style", List.of(), "",
				new HeapRetentionSummary(List.of(),
						List.of(new SuspectedHolderSummary("com.example.CacheHolder", "static-field-owner",
								12_582_912L, 12_582_912L, 4L, "com.example.CacheHolder.INSTANCE", "java.util.Map",
								"static holder")), List.of(), List.of(),
						new HeapRetentionConfidence("medium", List.of("retained bytes are approximate"),
								List.of("Engine=dominator-style")),
						"### Heap retention analysis\n\nEngine=dominator-style", true, List.of(), ""),
				"### Heap retention analysis\n\nEngine=dominator-style");
	}
}
