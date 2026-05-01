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
		assertThat(result.summaryMarkdown()).contains("retained-style approximation")
			.contains("reachable subgraph estimate")
			.contains("not MAT exact retained size")
			.contains("Likely source holder:");
	}

}
