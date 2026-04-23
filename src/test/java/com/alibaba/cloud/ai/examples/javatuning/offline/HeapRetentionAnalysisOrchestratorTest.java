package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class HeapRetentionAnalysisOrchestratorTest {

	@Test
	void deepRequestsTryHeavyAnalyzerBeforeFallingBackToShark() {
		HeapRetentionAnalyzer heavy = mock(HeapRetentionAnalyzer.class);
		HeapRetentionAnalyzer shark = mock(HeapRetentionAnalyzer.class);
		given(heavy.analyze(any(), any(), any(), any(), any(), any()))
			.willReturn(new HeapRetentionAnalysisResult(false, "dominator-style", List.of("heavy failed"),
					"out of memory", HeapRetentionSummary.empty(), ""));
		given(shark.analyze(any(), any(), any(), eq("deep"), any(), any()))
			.willReturn(sampleSharkFallbackResult());

		var orchestrator = new HeapRetentionAnalysisOrchestrator(shark, heavy);

		var result = orchestrator.analyze(Path.of("C:/tmp/demo.hprof"), 10, 12000, "deep", List.of(), List.of());

		verify(heavy).analyze(any(), any(), any(), eq("deep"), any(), any());
		verify(shark).analyze(any(), any(), any(), eq("deep"), any(), any());
		assertThat(result.engine()).isEqualTo("shark");
		assertThat(result.warnings()).anyMatch(it -> it.contains("fallback"));
		assertThat(result.retentionSummary().warnings()).anyMatch(it -> it.contains("fallback"));
	}

	@Test
	void deepRequestsFallbackToSharkWhenHeavyAnalyzerThrows() {
		HeapRetentionAnalyzer heavy = mock(HeapRetentionAnalyzer.class);
		HeapRetentionAnalyzer shark = mock(HeapRetentionAnalyzer.class);
		given(heavy.analyze(any(), any(), any(), any(), any(), any()))
			.willThrow(new IllegalStateException("boom"));
		given(shark.analyze(any(), any(), any(), eq("deep"), any(), any()))
			.willReturn(sampleSharkFallbackResult());

		var orchestrator = new HeapRetentionAnalysisOrchestrator(shark, heavy);

		var result = orchestrator.analyze(Path.of("C:/tmp/demo.hprof"), 10, 12000, "deep", List.of(), List.of());

		verify(heavy).analyze(any(), any(), any(), eq("deep"), any(), any());
		verify(shark).analyze(any(), any(), any(), eq("deep"), any(), any());
		assertThat(result.engine()).isEqualTo("shark");
		assertThat(result.warnings()).anyMatch(it -> it.contains("fallback"));
		assertThat(result.warnings()).anyMatch(it -> it.contains("boom"));
		assertThat(result.retentionSummary().warnings()).anyMatch(it -> it.contains("fallback"));
		assertThat(result.retentionSummary().warnings()).anyMatch(it -> it.contains("boom"));
	}

	@Test
	void fastRequestsStayOnSharkAndSkipHeavyAnalyzer() {
		HeapRetentionAnalyzer heavy = mock(HeapRetentionAnalyzer.class);
		HeapRetentionAnalyzer shark = mock(HeapRetentionAnalyzer.class);
		given(shark.analyze(any(), any(), any(), eq("fast"), any(), any()))
			.willReturn(sampleSharkFallbackResult());

		var orchestrator = new HeapRetentionAnalysisOrchestrator(shark, heavy);

		var result = orchestrator.analyze(Path.of("C:/tmp/demo.hprof"), 10, 12000, "fast", List.of(), List.of());

		verify(shark).analyze(any(), any(), any(), eq("fast"), any(), any());
		verify(heavy, never()).analyze(any(), any(), any(), any(), any(), any());
		assertThat(result.engine()).isEqualTo("shark");
	}

	@Test
	void balancedRequestsStayOnSharkAndSkipHeavyAnalyzer() {
		HeapRetentionAnalyzer heavy = mock(HeapRetentionAnalyzer.class);
		HeapRetentionAnalyzer shark = mock(HeapRetentionAnalyzer.class);
		given(shark.analyze(any(), any(), any(), eq("balanced"), any(), any()))
			.willReturn(sampleSharkFallbackResult());

		var orchestrator = new HeapRetentionAnalysisOrchestrator(shark, heavy);

		var result = orchestrator.analyze(Path.of("C:/tmp/demo.hprof"), 10, 12000, "balanced", List.of(),
				List.of());

		verify(shark).analyze(any(), any(), any(), eq("balanced"), any(), any());
		verify(heavy, never()).analyze(any(), any(), any(), any(), any(), any());
		assertThat(result.engine()).isEqualTo("shark");
	}

	private static HeapRetentionAnalysisResult sampleSharkFallbackResult() {
		return new HeapRetentionAnalysisResult(true, "shark", List.of("shark used"), "",
				new HeapRetentionSummary(List.of(), List.of(), List.of(), List.of(),
						new HeapRetentionConfidence("medium", List.of(), List.of()), "summary", true, List.of(), ""),
				"summary");
	}

}
