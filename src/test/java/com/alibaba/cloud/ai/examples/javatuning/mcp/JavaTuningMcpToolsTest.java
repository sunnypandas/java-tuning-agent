package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.agent.JavaTuningWorkflowService;
import com.alibaba.cloud.ai.examples.javatuning.discovery.JavaApplicationDescriptor;
import com.alibaba.cloud.ai.examples.javatuning.discovery.JavaProcessDiscoveryService;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidenceRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RuntimeCollectionPolicy;
import org.junit.jupiter.api.Test;

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
		given(workflowService.generateAdviceFromEvidence(any(MemoryGcEvidencePack.class), any(CodeContextSummary.class),
				any(), any()))				.willReturn(
						new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(), "medium",
								List.of("stub-reason"), ""));
		given(workflowService.collectEvidence(any(MemoryGcEvidenceRequest.class)))
				.willReturn(new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(), null));

		JavaTuningMcpTools tools = new JavaTuningMcpTools(discoveryService, collector, workflowService);

		assertThat(tools.listJavaApps()).hasSize(1);
		assertThat(tools.inspectJvmRuntime(123L)).extracting(JvmRuntimeSnapshot::pid).isEqualTo(123L);
		assertThat(tools.collectMemoryGcEvidence(new MemoryGcEvidenceRequest(123L, false, false, false, "", "ok")))
				.isNotNull();

		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());
		assertThat(tools.generateTuningAdvice(ctx, 123L, "prod", "lower-gc-pause", false, false, false, "", "")
				.confidenceReasons()).isNotEmpty();

		verify(collector, times(2)).collect(123L, RuntimeCollectionPolicy.CollectionRequest.safeReadonly());
		verify(workflowService, times(1)).generateAdviceFromEvidence(any(MemoryGcEvidencePack.class), eq(ctx),
				eq("prod"), eq("lower-gc-pause"));
	}

	@Test
	void shouldCollectHistogramBeforeAdviceWhenPrivileged() {
		JavaProcessDiscoveryService discoveryService = mock(JavaProcessDiscoveryService.class);
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);

		MemoryGcEvidencePack pack = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(), null);
		given(workflowService.collectEvidence(any(MemoryGcEvidenceRequest.class))).willReturn(pack);
		given(workflowService.generateAdviceFromEvidence(eq(pack), any(CodeContextSummary.class), any(), any()))
				.willReturn(new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(), "high",
						List.of("with-histogram"), ""));

		JavaTuningMcpTools tools = new JavaTuningMcpTools(discoveryService, collector, workflowService);
		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());

		assertThat(tools.generateTuningAdvice(ctx, 123L, "local", "diagnose", true, false, false, "", "approved")
				.confidence()).isEqualTo("high");

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
		assertThatThrownBy(() -> tools.generateTuningAdvice(ctx, 1L, "e", "g", true, false, false, "", "   "))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("confirmationToken");
	}

	@Test
	void shouldCollectHeapDumpBeforeAdviceWhenRequested() {
		JavaProcessDiscoveryService discoveryService = mock(JavaProcessDiscoveryService.class);
		JvmRuntimeCollector collector = mock(JvmRuntimeCollector.class);
		JavaTuningWorkflowService workflowService = mock(JavaTuningWorkflowService.class);

		MemoryGcEvidencePack pack = new MemoryGcEvidencePack(stubSnapshot(123L), null, null, List.of(), List.of(),
				"C:\\tmp\\dump.hprof");
		given(workflowService.collectEvidence(any(MemoryGcEvidenceRequest.class))).willReturn(pack);
		given(workflowService.generateAdviceFromEvidence(eq(pack), any(CodeContextSummary.class), any(), any()))
				.willReturn(new TuningAdviceReport(List.of(), List.of(), List.of(), List.of(), List.of(), "high",
						List.of("with-heap-dump"), ""));

		JavaTuningMcpTools tools = new JavaTuningMcpTools(discoveryService, collector, workflowService);
		var ctx = CodeContextSummary.withoutSource(List.of(), Map.of(), List.of());

		assertThat(tools.generateTuningAdvice(ctx, 123L, "local", "diagnose", false, false, true,
				"C:\\tmp\\dump.hprof", "approved").confidence()).isEqualTo("high");

		verify(workflowService, times(1)).collectEvidence(
				new MemoryGcEvidenceRequest(123L, false, false, true, "C:\\tmp\\dump.hprof", "approved"));
		verify(workflowService, times(1)).generateAdviceFromEvidence(eq(pack), eq(ctx), eq("local"), eq("diagnose"));
		verify(collector, times(0)).collect(any(Long.class), any());
	}
}
