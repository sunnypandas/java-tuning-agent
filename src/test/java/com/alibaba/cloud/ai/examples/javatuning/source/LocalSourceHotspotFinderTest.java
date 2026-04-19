package com.alibaba.cloud.ai.examples.javatuning.source;

import java.nio.file.Path;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HistogramClassNames;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalSourceHotspotFinderTest {

	private final LocalSourceHotspotFinder finder = new LocalSourceHotspotFinder();

	@Test
	void stripsModuleSuffixFromHistogramTokens() {
		assertThat(HistogramClassNames.stripModuleSuffix("com.foo.Bar (java.base@25.0.2)")).isEqualTo("com.foo.Bar");
		assertThat(HistogramClassNames.isPrimitiveArrayDescriptor("[B")).isTrue();
		assertThat(HistogramClassNames.isAnyArrayDescriptor("[Ljava.lang.String;")).isTrue();
	}

	@Test
	void prefersCandidatePackagesEvenWhenFrameworkTypesAreLarger() {
		ClassHistogramSummary histogram = new ClassHistogramParser().parse("""
				 1: 100 50000000 org.springframework.core.ResolvableType
				 2: 10 1024 com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord
				""");
		List<Path> roots = List.of(Path.of("compat/memory-leak-demo"));
		var hotspots = finder.hotspotsFromHistogram(roots, histogram,
				List.of("com.alibaba.cloud.ai.compat.memoryleakdemo"));
		assertThat(hotspots).isNotEmpty();
		assertThat(hotspots.get(0).className()).isEqualTo("com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord");
		assertThat(hotspots.get(0).fileHint()).contains("AllocationRecord.java");
	}

	@Test
	void moduleSuffixStillResolvesSourceFile() {
		ClassHistogramSummary histogram = new ClassHistogramParser().parse("""
				 1: 10 1024 com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord (java.base@25.0.2)
				""");
		List<Path> roots = List.of(Path.of("compat/memory-leak-demo"));
		var hotspots = finder.hotspotsFromHistogram(roots, histogram,
				List.of("com.alibaba.cloud.ai.compat.memoryleakdemo"));
		assertThat(hotspots).hasSize(1);
		assertThat(hotspots.get(0).fileHint()).contains("AllocationRecord.java");
	}

	@Test
	void skipsFrameworkTypesWhenNoCandidatePackagesMatch() {
		ClassHistogramSummary histogram = new ClassHistogramParser().parse("""
				 1: 100 50000000 org.springframework.core.ResolvableType
				 2: 50 1000000 ch.qos.logback.classic.Logger
				""");
		List<Path> roots = List.of(Path.of("compat/memory-leak-demo"));
		var hotspots = finder.hotspotsFromHistogram(roots, histogram, List.of("com.alibaba.cloud.ai.compat"));
		assertThat(hotspots).isEmpty();
	}
}
