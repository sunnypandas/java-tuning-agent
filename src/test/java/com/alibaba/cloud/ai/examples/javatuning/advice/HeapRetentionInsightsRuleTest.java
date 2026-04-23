package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.runtime.GcRootHint;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RetentionChainSegment;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RetentionChainSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SuspectedHolderSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeapRetentionInsightsRuleTest {

	@Test
	void shouldEmitFindingForLargeStaticHolderRetentionEvidence() {
		MemoryGcEvidencePack evidence = evidencePack(sampleRetentionResult("dominator-style", List.of()));
		DiagnosisScratch scratch = new DiagnosisScratch();

		new HeapRetentionInsightsRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.findings()).hasSize(1);
		assertThat(scratch.findings().get(0).title()).isEqualTo(HeapRetentionInsightsRule.FINDING_TITLE);
		assertThat(scratch.findings().get(0).severity()).isEqualTo("medium");
		assertThat(scratch.findings().get(0).evidence()).contains("holderType=com.example.CacheHolder")
			.contains("holderRole=static-field-owner")
			.contains("holder-oriented retained-style approximation");
		assertThat(scratch.recommendations()).isNotEmpty();
		assertThat(scratch.nextSteps()).anyMatch(step -> step.contains("CacheHolder"));
	}

	@Test
	void shouldUseConservativeWordingForSharkFallbackRetentionEvidence() {
		List<String> warnings = List.of("Deep retained-style analysis fallback to Shark: out of memory");
		MemoryGcEvidencePack evidence = evidencePack(sampleRetentionResult("shark", warnings));
		DiagnosisScratch scratch = new DiagnosisScratch();

		new HeapRetentionInsightsRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.findings()).hasSize(1);
		assertThat(scratch.findings().get(0).severity()).isEqualTo("medium");
		assertThat(scratch.findings().get(0).evidence()).contains("engine=shark")
			.contains("warnings=Deep retained-style analysis fallback to Shark: out of memory")
			.contains("retention hint");
		assertThat(scratch.nextSteps()).anyMatch(step -> step.contains("fallback limit"));
	}

	@Test
	void shouldIndicateWhenWarningsAreTruncated() {
		MemoryGcEvidencePack evidence = evidencePack(sampleRetentionResult("shark",
				List.of("warn-1", "warn-2", "warn-3", "warn-4")));
		DiagnosisScratch scratch = new DiagnosisScratch();

		new HeapRetentionInsightsRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.findings().get(0).evidence()).contains("(+2 more warnings)");
	}

	@Test
	void shouldSkipWhenRetentionAnalysisIsMissingOrFailed() {
		DiagnosisScratch missingScratch = new DiagnosisScratch();
		DiagnosisScratch failedScratch = new DiagnosisScratch();
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(stubSnapshot(), null, null, List.of(), List.of(), null,
				null, null);

		new HeapRetentionInsightsRule().evaluate(evidence, CodeContextSummary.empty(), missingScratch);
		new HeapRetentionInsightsRule().evaluate(evidence.withHeapRetentionAnalysis(new HeapRetentionAnalysisResult(false,
				"dominator-style", List.of(), "failed", HeapRetentionSummary.empty(), "")),
				CodeContextSummary.empty(), failedScratch);

		assertThat(missingScratch.findings()).isEmpty();
		assertThat(failedScratch.findings()).isEmpty();
	}

	private static MemoryGcEvidencePack evidencePack(HeapRetentionAnalysisResult retention) {
		return new MemoryGcEvidencePack(stubSnapshot(), null, null, List.of(), List.of(), null, null, retention);
	}

	private static JvmRuntimeSnapshot stubSnapshot() {
		return new JvmRuntimeSnapshot(123L, new JvmMemorySnapshot(0L, 0L, 0L, null, null, null, null, null),
				new JvmGcSnapshot("unknown", 0L, 0L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
	}

	private static HeapRetentionAnalysisResult sampleRetentionResult(String engine, List<String> warnings) {
		HeapRetentionSummary summary = new HeapRetentionSummary(
				List.of(),
				List.of(new SuspectedHolderSummary("com.example.CacheHolder", "static-field-owner", 12_582_912L,
						12_582_912L, 4L, "com.example.CacheHolder.INSTANCE", "java.util.Map",
						"static holder with a large reachable subgraph")),
				List.of(new RetentionChainSummary("system-class",
						List.of(new RetentionChainSegment("com.example.CacheHolder", "static-field", "INSTANCE",
								"com.example.CacheHolder")), "com.example.CacheHolder", 64L, 2L, 12_582_912L,
						12_582_912L)),
				List.of(new GcRootHint("system-class", "com.example.CacheHolder", 1L, "static holder hint")),
				new HeapRetentionConfidence("medium", List.of("retained bytes are approximate"), List.of("Engine=" + engine)),
				"### Heap retention analysis\n\nEngine=" + engine, true, warnings, "");
		return new HeapRetentionAnalysisResult(true, engine, warnings, "", summary,
				"### Heap retention analysis\n\nEngine=" + engine);
	}
}
