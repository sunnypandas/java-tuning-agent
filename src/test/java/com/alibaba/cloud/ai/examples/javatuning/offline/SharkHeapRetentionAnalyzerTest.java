package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.SuspectedHolderSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SharkHeapRetentionAnalyzerTest {

	@Test
	void analyzesStaticHolderChainForRetainedByteArrays(@TempDir Path dir) throws Exception {
		Path heap = TestHeapDumpSupport.dumpStaticRetainedBytesHeap(dir);
		var analyzer = new SharkHeapRetentionAnalyzer(20, 12000);

		var result = analyzer.analyze(heap, 10, 12000, "balanced", List.of("byte[]"), List.of());

		assertThat(result.analysisSucceeded()).isTrue();
		assertThat(result.retentionSummary().analysisSucceeded()).isTrue();
		assertThat(result.retentionSummary().suspectedHolders())
			.extracting(SuspectedHolderSummary::holderRole)
			.contains("static-field-owner");
		assertThat(result.retentionSummary().retentionChains())
			.anySatisfy(chain -> assertThat(chain.terminalType()).isEqualTo("byte[]"));
		assertThat(result.retentionSummary().confidenceAndLimits().limitations())
			.anySatisfy(limit -> assertThat(limit).contains("Retained bytes"));
		assertThat(result.summaryMarkdown()).contains("Heap retention analysis");
		assertThat(result.summaryMarkdown()).contains("reachable subgraph");
		assertThat(result.summaryMarkdown()).contains("not full dominator retained-size");
		assertThat(result.retentionSummary().summaryMarkdown()).isEqualTo(result.summaryMarkdown());
	}

	@Test
	void markdownIsBoundedAndUsesApproximateLanguage(@TempDir Path dir) throws Exception {
		Path heap = TestHeapDumpSupport.dumpStaticRetainedBytesHeap(dir);
		var analyzer = new SharkHeapRetentionAnalyzer(20, 260);

		var result = analyzer.analyze(heap, 10, 260, "balanced", List.of("byte[]"), List.of());

		assertThat(result.analysisSucceeded()).isTrue();
		assertThat(result.summaryMarkdown().length()).isLessThanOrEqualTo(260);
		assertThat(result.summaryMarkdown()).contains("retention hint");
		assertThat(result.summaryMarkdown()).contains("reachable subgraph");
	}

	@Test
	void missingFileReturnsFailureResult() {
		var analyzer = new SharkHeapRetentionAnalyzer(20, 12000);
		var result = analyzer.analyze(Path.of("missing.hprof"), 10, 12000, "balanced", List.of(), List.of());

		assertThat(result.analysisSucceeded()).isFalse();
		assertThat(result.errorMessage()).contains("Not a regular file");
		assertThat(result.retentionSummary().analysisSucceeded()).isFalse();
	}

	@Test
	@SuppressWarnings({ "unchecked", "rawtypes" })
	void candidateComparatorPrefersConfiguredPackagesBeforeNonMatchingTypes() throws Exception {
		Class<?> candidateClass = Class.forName(
				"com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapRetentionAnalyzer$Candidate");
		var constructor = candidateClass.getDeclaredConstructor(long.class, String.class, long.class);
		constructor.setAccessible(true);
		Object preferred = constructor.newInstance(1L, "java.lang.StringBuilder", 16L);
		Object nonMatching = constructor.newInstance(2L, "java.net.URI", 1024L);

		var method = SharkHeapRetentionAnalyzer.class.getDeclaredMethod("candidateComparator", List.class);
		method.setAccessible(true);
		Comparator comparator = (Comparator) method.invoke(null, List.of("java.lang"));

		List<Object> candidates = new ArrayList<>(List.of(nonMatching, preferred));
		candidates.sort(comparator);

		var typeNameAccessor = candidateClass.getDeclaredMethod("typeName");
		typeNameAccessor.setAccessible(true);
		assertThat(typeNameAccessor.invoke(candidates.get(0))).isEqualTo("java.lang.StringBuilder");
	}

}
