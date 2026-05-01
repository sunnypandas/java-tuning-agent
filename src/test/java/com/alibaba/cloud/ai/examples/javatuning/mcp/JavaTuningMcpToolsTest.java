package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceRequest;
import com.alibaba.cloud.ai.examples.javatuning.agent.JavaTuningWorkflowService;
import com.alibaba.cloud.ai.examples.javatuning.discovery.JavaApplicationDescriptor;
import com.alibaba.cloud.ai.examples.javatuning.discovery.JavaProcessDiscoveryService;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrAllocationSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrCountAndBytes;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrRecordingRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrRecordingResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidenceRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedRuntimeSample;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ResourceBudgetEvidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RuntimeCollectionPolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class JavaTuningMcpToolsTest {

	private static JvmRuntimeSnapshot stubSnapshot(long pid) {
		return new JvmRuntimeSnapshot(pid, new JvmMemorySnapshot(0L, 0L, 0L, null, null, null, null, null),
				new JvmGcSnapshot("unknown", 0L, 0L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
	}

	@Test
	void shouldDelegateListInspectEvidenceAndLightweightAdvice() {
		JavaProcessDiscoveryService discoveryService = mock(JavaProcessDiscoveryService.class);
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);

		given(discoveryService.listJavaApplications()).willReturn(List.of(
				new JavaApplicationDescriptor(123L, "orders", "orders.jar", "java -jar orders.jar", "", "", "",
						"executable-jar", true, List.of("prod"), List.of(8080), "jps", "medium")));
		given(collector.collect(123L, RuntimeCollectionPolicy.CollectionRequest.safeReadonly()))
				.willReturn(stubSnapshot(123L));
		given(workflowService.generateAdvice(any(TuningAdviceRequest.class)))
				.willReturn(new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(), "medium",
						List.of("stub-reason"), ""));
		given(workflowService.collectEvidence(any(MemoryGcEvidenceRequest.class)))
				.willReturn(new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(), null, null));

		JavaTuningMcpTools tools = new JavaTuningMcpTools(discoveryService, collector, workflowService);

		assertThat(tools.listJavaApps()).hasSize(1);
		assertThat(tools.inspectJvmRuntime(123L)).extracting(JvmRuntimeSnapshot::pid).isEqualTo(123L);
		assertThat(tools.collectMemoryGcEvidence(new MemoryGcEvidenceRequest(123L, false, false, false, "", "ok")))
				.isNotNull();

		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());
		assertThat(tools.generateTuningAdvice(ctx, 123L, "prod", "lower-gc-pause", false, false, false, "", "", null,
				null).confidenceReasons()).isNotEmpty();

		verify(collector, times(2)).collect(123L, RuntimeCollectionPolicy.CollectionRequest.safeReadonly());
		verify(workflowService, times(1)).generateAdvice(any(TuningAdviceRequest.class));
		verify(workflowService, times(0)).generateAdviceFromEvidence(any(), any(), any(), any());
	}

	@Test
	void shouldCollectHistogramBeforeAdviceWhenPrivileged() {
		JavaProcessDiscoveryService discoveryService = mock(JavaProcessDiscoveryService.class);
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);

		MemoryGcEvidencePack pack = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(), null,
				null);
		given(workflowService.collectEvidence(any(MemoryGcEvidenceRequest.class))).willReturn(pack);
		given(workflowService.generateAdviceFromEvidence(eq(pack), any(CodeContextSummary.class), any(), any()))
				.willReturn(new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(), "high",
						List.of("with-histogram"), ""));

		JavaTuningMcpTools tools = new JavaTuningMcpTools(discoveryService, collector, workflowService);
		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());

		assertThat(tools.generateTuningAdvice(ctx, 123L, "local", "diagnose", true, false, false, "", "approved", null,
				null).confidence()).isEqualTo("high");

		verify(workflowService, times(1))
				.collectEvidence(new MemoryGcEvidenceRequest(123L, true, false, false, "", "approved"));
		verify(workflowService, times(1)).generateAdviceFromEvidence(eq(pack), eq(ctx), eq("local"), eq("diagnose"));
		verify(collector, times(0)).collect(any(Long.class), any());
	}

	@Test
	void shouldRejectPrivilegedAdviceWithoutToken() {
		JavaTuningMcpTools tools = new JavaTuningMcpTools(mock(JavaProcessDiscoveryService.class),
				mock(JvmRuntimeCollector.class), mock(JavaTuningWorkflowService.class));
		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());
		assertThatThrownBy(() -> tools.generateTuningAdvice(ctx, 1L, "e", "g", true, false, false, "", "   ", null,
				null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("confirmationToken");
	}

	@Test
	void shouldDelegateRepeatedRuntimeInspectionToCollector() {
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		RepeatedSamplingRequest request = new RepeatedSamplingRequest(123L, 3, 500L, true, true, "");
		RepeatedSamplingResult result = new RepeatedSamplingResult(123L, List.of(), List.of(), List.of(), 1L, 0L);
		given(collector.collectRepeated(request)).willReturn(result);
		JavaTuningMcpTools tools = new JavaTuningMcpTools(mock(JavaProcessDiscoveryService.class), collector,
				mock(JavaTuningWorkflowService.class));

		assertThat(tools.inspectJvmRuntimeRepeated(request)).isSameAs(result);
	}

	@Test
	void shouldDelegateJfrRecordingToCollector() {
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		JfrRecordingRequest request = new JfrRecordingRequest(123L, 30, "profile", "C:/tmp/app.jfr", 200_000,
				"approved");
		JfrRecordingResult result = new JfrRecordingResult(123L, "C:/tmp/app.jfr", 10L, 1L, 2L, List.of(), null,
				List.of(), List.of());
		given(collector.recordJfr(request)).willReturn(result);
		JavaTuningMcpTools tools = new JavaTuningMcpTools(mock(JavaProcessDiscoveryService.class), collector,
				mock(JavaTuningWorkflowService.class));

		assertThat(tools.recordJvmFlightRecording(request)).isSameAs(result);
	}

	@Test
	void shouldCollectHeapDumpBeforeAdviceWhenRequested() {
		JavaProcessDiscoveryService discoveryService = mock(JavaProcessDiscoveryService.class);
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);

		MemoryGcEvidencePack pack = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(),
				"C:\\tmp\\dump.hprof", null);
		given(workflowService.collectEvidence(any(MemoryGcEvidenceRequest.class))).willReturn(pack);
		given(workflowService.generateAdviceFromEvidence(eq(pack), any(CodeContextSummary.class), any(), any()))
				.willReturn(new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(), "high",
						List.of("with-heap-dump"), ""));

		JavaTuningMcpTools tools = new JavaTuningMcpTools(discoveryService, collector, workflowService);
		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());

		assertThat(tools.generateTuningAdvice(ctx, 123L, "local", "diagnose", false, false, true,
				"C:\\tmp\\dump.hprof", "approved", null, null).confidence()).isEqualTo("high");

		verify(workflowService, times(1)).collectEvidence(
				new MemoryGcEvidenceRequest(123L, false, false, true, "C:\\tmp\\dump.hprof", "approved"));
		verify(workflowService, times(1)).generateAdviceFromEvidence(eq(pack), eq(ctx), eq("local"), eq("diagnose"));
		verify(collector, times(0)).collect(any(Long.class), any());
	}

	@Test
	void shouldGenerateAdviceFromExistingEvidenceWithoutCollectingAgain() {
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);

		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(),
				List.of("already-collected"), null, null);
		given(workflowService.generateAdviceFromEvidence(any(MemoryGcEvidencePack.class), any(CodeContextSummary.class),
				eq("prod"), eq("diagnose")))
			.willReturn(new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(), "high",
					List.of("from-current-evidence"), ""));

		JavaTuningMcpTools tools = new JavaTuningMcpTools(mock(JavaProcessDiscoveryService.class), collector,
				workflowService);
		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());

		assertThat(tools.generateTuningAdviceFromEvidence(evidence, ctx, "prod", "diagnose", null, null, null, null)
			.confidence())
			.isEqualTo("high");

		verify(workflowService, times(1)).generateAdviceFromEvidence(any(MemoryGcEvidencePack.class), eq(ctx),
				eq("prod"), eq("diagnose"));
		verify(workflowService, times(0)).collectEvidence(any(MemoryGcEvidenceRequest.class));
		verify(collector, times(0)).collect(any(Long.class), any());
	}

	@Test
	void shouldReuseHeapDumpEvidencePathWithoutRepeatingPrivilegedCollection() {
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);

		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(),
				"C:\\tmp\\dump.hprof", null);
		given(workflowService.generateAdviceFromEvidence(any(MemoryGcEvidencePack.class), any(CodeContextSummary.class),
				eq("local"), eq("diagnose")))
			.willReturn(new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(), "high",
					List.of("reused-heap-dump"), ""));

		JavaTuningMcpTools tools = new JavaTuningMcpTools(mock(JavaProcessDiscoveryService.class), collector,
				workflowService);
		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());

		assertThat(tools.generateTuningAdviceFromEvidence(evidence, ctx, "local", "diagnose", null, null, null, null)
			.confidenceReasons())
			.contains("reused-heap-dump");

		ArgumentCaptor<MemoryGcEvidencePack> captor = ArgumentCaptor.forClass(MemoryGcEvidencePack.class);
		verify(workflowService).generateAdviceFromEvidence(captor.capture(), eq(ctx), eq("local"), eq("diagnose"));
		assertThat(captor.getValue().heapDumpPath()).isEqualTo("C:\\tmp\\dump.hprof");
		verify(workflowService, times(0)).collectEvidence(any(MemoryGcEvidenceRequest.class));
		verify(collector, times(0)).collect(any(Long.class), any());
	}

	@Test
	void shouldMergeOptionalRepeatedSamplingIntoAdviceFromEvidencePath() {
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);

		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(),
				List.of("pack-warn"), null, null);
		given(workflowService.generateAdviceFromEvidence(any(MemoryGcEvidencePack.class), any(CodeContextSummary.class),
				eq("local"), eq("footprint")))
			.willReturn(new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(), "medium",
					List.of("merged"), ""));
		RepeatedSamplingResult repeated = new RepeatedSamplingResult(123L, List.of(),
				List.of("repeated-flag"), List.of(), 10L, 900L);

		JavaTuningMcpTools tools = new JavaTuningMcpTools(mock(JavaProcessDiscoveryService.class), collector,
				workflowService);
		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());

		tools.generateTuningAdviceFromEvidence(evidence, ctx, "local", "footprint", repeated, null, null, null);

		ArgumentCaptor<MemoryGcEvidencePack> mergedCaptor = ArgumentCaptor.forClass(MemoryGcEvidencePack.class);
		verify(workflowService).generateAdviceFromEvidence(mergedCaptor.capture(), eq(ctx), eq("local"),
				eq("footprint"));
		assertThat(mergedCaptor.getValue().repeatedSamplingResult()).isSameAs(repeated);
		assertThat(mergedCaptor.getValue().warnings()).contains("pack-warn", "repeated-flag");
		verify(workflowService, times(0)).collectEvidence(any(MemoryGcEvidenceRequest.class));
		verify(collector, times(0)).collect(any(Long.class), any());
	}

	@Test
	void shouldPassBaselineAndJfrIntoLightweightAdviceRequest() {
		JavaProcessDiscoveryService discoveryService = mock(JavaProcessDiscoveryService.class);
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);

		given(collector.collect(123L, RuntimeCollectionPolicy.CollectionRequest.safeReadonly()))
				.willReturn(stubSnapshot(123L));
		given(workflowService.generateAdvice(any(TuningAdviceRequest.class))).willReturn(
				new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(), "medium",
						List.of("stub"), ""));

		JavaTuningMcpTools tools = new JavaTuningMcpTools(discoveryService, collector, workflowService);
		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());

		MemoryGcEvidencePack baseline = new MemoryGcEvidencePack(stubSnapshot(99L), null, null, List.of(), List.of(),
				null, null);
		JfrSummary jfr = new JfrSummary(null, null, null, null,
				new JfrAllocationSummary(1000L, List.of(new JfrCountAndBytes("demo.Cls", 5L, 4096L)), List.of(), 12L),
				null, null, Map.of(), List.of());

		tools.generateTuningAdvice(ctx, 123L, "prod", "goal", false, false, false, "", "", baseline, jfr);

		ArgumentCaptor<TuningAdviceRequest> captor = ArgumentCaptor.forClass(TuningAdviceRequest.class);
		verify(workflowService).generateAdvice(captor.capture());
		assertThat(captor.getValue().baselineEvidence()).isSameAs(baseline);
		assertThat(captor.getValue().jfrSummary()).isSameAs(jfr);
		assertThat(captor.getValue().runtimeSnapshot().pid()).isEqualTo(123L);
	}

	@Test
	void shouldPassRepeatedSamplesAndResourceBudgetIntoLightweightAdviceRequest() {
		JavaProcessDiscoveryService discoveryService = mock(JavaProcessDiscoveryService.class);
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);

		given(collector.collect(123L, RuntimeCollectionPolicy.CollectionRequest.safeReadonly()))
			.willReturn(stubSnapshot(123L));
		given(workflowService.generateAdvice(any(TuningAdviceRequest.class))).willReturn(
				new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(), "medium",
						List.of("stub"), ""));

		JavaTuningMcpTools tools = new JavaTuningMcpTools(discoveryService, collector, workflowService);
		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());
		RepeatedSamplingResult repeated = new RepeatedSamplingResult(123L,
				List.of(new RepeatedRuntimeSample(1_000L,
						new JvmMemorySnapshot(1L, 2L, 3L, null, null, null, null, null),
						new JvmGcSnapshot("G1", 1L, 1L, 0L, 0L, null), 20L, 100L, List.of())),
				List.of(), List.of(), 1_000L, 0L);
		ResourceBudgetEvidence resourceBudget = new ResourceBudgetEvidence(1024L, 900L, 1.0d, 512L, 512L, 128L,
				64L, 704L, List.of(), List.of());

		tools.generateTuningAdvice(ctx, 123L, "prod", "goal", false, false, false, "", "", null, null, repeated,
				resourceBudget);

		ArgumentCaptor<TuningAdviceRequest> captor = ArgumentCaptor.forClass(TuningAdviceRequest.class);
		verify(workflowService).generateAdvice(captor.capture());
		assertThat(captor.getValue().repeatedSamplingResult()).isSameAs(repeated);
		assertThat(captor.getValue().resourceBudgetEvidence()).isSameAs(resourceBudget);
	}

	@Test
	void shouldMergeBaselineAndJfrIntoPrivilegedEvidencePack() {
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);

		MemoryGcEvidencePack collected = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(),
				null, null);
		MemoryGcEvidencePack baseline = new MemoryGcEvidencePack(stubSnapshot(1L), null, null, List.of(), List.of(), null,
				null);
		JfrSummary jfr = new JfrSummary(null, null, null, null,
				new JfrAllocationSummary(500L, List.of(new JfrCountAndBytes("demo.X", 2L, 256L)), List.of(), 3L), null,
				null, Map.of(), List.of());

		given(workflowService.collectEvidence(any(MemoryGcEvidenceRequest.class))).willReturn(collected);
		given(workflowService.generateAdviceFromEvidence(any(MemoryGcEvidencePack.class), any(CodeContextSummary.class),
				any(), any())).willReturn(new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(),
						"high", List.of("ok"), ""));

		JavaTuningMcpTools tools = new JavaTuningMcpTools(mock(JavaProcessDiscoveryService.class), collector,
				workflowService);
		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());

		tools.generateTuningAdvice(ctx, 123L, "stage", "cpu", true, false, false, "", "approved", baseline, jfr);

		ArgumentCaptor<MemoryGcEvidencePack> captor = ArgumentCaptor.forClass(MemoryGcEvidencePack.class);
		verify(workflowService).generateAdviceFromEvidence(captor.capture(), eq(ctx), eq("stage"), eq("cpu"));
		assertThat(captor.getValue().baselineEvidence()).isSameAs(baseline);
		assertThat(captor.getValue().jfrSummary()).isSameAs(jfr);
		assertThat(captor.getValue().snapshot().pid()).isEqualTo(123L);
	}
}
