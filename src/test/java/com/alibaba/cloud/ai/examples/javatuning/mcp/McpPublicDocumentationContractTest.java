package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class McpPublicDocumentationContractTest {

	@Autowired
	private ToolCallbackProvider toolCallbackProvider;

	@Test
	void readmeAndCursorReferenceShouldMentionEveryPublicTool() throws Exception {
		Set<String> tools = Arrays.stream(toolCallbackProvider.getToolCallbacks())
			.map(callback -> callback.getToolDefinition().name())
			.collect(Collectors.toCollection(java.util.TreeSet::new));
		String readme = Files.readString(Path.of("README.md"));
		String skill = Files.readString(Path.of(".cursor/skills/java-tuning-agent-workflow/SKILL.md"));
		String reference = Files.readString(Path.of(".cursor/skills/java-tuning-agent-workflow/reference.md"));

		for (String tool : tools) {
			assertThat(readme).as("README should mention " + tool).contains(tool);
			assertThat(reference).as("Cursor reference should mention " + tool).contains(tool);
		}
		assertThat(skill).contains("inspectJvmRuntimeRepeated", "recordJvmFlightRecording", "jfrOutputPath");
		assertThat(readme).contains("analysisDepth", "heapDumpAbsolutePath", "confirmationToken", "sampleCount",
				"intervalMillis", "recordJvmFlightRecording", "durationSeconds", "settings", "jfrOutputPath",
				"maxSummaryEvents");
		assertThat(reference).contains("analysisDepth", "heapDumpAbsolutePath", "confirmationToken", "sampleCount",
				"intervalMillis", "recordJvmFlightRecording", "durationSeconds", "settings", "jfrOutputPath",
				"maxSummaryEvents");
	}

	@Test
	void readmeShouldStateCurrentToolCount() throws Exception {
		int toolCount = toolCallbackProvider.getToolCallbacks().length;
		String readme = Files.readString(Path.of("README.md"));

		assertThat(readme).contains("**" + toolCount + "** tools");
	}

}
