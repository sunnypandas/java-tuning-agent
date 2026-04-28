package com.alibaba.cloud.ai.examples.javatuning;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JavaTuningAgentApplicationTests {

	@Autowired
	private ToolCallbackProvider toolCallbackProvider;

	@Autowired
	private Environment environment;

	@Test
	void contextLoads() {
	}

	@Test
	void shouldRegisterMcpToolCallbacks() {
		assertThat(Arrays.stream(toolCallbackProvider.getToolCallbacks())
			.map(callback -> callback.getToolDefinition().name())).contains("listJavaApps", "inspectJvmRuntime",
					"collectMemoryGcEvidence", "generateTuningAdvice", "generateTuningAdviceFromEvidence",
					"validateOfflineAnalysisDraft", "submitOfflineHeapDumpChunk", "finalizeOfflineHeapDump",
					"generateOfflineTuningAdvice", "summarizeOfflineHeapDumpFile", "analyzeOfflineHeapRetention");
	}

	@Test
	void shouldNotForceSpringKeepAliveForStdioMcpLifecycle() {
		assertThat(environment.getProperty("spring.main.keep-alive", Boolean.class)).isFalse();
	}

}
