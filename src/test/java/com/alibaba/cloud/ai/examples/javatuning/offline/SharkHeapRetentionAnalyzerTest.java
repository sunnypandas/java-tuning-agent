package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.alibaba.cloud.ai.examples.javatuning.runtime.RetentionChainSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SuspectedHolderSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SharkHeapRetentionAnalyzerTest {

	@Test
	void analyzesStaticHolderChainForRetainedByteArrays(@TempDir Path dir) throws Exception {
		Path heap = TestHeapDumpSupport.dumpStaticRetainedBytesHeap(dir);
		var analyzer = new SharkHeapRetentionAnalyzer(20, 12000);

		var result = analyzer.analyze(heap, 10, 12000, "balanced", List.of("byte[]"), List.of());

		assertThat(result.analysisSucceeded()).as(result.errorMessage()).isTrue();
		assertThat(result.engine()).isEqualTo("shark");
		assertThat(result.retentionSummary().analysisSucceeded()).isTrue();
		assertThat(result.retentionSummary().suspectedHolders())
			.anySatisfy(holder -> {
				assertThat(holder.holderRole()).isEqualTo("static-field-owner");
				assertThat(holder.retainedBytesApprox()).isNull();
			});
		assertThat(result.retentionSummary().retentionChains())
			.anySatisfy(chain -> assertThat(chain.terminalType()).isEqualTo("byte[]"));
		assertThat(result.retentionSummary().confidenceAndLimits().limitations())
			.anySatisfy(limit -> assertThat(limit).contains("Retained bytes"));
		assertThat(result.summaryMarkdown()).contains("Heap retention analysis");
		assertThat(result.summaryMarkdown()).contains("reachable subgraph");
		assertThat(result.summaryMarkdown()).contains("not MAT exact retained size");
		assertThat(result.retentionSummary().summaryMarkdown()).isEqualTo(result.summaryMarkdown());
	}

	@Test
	void markdownIsBoundedAndUsesApproximateLanguage(@TempDir Path dir) throws Exception {
		Path heap = TestHeapDumpSupport.dumpStaticRetainedBytesHeap(dir);
		var analyzer = new SharkHeapRetentionAnalyzer(20, 260);

		var result = analyzer.analyze(heap, 10, 260, "balanced", List.of("byte[]"), List.of());

		assertThat(result.analysisSucceeded()).as(result.errorMessage()).isTrue();
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
	void focusPackagesAffectsCandidateLimitingInPublicAnalyzerBehavior(@TempDir Path dir) throws Exception {
		Path heap = TestHeapDumpSupport.dumpFocusPackagePreferenceHeap(dir);
		var analyzer = new SharkHeapRetentionAnalyzer(20, 12000);
		String preferredType = TestHeapDumpSupport.preferredNodeArrayTypeName();
		List<String> focusTypes = List.of("java.lang.String[]", preferredType);

		var withoutFocusPackages = analyzer.analyze(heap, 2, 12000, "balanced", focusTypes, List.of());
		var withFocusPackages = analyzer.analyze(heap, 2, 12000, "balanced", focusTypes,
				List.of("com.alibaba.cloud.ai.examples.javatuning.offline"));

		assertThat(withoutFocusPackages.analysisSucceeded()).as(withoutFocusPackages.errorMessage()).isTrue();
		assertThat(withFocusPackages.analysisSucceeded()).as(withFocusPackages.errorMessage()).isTrue();
		assertThat(withoutFocusPackages.retentionSummary().dominantRetainedTypes())
			.extracting(type -> type.typeName())
			.doesNotContain(preferredType);
		assertThat(withFocusPackages.retentionSummary().dominantRetainedTypes())
			.extracting(type -> type.typeName())
			.contains(preferredType);
	}

	@Test
	void earlierFocusPackageEntryOutranksLaterMatchWhenBothMatch(@TempDir Path dir) throws Exception {
		Path heap = TestHeapDumpSupport.dumpFocusPackagePreferenceHeap(dir);
		var analyzer = new SharkHeapRetentionAnalyzer(20, 12000);
		String preferredType = TestHeapDumpSupport.preferredNodeArrayTypeName();
		String secondaryType = TestHeapDumpSupport.secondaryNodeArrayTypeName();
		List<String> focusTypes = List.of(preferredType, secondaryType);
		String broadPackage = "com.alibaba.cloud.ai.examples.javatuning.offline";
		String preferredPrefix = preferredType.substring(0, preferredType.length() - 2);

		var broadFirst = analyzer.analyze(heap, 1, 12000, "balanced", focusTypes, List.of(broadPackage, preferredPrefix));
		var specificFirst = analyzer.analyze(heap, 1, 12000, "balanced", focusTypes, List.of(preferredPrefix, broadPackage));

		assertThat(broadFirst.analysisSucceeded()).as(broadFirst.errorMessage()).isTrue();
		assertThat(specificFirst.analysisSucceeded()).as(specificFirst.errorMessage()).isTrue();
		assertThat(broadFirst.retentionSummary().retentionChains())
			.extracting(RetentionChainSummary::terminalType)
			.doesNotContain(preferredType);
		assertThat(specificFirst.retentionSummary().retentionChains())
			.extracting(RetentionChainSummary::terminalType)
			.contains(preferredType);
	}

	@Test
	void dominantTypeShareUsesTrackedReachableApproximationBasis(@TempDir Path dir) throws Exception {
		Path heap = TestHeapDumpSupport.dumpFocusPackagePreferenceHeap(dir);
		var analyzer = new SharkHeapRetentionAnalyzer(20, 12000);
		String preferredType = TestHeapDumpSupport.preferredNodeArrayTypeName();
		String secondaryType = TestHeapDumpSupport.secondaryNodeArrayTypeName();

		var result = analyzer.analyze(heap, 8, 12000, "balanced", List.of(preferredType, secondaryType), List.of());

		assertThat(result.analysisSucceeded()).as(result.errorMessage()).isTrue();
		long totalReachableBytes = result.retentionSummary().retentionChains()
			.stream()
			.mapToLong(RetentionChainSummary::reachableSubgraphBytesApprox)
			.sum();
		var dominantTypesByName = result.retentionSummary().dominantRetainedTypes()
			.stream()
			.collect(java.util.stream.Collectors.toMap(type -> type.typeName(), Function.identity()));
		var reachableBytesByType = result.retentionSummary().retentionChains()
			.stream()
			.collect(java.util.stream.Collectors.groupingBy(RetentionChainSummary::terminalType,
					java.util.stream.Collectors.summingLong(RetentionChainSummary::reachableSubgraphBytesApprox)));

		assertThat(totalReachableBytes).isPositive();
		assertThat(dominantTypesByName).containsKeys(preferredType, secondaryType);
		reachableBytesByType.entrySet().stream()
			.sorted(Comparator.comparingLong((Map.Entry<String, Long> entry) -> entry.getValue()).reversed())
			.forEach(entry -> {
				double expectedShare = 100.0d * entry.getValue() / (double) totalReachableBytes;
				assertThat(dominantTypesByName.get(entry.getKey()).shareOfTrackedRetainedApprox())
					.isCloseTo(expectedShare, within(0.0001d));
			});
	}

	@Test
	void reachableSubgraphUsesBoundedGraphApproximation(@TempDir Path dir) throws Exception {
		Path heap = TestHeapDumpSupport.dumpFocusPackagePreferenceHeap(dir);
		var analyzer = new SharkHeapRetentionAnalyzer(20, 12000);
		String preferredType = TestHeapDumpSupport.preferredNodeArrayTypeName();

		var result = analyzer.analyze(heap, 2, 12000, "balanced", List.of(preferredType), List.of());

		assertThat(result.analysisSucceeded()).as(result.errorMessage()).isTrue();
		assertThat(result.retentionSummary().retentionChains())
			.anySatisfy(chain -> {
				assertThat(chain.terminalType()).isEqualTo(preferredType);
				assertThat(chain.reachableSubgraphBytesApprox()).isGreaterThan(chain.terminalShallowBytes());
				assertThat(chain.retainedBytesApprox()).isNull();
			});
	}

}
