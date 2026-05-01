package com.alibaba.cloud.ai.examples.javatuning.source;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrAllocationSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrCountAndBytes;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrExecutionSampleSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrGcSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrStackAggregate;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrThreadSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RetentionChainSegment;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RetentionChainSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SuspectedHolderSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ThreadDumpSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceHotspotCorrelationServiceTest {

	private final SourceHotspotCorrelationService service = new SourceHotspotCorrelationService(
			new LocalSourceHotspotFinder());

	private final List<Path> sourceRoots = List.of(Path.of("").toAbsolutePath().resolve("compat/memory-leak-demo"));

	@Test
	void correlatesRetentionPathHolderToSourceFile() {
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(null, null, null, List.of(), List.of(), null, null,
				retentionResult("""
						unknown -> Object[].* -> AllocationRecord.payload -> byte[]
						"""));

		var hotspots = service.correlate(sourceRoots, evidence,
				List.of("com.alibaba.cloud.ai.compat.memoryleakdemo"));

		assertThat(hotspots).isNotEmpty();
		assertThat(hotspots.get(0).className())
			.isEqualTo("com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord");
		assertThat(hotspots.get(0).fileHint()).contains("compat/memory-leak-demo")
			.contains("AllocationRecord.java");
		assertThat(hotspots.get(0).evidenceLink()).contains("heap-retention");
		assertThat(hotspots.get(0).confidence()).isIn("high", "medium-high");
		assertThat(hotspots).extracting(hotspot -> hotspot.className())
			.contains("com.alibaba.cloud.ai.compat.memoryleakdemo.leak.InMemoryLeakStore");
	}

	@Test
	void ranksThreadDumpDeadlockFrameAboveOtherEvidence() {
		ThreadDumpSummary threadDump = new ThreadDumpSummary(2, Map.of("BLOCKED", 2L), List.of(
				"Found one Java-level deadlock: com.alibaba.cloud.ai.compat.memoryleakdemo.deadlock.DeadlockDemoTrigger.holdThenWait(DeadlockDemoTrigger.java:46)"));
		var histogram = new ClassHistogramParser().parse("""
				 1: 10 1024 com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord
				""");
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(null, histogram, threadDump, List.of(), List.of(),
				null, null);

		var hotspots = service.correlate(sourceRoots, evidence,
				List.of("com.alibaba.cloud.ai.compat.memoryleakdemo"));

		assertThat(hotspots).isNotEmpty();
		assertThat(hotspots.get(0).className())
			.isEqualTo("com.alibaba.cloud.ai.compat.memoryleakdemo.deadlock.DeadlockDemoTrigger");
		assertThat(hotspots.get(0).fileHint()).contains("DeadlockDemoTrigger.java");
		assertThat(hotspots.get(0).evidenceLink()).contains("Thread.print");
		assertThat(hotspots.get(0).confidence()).isEqualTo("high");
	}

	@Test
	void correlatesJfrStackFrameToSourceFile() {
		JfrSummary jfr = new JfrSummary(1L, 2L, 1L, new JfrGcSummary(0, 0, 0, List.of(), List.of()),
				new JfrAllocationSummary(0, List.of(new JfrCountAndBytes("[B", 12, 4096)),
						List.of(new JfrStackAggregate(
								"com.alibaba.cloud.ai.compat.memoryleakdemo.churn.JfrWorkloadService.burnCpu(byte[])",
								3, 4096, List.of(
										"com.alibaba.cloud.ai.compat.memoryleakdemo.churn.JfrWorkloadService.burnCpu(byte[])"))),
						1),
				new JfrThreadSummary(0, 0, 0, List.of()),
				new JfrExecutionSampleSummary(3, List.of(new JfrStackAggregate(
						"com.alibaba.cloud.ai.compat.memoryleakdemo.churn.JfrWorkloadService.burnCpu(byte[])", 3,
						0, List.of(
								"com.alibaba.cloud.ai.compat.memoryleakdemo.churn.JfrWorkloadService.burnCpu(byte[])")))),
				Map.of(), List.of());
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(null, null, null, List.of(), List.of(), null, null)
			.withJfrSummary(jfr);

		var hotspots = service.correlate(sourceRoots, evidence,
				List.of("com.alibaba.cloud.ai.compat.memoryleakdemo"));

		assertThat(hotspots).isNotEmpty();
		assertThat(hotspots.get(0).className())
			.isEqualTo("com.alibaba.cloud.ai.compat.memoryleakdemo.churn.JfrWorkloadService");
		assertThat(hotspots.get(0).fileHint()).contains("JfrWorkloadService.java");
		assertThat(hotspots.get(0).evidenceLink()).contains("JFR");
	}

	private static HeapRetentionAnalysisResult retentionResult(String pathText) {
		var holder = new SuspectedHolderSummary("com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord",
				"field-owner", 8_388_608L, 8_388_608L, 128L, "AllocationRecord.payload", "byte[]",
				"payload retains byte arrays");
		var storeHolder = new SuspectedHolderSummary(
				"com.alibaba.cloud.ai.compat.memoryleakdemo.leak.InMemoryLeakStore", "collection-owner",
				8_388_608L, 8_388_608L, 128L, "InMemoryLeakStore.retainedRecords", "AllocationRecord",
				"retainedRecords keeps allocation records reachable");
		var chain = new RetentionChainSummary("unknown",
				List.of(new RetentionChainSegment("com.alibaba.cloud.ai.compat.memoryleakdemo.leak.InMemoryLeakStore",
						"field", "retainedRecords", "java.lang.Object[]"),
						new RetentionChainSegment("java.lang.Object[]", "array-slot", "*",
						"com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord"),
						new RetentionChainSegment("com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord",
								"field", "payload", "byte[]")),
				"byte[]", 65536L, 128L, 8_388_608L, 8_388_608L);
		var summary = new HeapRetentionSummary(List.of(), List.of(holder, storeHolder), List.of(chain), List.of(),
				new HeapRetentionConfidence("medium", List.of(), List.of("retained bytes are approximate")),
				"### Heap retention analysis\n\n```text\n" + pathText + "\n```", true, List.of(), "");
		return new HeapRetentionAnalysisResult(true, "dominator-style", List.of(), "", summary,
				summary.summaryMarkdown());
	}

}
