package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class DominatorStyleHeapRetentionAnalyzerTest {

	@Test
	void computesRetainedStyleBytesForDominatingStaticHolder(@TempDir Path dir) throws Exception {
		Path heap = TestHeapDumpSupport.dumpDominatingStaticOwnerHeap(dir);
		var analyzer = new DominatorStyleHeapRetentionAnalyzer(20, 12000);

		var result = analyzer.analyze(heap, 10, 12000, "deep", List.of("byte[]"), List.of());

		assertThat(result.analysisSucceeded()).isTrue();
		assertThat(result.engine()).isEqualTo("dominator-style");
		assertThat(result.retentionSummary().suspectedHolders())
			.anySatisfy(holder -> assertThat(holder.retainedBytesApprox()).isNotNull());
		assertThat(result.retentionSummary().retentionChains())
			.anySatisfy(chain -> assertThat(chain.segments())
				.anySatisfy(segment -> assertThat(segment.targetType()).isNotBlank()));
		assertThat(result.retentionSummary().retentionChains())
			.anySatisfy(chain -> {
				assertThat(chain.chainCountApprox()).isGreaterThan(1L);
				assertThat(chain.segments())
					.anySatisfy(segment -> assertThat(segment.referenceKind()).isEqualTo("array-element"));
			});
		assertThat(result.retentionSummary().suspectedHolders())
			.anySatisfy(holder -> {
				assertThat(holder.holderType()).contains("DominatedPayload[]");
				assertThat(holder.retainedBytesApprox()).isGreaterThan(163_840L);
				assertThat(holder.retainedObjectCountApprox()).isGreaterThan(1L);
			});
		assertThat(result.retentionSummary().classloaderRetainedGroups())
			.anySatisfy(group -> {
				assertThat(group.classLoaderName()).isNotBlank();
				assertThat(group.retainedBytesApprox()).isNotNull();
				assertThat(group.retainedBytesApprox()).isPositive();
				assertThat(group.exampleHolderType()).isNotBlank();
				assertThat(group.exampleTargetType()).isEqualTo("byte[]");
			});
		assertThat(result.summaryMarkdown()).contains("retained-style approximation")
			.contains("reachable subgraph estimate")
			.contains("Classloader retained groups")
			.contains("not MAT exact retained size")
			.contains("Likely source holder:");
	}

	@Test
	void keepsLargePayloadCandidatesWhenFocusPackagesAlsoMatchManySmallBusinessObjects(@TempDir Path dir)
			throws Exception {
		Path heap = TestHeapDumpSupport.dumpFocusPackagePreferenceHeap(dir);
		var analyzer = new DominatorStyleHeapRetentionAnalyzer(20, 12000);
		String businessType = TestHeapDumpSupport.preferredNodeTypeName();

		var result = analyzer.analyze(heap, 1, 12000, "deep", List.of("byte[]", businessType),
				List.of("com.alibaba.cloud.ai.examples.javatuning.offline"));

		assertThat(result.analysisSucceeded()).as(result.errorMessage()).isTrue();
		assertThat(result.retentionSummary().dominantRetainedTypes())
			.extracting(type -> type.typeName())
			.contains("byte[]");
		assertThat(result.retentionSummary().retentionChains())
			.anySatisfy(chain -> assertThat(chain.terminalType()).isEqualTo("byte[]"));
	}

	@Test
	void exposesConfiguredDeepBudgetInEngineNotes(@TempDir Path dir) throws Exception {
		Path heap = TestHeapDumpSupport.dumpDominatingStaticOwnerHeap(dir);
		var options = new DominatorStyleHeapRetentionOptions(7, 5, 4, 1_001, 1_002, 2_001, 2_002, 123);
		var analyzer = new DominatorStyleHeapRetentionAnalyzer(20, 12000, options);

		var result = analyzer.analyze(heap, 2, 12000, "deep", List.of("byte[]"), List.of());

		assertThat(result.analysisSucceeded()).as(result.errorMessage()).isTrue();
		assertThat(result.retentionSummary().confidenceAndLimits().engineNotes())
			.contains("candidateMultiplier=7")
			.contains("reverseDepthLimit=5")
			.contains("forwardDepthLimit=4")
			.contains("reverseNodeLimit=2001")
			.contains("forwardNodeLimit=2002")
			.contains("pathSearchNodeLimit=123");
	}

}
