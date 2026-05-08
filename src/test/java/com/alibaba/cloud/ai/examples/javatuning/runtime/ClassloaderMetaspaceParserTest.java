package com.alibaba.cloud.ai.examples.javatuning.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassloaderMetaspaceParserTest {

	private final ClassloaderMetaspaceParser parser = new ClassloaderMetaspaceParser();

	@Test
	void parsesVmClassloaderStatsRows() {
		ClassloaderMetaspaceSummary summary = parser.parse("""
				ClassLoader Parent CLD* Classes ChunkSz BlockSz Type
				0x1 0x0 0x2 1,200 65,536 32,768 com.example.ProxyClassLoader
				0x3 0x0 0x4 25 4096 2048 jdk.internal.loader.ClassLoaders$AppClassLoader
				""");

		assertThat(summary.entries()).hasSize(2);
		assertThat(summary.totalClassCount()).isEqualTo(1_225L);
		assertThat(summary.totalBytes()).isEqualTo(104_448L);
		assertThat(summary.topByClassCount(1).get(0).classLoaderName()).isEqualTo("com.example.ProxyClassLoader");
	}

	@Test
	void parsesJmapClstatsRows() {
		ClassloaderMetaspaceSummary summary = parser.parse("""
				ClassLoader Parent Alive Classes Bytes Type
				0x10 0x0 true 850 49,152 com.example.PluginClassLoader
				""");

		assertThat(summary.entries()).hasSize(1);
		ClassloaderMetaspaceEntry entry = summary.entries().get(0);
		assertThat(entry.alive()).isTrue();
		assertThat(entry.classCount()).isEqualTo(850L);
		assertThat(entry.bytes()).isEqualTo(49_152L);
		assertThat(entry.classLoaderName()).isEqualTo("com.example.PluginClassLoader");
	}

	@Test
	void warnsWhenNonBlankTextCannotBeParsed() {
		ClassloaderMetaspaceSummary summary = parser.parse("""
				this is metaspace context but not a classloader stats table
				another diagnostic paragraph
				""");

		assertThat(summary.entries()).isEmpty();
		assertThat(summary.warnings()).anyMatch(w -> w.contains("Unable to parse classloader metaspace evidence"));
	}

}
