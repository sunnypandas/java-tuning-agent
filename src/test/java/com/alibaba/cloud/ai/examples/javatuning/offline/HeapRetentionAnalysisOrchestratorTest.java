package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeapRetentionAnalysisOrchestratorTest {

	@Test
	void deepRequestsTryHeavyAnalyzerBeforeFallingBackToShark() {
		RecordingAnalyzer heavy = RecordingAnalyzer.returning(new HeapRetentionAnalysisResult(false,
				"dominator-style", List.of("heavy failed"), "out of memory", HeapRetentionSummary.empty(), ""));
		RecordingAnalyzer shark = RecordingAnalyzer.returning(sampleSharkFallbackResult());

		var orchestrator = new HeapRetentionAnalysisOrchestrator(shark, heavy);

		var result = orchestrator.analyze(Path.of("C:/tmp/demo.hprof"), 10, 12000, "deep", List.of(), List.of());

		assertThat(heavy.calls()).containsExactly("deep");
		assertThat(shark.calls()).containsExactly("deep");
		assertThat(result.engine()).isEqualTo("shark");
		assertThat(result.warnings()).anyMatch(it -> it.contains("fallback"));
		assertThat(result.retentionSummary().warnings()).anyMatch(it -> it.contains("fallback"));
	}

	@Test
	void deepRequestsFallbackToSharkWhenHeavyAnalyzerThrows() {
		RecordingAnalyzer heavy = RecordingAnalyzer.throwing(new IllegalStateException("boom"));
		RecordingAnalyzer shark = RecordingAnalyzer.returning(sampleSharkFallbackResult());

		var orchestrator = new HeapRetentionAnalysisOrchestrator(shark, heavy);

		var result = orchestrator.analyze(Path.of("C:/tmp/demo.hprof"), 10, 12000, "deep", List.of(), List.of());

		assertThat(heavy.calls()).containsExactly("deep");
		assertThat(shark.calls()).containsExactly("deep");
		assertThat(result.engine()).isEqualTo("shark");
		assertThat(result.warnings()).anyMatch(it -> it.contains("fallback"));
		assertThat(result.warnings()).anyMatch(it -> it.contains("boom"));
		assertThat(result.retentionSummary().warnings()).anyMatch(it -> it.contains("fallback"));
		assertThat(result.retentionSummary().warnings()).anyMatch(it -> it.contains("boom"));
	}

	@Test
	void fastRequestsStayOnSharkAndSkipHeavyAnalyzer() {
		RecordingAnalyzer heavy = RecordingAnalyzer.returning(sampleSharkFallbackResult());
		RecordingAnalyzer shark = RecordingAnalyzer.returning(sampleSharkFallbackResult());

		var orchestrator = new HeapRetentionAnalysisOrchestrator(shark, heavy);

		var result = orchestrator.analyze(Path.of("C:/tmp/demo.hprof"), 10, 12000, "fast", List.of(), List.of());

		assertThat(shark.calls()).containsExactly("fast");
		assertThat(heavy.calls()).isEmpty();
		assertThat(result.engine()).isEqualTo("shark");
	}

	@Test
	void balancedRequestsStayOnSharkAndSkipHeavyAnalyzer() {
		RecordingAnalyzer heavy = RecordingAnalyzer.returning(sampleSharkFallbackResult());
		RecordingAnalyzer shark = RecordingAnalyzer.returning(sampleSharkFallbackResult());

		var orchestrator = new HeapRetentionAnalysisOrchestrator(shark, heavy);

		var result = orchestrator.analyze(Path.of("C:/tmp/demo.hprof"), 10, 12000, "balanced", List.of(),
				List.of());

		assertThat(shark.calls()).containsExactly("balanced");
		assertThat(heavy.calls()).isEmpty();
		assertThat(result.engine()).isEqualTo("shark");
	}

	private static HeapRetentionAnalysisResult sampleSharkFallbackResult() {
		return new HeapRetentionAnalysisResult(true, "shark", List.of("shark used"), "",
				new HeapRetentionSummary(List.of(), List.of(), List.of(), List.of(),
						new HeapRetentionConfidence("medium", List.of(), List.of()), "summary", true, List.of(), ""),
				"summary");
	}

	private static final class RecordingAnalyzer implements HeapRetentionAnalyzer {

		private final HeapRetentionAnalysisResult result;

		private final RuntimeException failure;

		private final java.util.List<String> calls = new java.util.ArrayList<>();

		private RecordingAnalyzer(HeapRetentionAnalysisResult result, RuntimeException failure) {
			this.result = result;
			this.failure = failure;
		}

		static RecordingAnalyzer returning(HeapRetentionAnalysisResult result) {
			return new RecordingAnalyzer(result, null);
		}

		static RecordingAnalyzer throwing(RuntimeException failure) {
			return new RecordingAnalyzer(null, failure);
		}

		@Override
		public HeapRetentionAnalysisResult analyze(Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars,
				String analysisDepth, List<String> focusTypes, List<String> focusPackages) {
			calls.add(analysisDepth);
			if (failure != null) {
				throw failure;
			}
			return result;
		}

		List<String> calls() {
			return List.copyOf(calls);
		}

	}

}
