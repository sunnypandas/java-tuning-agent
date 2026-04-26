package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.runtime.GcLogSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GcLogInsightsRuleTest {

	@Test
	void shouldReportLongFullGcPauseFromImportedGcLog() {
		GcLogSummary summary = new GcLogSummary(4, 2, 1, 1, 980.0d, 1_120.0d, 480L * 1024L * 1024L,
				180L * 1024L * 1024L, 1, 0, Map.of("G1 Compaction Pause", 1L), List.of());
		MemoryGcEvidencePack evidence = stableEvidence().withGcLogSummary(summary);
		DiagnosisScratch scratch = new DiagnosisScratch();

		new GcLogInsightsRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.findings()).extracting(TuningFinding::title)
			.contains(GcLogInsightsRule.LONG_PAUSE_TITLE, GcLogInsightsRule.FULL_GC_TITLE);
		assertThat(scratch.nextSteps()).anyMatch(step -> step.contains("JFR") || step.contains("heap dump"));
	}

	private static MemoryGcEvidencePack stableEvidence() {
		JvmRuntimeSnapshot snapshot = new JvmRuntimeSnapshot(100L,
				new JvmMemorySnapshot(128L * 1024L * 1024L, 512L * 1024L * 1024L, 512L * 1024L * 1024L, null, null,
						null, null, null),
				new JvmGcSnapshot("G1", 10L, 100L, 0L, 0L, 35.0d), List.of("-XX:+UseG1GC"), "", 20L, 1_000L,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
		return new MemoryGcEvidencePack(snapshot, null, null, List.of(), List.of(), null, null);
	}

}
