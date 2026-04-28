package com.alibaba.cloud.ai.examples.javatuning.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NativeMemorySummaryParserTest {

	private final NativeMemorySummaryParser parser = new NativeMemorySummaryParser();

	@Test
	void parsesTotalsAndKeyCategories() {
		NativeMemorySummary summary = parser.parse("""
				Native Memory Tracking:
				Total: reserved=2097152K, committed=1048576K
				-                     Java Heap (reserved=1048576K, committed=524288K)
				-                          Class (reserved=262144K, committed=131072K)
				-                            NIO (reserved=131072K, committed=65536K)
				""");
		assertThat(summary.totalReservedBytes()).isEqualTo(2097152L * 1024L);
		assertThat(summary.totalCommittedBytes()).isEqualTo(1048576L * 1024L);
		assertThat(summary.classCommittedBytes()).isEqualTo(131072L * 1024L);
		assertThat(summary.directCommittedBytes()).isEqualTo(65536L * 1024L);
		assertThat(summary.categories()).containsKeys("class", "nio");
		assertThat(summary.categoryGrowth()).isEmpty();
	}

	@Test
	void parsesSummaryDiffCategoryGrowthWhenPresent() {
		NativeMemorySummary summary = parser.parse("""
				VM.native_memory summary.diff
				Total: reserved=+512M, committed=+384M
				-                            NIO (reserved=+64M, committed=+48M)
				-                          Class (reserved=+128M, committed=+96M)
				-                        Thread (reserved=-16M, committed=-16M)
				""");

		assertThat(summary.totalReservedBytes()).isEqualTo(512L * 1024L * 1024L);
		assertThat(summary.totalCommittedBytes()).isEqualTo(384L * 1024L * 1024L);
		assertThat(summary.categoryGrowth()).containsKeys("nio", "class", "thread");
		assertThat(summary.categoryGrowth().get("nio").reservedDeltaBytes()).isEqualTo(64L * 1024L * 1024L);
		assertThat(summary.categoryGrowth().get("thread").committedDeltaBytes()).isEqualTo(-16L * 1024L * 1024L);
	}

	@Test
	void parsesHotspotSummaryDiffAbsoluteAndDeltaColumns() {
		NativeMemorySummary summary = parser.parse("""
				VM.native_memory summary.diff
				Total: reserved=2097152KB +131072KB, committed=1048576KB +98304KB
				-                            NIO (reserved=262144KB +65536KB, committed=225280KB +51200KB)
				-                          Class (reserved=655360KB +92160KB, committed=552960KB +71680KB)
				""");

		assertThat(summary.totalReservedBytes()).isEqualTo(131072L * 1024L);
		assertThat(summary.totalCommittedBytes()).isEqualTo(98304L * 1024L);
		assertThat(summary.categoryGrowth().get("nio").committedDeltaBytes()).isEqualTo(51200L * 1024L);
		assertThat(summary.categoryGrowth().get("class").reservedDeltaBytes()).isEqualTo(92160L * 1024L);
	}

}
