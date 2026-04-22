package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.nio.file.Path;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.offline.HeapDumpChunkRepository;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapRetentionAnalyzer;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineAnalysisService;
import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapDumpSummarizer;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OfflineMcpToolsRetentionTest {

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

	private static HeapRetentionSummary sampleSummary() {
		return new HeapRetentionSummary(List.of(), List.of(), List.of(), List.of(),
				new HeapRetentionConfidence("medium", List.of(), List.of()), "markdown", true, List.of(), "");
	}

}
