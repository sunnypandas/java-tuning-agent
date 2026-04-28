package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrAllocationSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrExecutionSampleSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrGcSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrThreadSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TuningAdviceRequestTest {

	@Test
	void shouldKeepLegacyConstructorsBackwardCompatible() {
		JvmRuntimeSnapshot snapshot = snapshot();
		TuningAdviceRequest legacy = new TuningAdviceRequest(snapshot, CodeContextSummary.empty(), "prod",
				"reduce-pause");
		TuningAdviceRequest withHistogram = new TuningAdviceRequest(snapshot, CodeContextSummary.empty(), "prod",
				"reduce-pause", null);

		assertThat(legacy.classHistogramHint()).isNull();
		assertThat(legacy.baselineEvidence()).isNull();
		assertThat(legacy.jfrSummary()).isNull();
		assertThat(withHistogram.baselineEvidence()).isNull();
		assertThat(withHistogram.jfrSummary()).isNull();
	}

	@Test
	void shouldAcceptOptionalBaselineEvidence() {
		MemoryGcEvidencePack baseline = new MemoryGcEvidencePack(snapshot(), null, null, List.of(), List.of(), null, null);
		TuningAdviceRequest request = new TuningAdviceRequest(snapshot(), CodeContextSummary.empty(), "prod",
				"reduce-pause", null, baseline);

		assertThat(request.baselineEvidence()).isNotNull();
		assertThat(request.baselineEvidence().snapshot().pid()).isEqualTo(11L);
		assertThat(request.jfrSummary()).isNull();
	}

	@Test
	void shouldAcceptOptionalJfrSummary() {
		JfrSummary jfr = new JfrSummary(1L, 2L, 1L, new JfrGcSummary(0L, 0.0d, 0.0d, List.of(), List.of()),
				new JfrAllocationSummary(0L, List.of(), List.of(), 0L), new JfrThreadSummary(0L, 0L, 0.0d, List.of()),
				new JfrExecutionSampleSummary(0L, List.of()), java.util.Map.of(), List.of());
		TuningAdviceRequest request = new TuningAdviceRequest(snapshot(), CodeContextSummary.empty(), "prod",
				"reduce-pause", null, null, jfr);

		assertThat(request.jfrSummary()).isSameAs(jfr);
	}

	private static JvmRuntimeSnapshot snapshot() {
		return new JvmRuntimeSnapshot(11L, new JvmMemorySnapshot(1L, 2L, 3L, null, null, null, null, null),
				new JvmGcSnapshot("G1", 1L, 1L, 0L, 0L, null), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
	}

}
