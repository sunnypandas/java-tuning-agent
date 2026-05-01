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
import com.alibaba.cloud.ai.examples.javatuning.runtime.RetentionChainSegment;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RetentionChainSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SuspectedHolderSummary;
import com.alibaba.cloud.ai.examples.javatuning.source.LocalSourceHotspotFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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

		JavaTuningWorkflowService service = new JavaTuningWorkflowService(stubCollector(),
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

		JavaTuningWorkflowService service = new JavaTuningWorkflowService(stubCollector(),
				MemoryGcDiagnosisEngine.firstVersion(), new LocalSourceHotspotFinder());

		TuningAdviceReport report = service.generateAdviceFromEvidence(evidence, CodeContextSummary.empty(), "local",
				"diagnose-memory");

		assertThat(report.formattedSummary()).contains("Heap retention analysis");
		assertThat(report.formattedSummary()).contains("Engine=dominator-style");
		assertThat(report.formattedSummary()).contains("## Confidence");
	}

	@Test
	void shouldCorrelateRetentionHotspotsWhenGeneratingAdviceFromEvidence() {
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(stubSnapshot(), null, null, List.of(), List.of(), null,
				null, memoryLeakDemoRetentionResult());
		JavaTuningWorkflowService service = new JavaTuningWorkflowService(stubCollector(),
				MemoryGcDiagnosisEngine.firstVersion(), new LocalSourceHotspotFinder());
		CodeContextSummary ctx = new CodeContextSummary(List.of(), Map.of(), List.of(),
				List.of(Path.of("").toAbsolutePath().resolve("compat/memory-leak-demo").toString()),
				List.of("com.alibaba.cloud.ai.compat.memoryleakdemo"));

		TuningAdviceReport report = service.generateAdviceFromEvidence(evidence, ctx, "local", "diagnose-memory");

		assertThat(report.suspectedCodeHotspots()).extracting(hotspot -> hotspot.className())
			.contains("com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord",
					"com.alibaba.cloud.ai.compat.memoryleakdemo.leak.InMemoryLeakStore");
		assertThat(report.formattedSummary()).contains("heap-retention");
	}

	private static JvmRuntimeCollector stubCollector() {
		return (pid, request) -> stubSnapshot();
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

	private static HeapRetentionAnalysisResult memoryLeakDemoRetentionResult() {
		var holder = new SuspectedHolderSummary("com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord",
				"field-owner", 8_388_608L, 8_388_608L, 128L, "AllocationRecord.payload", "byte[]",
				"payload retains byte arrays");
		var storeHolder = new SuspectedHolderSummary(
				"com.alibaba.cloud.ai.compat.memoryleakdemo.leak.InMemoryLeakStore", "collection-owner",
				8_388_608L, 8_388_608L, 128L, "InMemoryLeakStore.retainedRecords", "AllocationRecord",
				"retainedRecords keeps allocation records reachable");
		var chain = new RetentionChainSummary("unknown",
				List.of(new RetentionChainSegment("com.alibaba.cloud.ai.compat.memoryleakdemo.leak.InMemoryLeakStore",
						"field", "retainedRecords", "java.lang.Object[]"),
						new RetentionChainSegment("java.lang.Object[]", "array-slot", "*",
						"com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord"),
						new RetentionChainSegment("com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord",
								"field", "payload", "byte[]")),
				"byte[]", 65536L, 128L, 8_388_608L, 8_388_608L);
		var summary = new HeapRetentionSummary(List.of(), List.of(holder, storeHolder), List.of(chain), List.of(),
				new HeapRetentionConfidence("medium", List.of("retained bytes are approximate"),
						List.of("Engine=dominator-style")),
				"### Heap retention analysis\n\nEngine=dominator-style", true, List.of(), "");
		return new HeapRetentionAnalysisResult(true, "dominator-style", List.of(), "", summary,
				summary.summaryMarkdown());
	}
}
