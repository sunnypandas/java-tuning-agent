package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineTargetConsistencyAnalyzerTest {

	private final OfflineTargetConsistencyAnalyzer analyzer = new OfflineTargetConsistencyAnalyzer();

	@Test
	void reportsMatchWhenOfflineEvidenceMatchesSourceContext() {
		OfflineBundleDraft draft = draftFor("1961",
				"com.alibaba.cloud.ai.compat.memoryleakdemo.MemoryLeakDemoApplication",
				"com.alibaba.cloud.ai.compat.memoryleakdemo.MemoryLeakDemoApplication");
		CodeContextSummary context = new CodeContextSummary(List.of(), Map.of(),
				List.of("MemoryLeakDemoApplication", "memory-leak-demo"), List.of(),
				List.of("com.alibaba.cloud.ai.compat.memoryleakdemo"));

		OfflineTargetConsistencyResult result = analyzer.analyze(draft, context);

		assertThat(result.targetMatched()).isTrue();
		assertThat(result.warnings()).isEmpty();
		assertThat(result.extractedJavaCommand()).contains("MemoryLeakDemoApplication");
		assertThat(result.extractedPids()).contains(1961L);
	}

	@Test
	void reportsMismatchWhenOfflineEvidenceTargetsDifferentApplication() {
		OfflineBundleDraft draft = draftFor("98662",
				"com.alibaba.cloud.ai.examples.javatuning.JavaTuningAgentApplication",
				"com.alibaba.cloud.ai.examples.javatuning.JavaTuningAgentApplication");
		CodeContextSummary context = new CodeContextSummary(List.of(), Map.of(),
				List.of("MemoryLeakDemoApplication", "memory-leak-demo"), List.of(),
				List.of("com.alibaba.cloud.ai.compat.memoryleakdemo"));

		OfflineTargetConsistencyResult result = analyzer.analyze(draft, context);

		assertThat(result.targetMatched()).isFalse();
		assertThat(result.warnings()).anyMatch(warning -> warning.contains("java_command"));
		assertThat(result.warnings()).anyMatch(warning -> warning.contains("MemoryLeakDemoApplication"));
		assertThat(result.warnings()).anyMatch(warning -> warning.contains("JavaTuningAgentApplication"));
	}

	private static OfflineBundleDraft draftFor(String pid, String identityCommand, String runtimeCommand) {
		return new OfflineBundleDraft("""
				%s:
				VM Arguments:
				java_command: %s
				""".formatted(pid, identityCommand), "JDK 25.0.3", """
				targetPid: %s
				sun.rt.javaCommand="%s"
				""".formatted(pid, runtimeCommand), new OfflineArtifactSource(null, pid + ":\nclass histogram"),
				new OfflineArtifactSource(null, pid + ":\nthread dump"), "/tmp/demo.hprof", true, true, false,
				null, null, null, "", "", "/tmp/repeated.json", Map.of());
	}

}
