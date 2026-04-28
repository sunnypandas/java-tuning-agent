package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
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

		assertThat(toolCount).isEqualTo(13);
		assertThat(readme).contains("**" + toolCount + "** tools");
	}

	@Test
	void publicDocsShouldDescribeCurrentEvidenceSurface() throws Exception {
		String readme = Files.readString(Path.of("README.md"));
		String skill = Files.readString(Path.of(".cursor/skills/java-tuning-agent-workflow/SKILL.md"));
		String reference = Files.readString(Path.of(".cursor/skills/java-tuning-agent-workflow/reference.md"));
		String offlineSpec = Files.readString(Path.of("docs/offline-mode-spec.md"));
		String walkthrough = Files.readString(Path.of("docs/mcp-jvm-tuning-demo-walkthrough.md"));

		assertThat(readme).contains("**13** tools", "seven for **live JVM**", "six for **offline / imported**",
				"baselineEvidence", "jfrSummary", "repeatedSamplingResult", "resourceBudgetEvidence",
				"nativeMemorySummary", "analysisDepth=deep");
		assertThat(skill).contains("inspectJvmRuntimeRepeated", "recordJvmFlightRecording", "nativeMemorySummary",
				"backgroundNotes.resourceBudget", "analysisDepth: \"deep\"");
		assertThat(reference).contains("nativeMemorySummary", "repeatedSamplesPathOrText",
				"backgroundNotes.resourceBudget", "resourceBudgetEvidence", "`fast`, `balanced`, or `deep`");
		assertThat(offlineSpec).contains("13", "7 个在线 + 6 个离线", "repeatedSamplesPathOrText",
				"backgroundNotes.resourceBudget", "nativeMemorySummary", "analysisDepth=deep");
		assertThat(walkthrough).contains("共 13 个", "repeatedSamplesPathOrText", "nativeMemorySummary",
				"backgroundNotes.resourceBudget", "analysisDepth=\"deep\"");
	}

	@Test
	void publicDocsShouldNotContainRetiredPublicClaims() throws Exception {
		for (String doc : publicDocTexts()) {
			assertThat(doc).doesNotContain("九个 MCP 工具")
				.doesNotContain("**9** tools")
				.doesNotContain("**10** tools")
				.doesNotContain("**11** tools")
				.doesNotContain("**12** tools")
				.doesNotContain("does not feed JFR findings into `generateTuningAdvice` yet")
				.doesNotContain("Do not make `generateTuningAdvice` consume JFR summaries in this phase")
				.doesNotContain("thread dump 未实现")
				.doesNotContain("thread dump not implemented");
		}
	}

	@Test
	void publicDocsShouldRecommendEvidenceReusePathAfterCollection() throws Exception {
		String readme = Files.readString(Path.of("README.md"));
		String skill = Files.readString(Path.of(".cursor/skills/java-tuning-agent-workflow/SKILL.md"));
		String reference = Files.readString(Path.of(".cursor/skills/java-tuning-agent-workflow/reference.md"));
		String rule = Files.readString(Path.of(".cursor/rules/java-tuning-agent-mcp.mdc"));
		String offlineDesign = Files
			.readString(Path.of("docs/superpowers/specs/2026-04-19-offline-mode-design.md"));

		assertThat(readme).contains("seven for **live JVM**", "generateTuningAdviceFromEvidence",
				"not collected again", "Evidence-backed advice (two calls, no double collection)");
		assertThat(skill).contains("generateTuningAdviceFromEvidence", "no second collection",
				"do not call `generateTuningAdvice` with matching privileged flags",
				"one-shot collect-and-advise shortcut");
		assertThat(reference).contains("generateTuningAdviceFromEvidence",
				"That path collects again", "Existing clients can keep using it unchanged");
		assertThat(readme).contains("Histogram-inclusive (one call)",
				"Use `generateTuningAdvice` only as a one-shot shortcut");
		assertThat(rule).contains("generateTuningAdviceFromEvidence", "analyzeOfflineHeapRetention");
		assertThat(offlineDesign).contains("generateTuningAdviceFromEvidence(evidence, ...)",
				"不会再次采集 histogram/thread/heap");

		assertThat(readme)
			.doesNotContain("six for **live JVM**")
			.doesNotContain("`collectMemoryGcEvidence(request)` → `generateTuningAdvice(...)`");
		assertThat(offlineDesign).doesNotContain(
				"`collectMemoryGcEvidence(request)` → `generateTuningAdvice(...)`");
	}

	private static List<String> publicDocTexts() throws Exception {
		return List.of(Files.readString(Path.of("README.md")),
				Files.readString(Path.of("docs/offline-mode-spec.md")),
				Files.readString(Path.of("docs/mcp-jvm-tuning-demo-walkthrough.md")),
				Files.readString(Path.of(".cursor/skills/java-tuning-agent-workflow/SKILL.md")),
				Files.readString(Path.of(".cursor/skills/java-tuning-agent-workflow/reference.md")),
				Files.readString(Path.of(".cursor/rules/java-tuning-agent-mcp.mdc")));
	}

}
