package com.alibaba.cloud.ai.examples.javatuning.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GcUnifiedLogParserTest {

	@Test
	void shouldSummarizeUnifiedGcPauseLines() {
		String log = """
				[2026-04-25T10:00:00.123+0800][info][gc] GC(12) Pause Young (Normal) (G1 Evacuation Pause) 128M->96M(512M) 12.345ms
				[2026-04-25T10:00:01.123+0800][info][gc] GC(13) Pause Young (Concurrent Start) (G1 Humongous Allocation) 300M->280M(512M) 35.000ms
				[2026-04-25T10:00:02.123+0800][info][gc] GC(14) To-space exhausted
				[2026-04-25T10:00:03.123+0800][info][gc] GC(15) Pause Full (G1 Compaction Pause) 450M->280M(512M) 210.000ms
				""";

		GcLogSummary summary = new GcUnifiedLogParser().parse(log);

		assertThat(summary.pauseEventCount()).isEqualTo(3);
		assertThat(summary.youngPauseCount()).isEqualTo(2);
		assertThat(summary.fullPauseCount()).isEqualTo(1);
		assertThat(summary.humongousAllocationCount()).isEqualTo(1);
		assertThat(summary.toSpaceExhaustedCount()).isEqualTo(1);
		assertThat(summary.maxPauseMs()).isEqualTo(210.0d);
		assertThat(summary.totalPauseMs()).isEqualTo(257.345d);
		assertThat(summary.maxHeapBeforeBytes()).isEqualTo(450L * 1024L * 1024L);
		assertThat(summary.minHeapAfterBytes()).isEqualTo(96L * 1024L * 1024L);
		assertThat(summary.topCauses()).containsEntry("G1 Evacuation Pause", 1L)
			.containsEntry("G1 Humongous Allocation", 1L)
			.containsEntry("G1 Compaction Pause", 1L);
		assertThat(summary.warnings()).isEmpty();
	}

	@Test
	void shouldWarnWhenTextContainsNoRecognizedPauseLines() {
		GcLogSummary summary = new GcUnifiedLogParser().parse("not a gc log");

		assertThat(summary.pauseEventCount()).isZero();
		assertThat(summary.warnings()).contains("GC log text was present but no unified GC pause lines were parsed");
	}

}
