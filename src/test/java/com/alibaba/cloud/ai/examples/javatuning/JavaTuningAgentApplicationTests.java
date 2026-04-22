package com.alibaba.cloud.ai.examples.javatuning;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JavaTuningAgentApplicationTests {

	@Autowired
	private ToolCallbackProvider toolCallbackProvider;

	@Test
	void contextLoads() {
	}

	@Test
	void shouldRegisterMcpToolCallbacks() {
		assertThat(Arrays.stream(toolCallbackProvider.getToolCallbacks())
			.map(callback -> callback.getToolDefinition().name())).contains("listJavaApps", "inspectJvmRuntime",
					"collectMemoryGcEvidence", "generateTuningAdvice", "validateOfflineAnalysisDraft",
					"submitOfflineHeapDumpChunk", "finalizeOfflineHeapDump", "generateOfflineTuningAdvice",
					"summarizeOfflineHeapDumpFile", "analyzeOfflineHeapRetention");
	}

}
