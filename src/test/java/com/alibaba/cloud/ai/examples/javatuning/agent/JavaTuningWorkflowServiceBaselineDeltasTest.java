package com.alibaba.cloud.ai.examples.javatuning.agent;

import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.MemoryGcDiagnosisEngine;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.runtime.DiagnosisWindow;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;
import com.alibaba.cloud.ai.examples.javatuning.source.LocalSourceHotspotFinder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaTuningWorkflowServiceBaselineDeltasTest {

	@Test
	void shouldAppendDeterministicKeyDeltasWhenBaselineProvided() {
		JavaTuningWorkflowService service = service();
		MemoryGcEvidencePack baseline = evidence(1L, 256, 10L, 200L, 1L, 120L, 700);
		MemoryGcEvidencePack current = evidence(2L, 384, 25L, 460L, 3L, 240L, 920).withBaselineEvidence(baseline);

		TuningAdviceReport report = service.generateAdviceFromEvidence(current, CodeContextSummary.empty(), "prod",
				"diagnose-memory");

		assertThat(report.formattedSummary()).contains("## Key Deltas");
		assertThat(report.formattedSummary()).contains("- heap used: +128 MiB (256 MiB baseline -> 384 MiB)");
		assertThat(report.formattedSummary())
			.contains("- GC events/time: young +15 events, full +2 events; time young +260 ms, full +120 ms");
		assertThat(report.formattedSummary())
			.contains("- native committed: +220 MiB (700 MiB baseline -> 920 MiB)");
		assertThat(report.formattedSummary())
			.contains("- native category growth: Class +120 MiB committed (+160 MiB reserved), NIO +80 MiB committed (+90 MiB reserved)");
	}

	@Test
	void shouldKeepReportShapeUnchangedWithoutBaseline() {
		JavaTuningWorkflowService service = service();
		MemoryGcEvidencePack current = evidence(2L, 384, 25L, 460L, 3L, 240L, 920);

		TuningAdviceReport report = service.generateAdviceFromEvidence(current, CodeContextSummary.empty(), "prod",
				"diagnose-memory");

		assertThat(report.formattedSummary()).doesNotContain("## Key Deltas");
		assertThat(report.formattedSummary()).contains("## Findings");
		assertThat(report.formattedSummary()).contains("## Confidence");
	}

	@Test
	void shouldAppendDiagnosisContextWhenEvidenceWindowIsPresent() {
		JavaTuningWorkflowService service = service();
		MemoryGcEvidencePack current = evidence(2L, 384, 25L, 460L, 3L, 240L, 920)
			.withDiagnosisWindow(new DiagnosisWindow("case-42", "prod-incident", 1_000L, 31_000L, 32_000L));

		TuningAdviceReport report = service.generateAdviceFromEvidence(current, CodeContextSummary.empty(), "prod",
				"diagnose-memory");

		assertThat(report.formattedSummary()).contains("## Diagnosis Context");
		assertThat(report.formattedSummary()).contains("- case: case-42");
		assertThat(report.formattedSummary()).contains("- source: prod-incident");
		assertThat(report.formattedSummary()).contains("- window: 1000 -> 31000 (30s)");
	}

	@Test
	void shouldNotUseBaselineWindowAsCurrentDiagnosisContext() {
		JavaTuningWorkflowService service = service();
		MemoryGcEvidencePack baseline = evidence(1L, 256, 10L, 200L, 1L, 120L, 700)
			.withDiagnosisWindow(new DiagnosisWindow("baseline-case", "baseline", 1_000L, 2_000L, 2_000L));
		MemoryGcEvidencePack current = evidence(2L, 384, 25L, 460L, 3L, 240L, 920).withBaselineEvidence(baseline);

		TuningAdviceReport report = service.generateAdviceFromEvidence(current, CodeContextSummary.empty(), "prod",
				"diagnose-memory");

		assertThat(report.formattedSummary()).doesNotContain("## Diagnosis Context");
		assertThat(report.formattedSummary()).contains("## Key Deltas");
	}

	@Test
	void shouldPreferExplicitBaselineCategoriesOverCurrentNmtDiffGrowth() {
		JavaTuningWorkflowService service = service();
		MemoryGcEvidencePack baseline = new MemoryGcEvidencePack(snapshot(1L, 256, 10L, 200L, 1L, 120L, 35), null,
				null, List.of(), List.of(), null, null)
			.withNativeMemorySummary(new NativeMemorySummary(0L, 700L * 1024L * 1024L, null, null, null, null,
					java.util.Map.of("class", new NativeMemorySummary.CategoryUsage(300L * 1024L * 1024L,
							260L * 1024L * 1024L)),
					java.util.Map.of(), List.of()));
		NativeMemorySummary currentNative = new NativeMemorySummary(0L, 920L * 1024L * 1024L, null, null, null, null,
				java.util.Map.of("class",
						new NativeMemorySummary.CategoryUsage(460L * 1024L * 1024L, 380L * 1024L * 1024L)),
				java.util.Map.of("thread",
						new NativeMemorySummary.CategoryGrowth(900L * 1024L * 1024L, 900L * 1024L * 1024L)),
				List.of());
		MemoryGcEvidencePack current = new MemoryGcEvidencePack(snapshot(2L, 384, 25L, 460L, 3L, 240L, 35), null,
				null, List.of(), List.of(), null, null)
			.withNativeMemorySummary(currentNative)
			.withBaselineEvidence(baseline);

		TuningAdviceReport report = service.generateAdviceFromEvidence(current, CodeContextSummary.empty(), "prod",
				"diagnose-memory");

		assertThat(report.formattedSummary())
			.contains("native category growth: Class +120 MiB committed (+160 MiB reserved)")
			.doesNotContain("Thread +900 MiB");
	}

	private static JavaTuningWorkflowService service() {
		return new JavaTuningWorkflowService((pid, request) -> snapshot(pid, 1, 0L, 0L, 0L, 0L, 0),
				MemoryGcDiagnosisEngine.firstVersion(), new LocalSourceHotspotFinder());
	}

	private static MemoryGcEvidencePack evidence(long pid, int heapUsedMiB, long youngCount, long youngTimeMs,
			long fullCount, long fullTimeMs, long nativeCommittedMiB) {
		return new MemoryGcEvidencePack(snapshot(pid, heapUsedMiB, youngCount, youngTimeMs, fullCount, fullTimeMs, 35),
				null, null, List.of(), List.of(), null, null)
			.withNativeMemorySummary(new NativeMemorySummary(0L, nativeCommittedMiB * 1024L * 1024L, null, null, null,
					null,
					java.util.Map.of("class", new NativeMemorySummary.CategoryUsage(pid == 1L ? 300L * 1024L * 1024L
							: 460L * 1024L * 1024L, pid == 1L ? 260L * 1024L * 1024L : 380L * 1024L * 1024L), "nio",
							new NativeMemorySummary.CategoryUsage(pid == 1L ? 120L * 1024L * 1024L : 210L * 1024L * 1024L,
									pid == 1L ? 90L * 1024L * 1024L : 170L * 1024L * 1024L)),
					java.util.Map.of(), List.of()));
	}

	private static JvmRuntimeSnapshot snapshot(long pid, int heapUsedMiB, long youngCount, long youngTimeMs,
			long fullCount, long fullTimeMs, double oldPercent) {
		return new JvmRuntimeSnapshot(pid,
				new JvmMemorySnapshot(heapUsedMiB * 1024L * 1024L, 512L * 1024L * 1024L, 1024L * 1024L * 1024L, null,
						null, null, null, null),
				new JvmGcSnapshot("G1", youngCount, youngTimeMs, fullCount, fullTimeMs, oldPercent), List.of(), "", null,
				null, new JvmCollectionMetadata(List.of(), 0L, 0L, false), List.of());
	}

}
