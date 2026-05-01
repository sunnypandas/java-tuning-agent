package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceGapRuleTest {

	@Test
	void shouldAddCommandLevelNmtGuidanceWhenNativeSummaryIsMissing() {
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot(), null, null,
				List.of("nativeMemorySummary"), List.of(), null, null);
		DiagnosisScratch scratch = new DiagnosisScratch();

		new EvidenceGapRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

		assertThat(scratch.nextSteps()).anyMatch(step -> step.contains("Restart with -XX:NativeMemoryTracking=summary"));
		assertThat(scratch.nextSteps()).anyMatch(step -> step.contains("Collect jcmd 1961 VM.native_memory summary"));
		assertThat(scratch.nextSteps()).anyMatch(step -> step.contains("Use collectMemoryGcEvidence or offline nativeMemorySummary"));
	}

	private static JvmRuntimeSnapshot snapshot() {
		return new JvmRuntimeSnapshot(1961L,
				new JvmMemorySnapshot(512L * 1024L * 1024L, 768L * 1024L * 1024L, 1024L * 1024L * 1024L, null,
						null, null, null, null),
				new JvmGcSnapshot("G1", 0L, 0L, 0L, 0L, 40.0d), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
	}

}
