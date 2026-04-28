package com.alibaba.cloud.ai.examples.javatuning.agent;

import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.JfrInsightsRule;
import com.alibaba.cloud.ai.examples.javatuning.advice.MemoryGcDiagnosisEngine;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrAllocationSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrCountAndBytes;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.source.LocalSourceHotspotFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ensures optional baseline / JFR on {@link TuningAdviceRequest} reach diagnosis (MCP mirrors this path for
 * lightweight collections).
 */
class JavaTuningWorkflowServiceOptionalEvidenceIntegrationTest {

	@Test
	void generateAdvicePassesInlineJfrSummaryIntoJfrInsightsRule() {
		JvmRuntimeCollector collector = (pid, ignored) -> snapshot(pid);
		JavaTuningWorkflowService service = new JavaTuningWorkflowService(collector,
				MemoryGcDiagnosisEngine.firstVersion(), new LocalSourceHotspotFinder());

		JvmRuntimeSnapshot snapshot = snapshot(42L);
		JfrSummary jfr = new JfrSummary(null, null, null, null,
				new JfrAllocationSummary(10_000L, List.of(new JfrCountAndBytes("hot.AllocPath", 100L, 8_000_000L)),
						List.of(), 50L),
				null, null, Map.of(), List.of());

		var report = service.generateAdvice(new TuningAdviceRequest(snapshot, CodeContextSummary.empty(), "local",
				"latency", null, null, jfr));

		assertThat(report.findings()).anyMatch(f -> JfrInsightsRule.ALLOCATION_TITLE.equals(f.title()));
	}
	private static JvmRuntimeSnapshot snapshot(long pid) {
		return new JvmRuntimeSnapshot(pid, new JvmMemorySnapshot(64L * 1024L * 1024L, 512L * 1024L * 1024L,
				1024L * 1024L * 1024L, null, null, null, null, null),
				new JvmGcSnapshot("G1", 10L, 50L, 2L, 120L, 40.0d), List.of(), "", null, null,
				new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
	}

}
