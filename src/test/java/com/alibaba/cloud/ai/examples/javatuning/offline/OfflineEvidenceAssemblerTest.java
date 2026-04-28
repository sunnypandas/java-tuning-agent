package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ResourceBudgetEvidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ThreadDumpParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineEvidenceAssemblerTest {

	private final OfflineJvmSnapshotAssembler snapshotAssembler = new OfflineJvmSnapshotAssembler();

	private final OfflineEvidenceAssembler assembler = new OfflineEvidenceAssembler(snapshotAssembler,
			new ClassHistogramParser(), new ThreadDumpParser(), new SharkHeapDumpSummarizer(40, 32000), false);

	@Test
	void buildsPackWithHistogramThreadDumpAndPaths() {
		String histogramFromEngineTest = """
				 1: 600 157286400 com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord
				""";
		String runtime = """
				garbage-first heap total 262144K, used 218758K
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""";
		String threadSnippet = """
				"main" #1
				   java.lang.Thread.State: RUNNABLE
				""";

		OfflineBundleDraft draft = new OfflineBundleDraft("pid=99999\n-XX:+UseG1GC", "VM.version:\n21.0.2", runtime,
				new OfflineArtifactSource(null, histogramFromEngineTest),
				new OfflineArtifactSource(null, threadSnippet), "C:\\\\heap\\\\dump.hprof", false, false, false, null,
				null, null, null, null, null, Map.of());

		MemoryGcEvidencePack pack = assembler.build(draft);

		assertThat(pack.snapshot().pid()).isEqualTo(99999L);
		assertThat(pack.classHistogram()).isNotNull();
		assertThat(pack.classHistogram().entries()).isNotEmpty();
		assertThat(pack.threadDump()).isNotNull();
		assertThat(pack.threadDump().threadCount()).isGreaterThan(0);
		assertThat(pack.heapDumpPath()).isEqualTo("C:\\\\heap\\\\dump.hprof");
		assertThat(pack.snapshot().gc()).isNotNull();
	}

	@Test
	void loadsHistogramFromFile(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("hist.txt");
		String body = """
				 1: 600 157286400 com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord
				""";
		Files.writeString(file, body, StandardCharsets.UTF_8);

		OfflineBundleDraft draft = new OfflineBundleDraft("", "", "",
				new OfflineArtifactSource(file.toString(), null), new OfflineArtifactSource(null, null), null, false,
				false, false, null, null, null, null, null, null, Map.of());

		MemoryGcEvidencePack pack = assembler.build(draft);

		assertThat(pack.classHistogram().totalInstances()).isEqualTo(600L);
	}

	@Test
	void offlineTextLoader_blankSourceReturnsEmpty() throws Exception {
		assertThat(OfflineTextLoader.load(null)).isEmpty();
		assertThat(OfflineTextLoader.load(new OfflineArtifactSource(null, null))).isEmpty();
	}

	@Test
	void parsesGcLogPathOrTextIntoEvidencePack() {
		String gcLog = """
				[1.234s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 128M->96M(512M) 12.345ms
				[2.345s][info][gc] GC(2) Pause Full (G1 Compaction Pause) 450M->280M(512M) 210.000ms
				""";
		OfflineBundleDraft draft = new OfflineBundleDraft("pid=99999\n-XX:+UseG1GC", "VM.version:\n21.0.2",
				"garbage-first heap total 262144K, used 218758K", new OfflineArtifactSource(null, null),
				new OfflineArtifactSource(null, null), null, false, false, false, null, null, null, gcLog, null, null,
				Map.of());

		MemoryGcEvidencePack pack = assembler.build(draft);

		assertThat(pack.gcLogSummary()).isNotNull();
		assertThat(pack.gcLogSummary().pauseEventCount()).isEqualTo(2);
		assertThat(pack.gcLogSummary().fullPauseCount()).isEqualTo(1);
	}

	@Test
	void parsesNativeMemorySummaryFromRuntimeSnapshotText() {
		String runtime = """
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690

				VM.native_memory summary
				Total: reserved=2048M, committed=1536M
				-                            NIO (reserved=256M, committed=220M)
				-                          Class (reserved=640M, committed=540M)

				VM.native_memory summary.diff
				Total: reserved=+200M, committed=+160M
				-                            NIO (reserved=+60M, committed=+50M)
				-                          Class (reserved=+90M, committed=+70M)
				""";
		OfflineBundleDraft draft = new OfflineBundleDraft("pid=99999\n-XX:+UseG1GC", "VM.version:\n21.0.2", runtime,
				new OfflineArtifactSource(null, null), new OfflineArtifactSource(null, null), null, false, false, false,
				null, null, null, null, null, null, Map.of());

		MemoryGcEvidencePack pack = assembler.build(draft);

		assertThat(pack.nativeMemorySummary()).isNotNull();
		assertThat(pack.nativeMemorySummary().hasTotals()).isTrue();
		assertThat(pack.nativeMemorySummary().directCommittedBytes()).isEqualTo(220L * 1024L * 1024L);
		assertThat(pack.nativeMemorySummary().categoryGrowth().get("class").committedDeltaBytes())
			.isEqualTo(70L * 1024L * 1024L);
	}

	@Test
	void prefersExplicitNativeMemorySourceWhenProvided() {
		OfflineBundleDraft draft = new OfflineBundleDraft("pid=99999\n-XX:+UseG1GC", "VM.version:\n21.0.2",
				"garbage-first heap total 262144K, used 218758K", new OfflineArtifactSource(null, null),
				new OfflineArtifactSource(null, null), null, false, false, false,
				new OfflineArtifactSource(null, """
						Total: reserved=1024M, committed=900M
						-                            NIO (reserved=300M, committed=260M)
						-                          Class (reserved=200M, committed=180M)
						"""), null, null, null, null, null, Map.of());

		MemoryGcEvidencePack pack = assembler.build(draft);

		assertThat(pack.nativeMemorySummary()).isNotNull();
		assertThat(pack.nativeMemorySummary().totalCommittedBytes()).isEqualTo(900L * 1024L * 1024L);
	}

	@Test
	void parsesRepeatedSamplesPathOrTextIntoEvidencePack() {
		String repeatedSamples = """
				{
				  "pid": 99999,
				  "startedAtEpochMs": 1000,
				  "elapsedMs": 2000,
				  "samples": [
				    {"sampledAtEpochMs":1000,
				     "memory":{"heapUsedBytes":104857600,"heapCommittedBytes":536870912,"heapMaxBytes":536870912},
				     "gc":{"collector":"G1","youngGcCount":1,"youngGcTimeMs":10,"fullGcCount":0,"fullGcTimeMs":0,"oldUsagePercent":40.0},
				     "threadCount":20,"loadedClassCount":1000,"warnings":[]},
				    {"sampledAtEpochMs":2000,
				     "memory":{"heapUsedBytes":188743680,"heapCommittedBytes":536870912,"heapMaxBytes":536870912},
				     "gc":{"collector":"G1","youngGcCount":2,"youngGcTimeMs":20,"fullGcCount":0,"fullGcTimeMs":0,"oldUsagePercent":55.0},
				     "threadCount":22,"loadedClassCount":1005,"warnings":[]},
				    {"sampledAtEpochMs":3000,
				     "memory":{"heapUsedBytes":272629760,"heapCommittedBytes":536870912,"heapMaxBytes":536870912},
				     "gc":{"collector":"G1","youngGcCount":3,"youngGcTimeMs":30,"fullGcCount":0,"fullGcTimeMs":0,"oldUsagePercent":72.0},
				     "threadCount":24,"loadedClassCount":1010,"warnings":[]}
				  ],
				  "warnings": [],
				  "missingData": []
				}
				""";
		OfflineBundleDraft draft = new OfflineBundleDraft("pid=99999\n-XX:+UseG1GC", "VM.version:\n21.0.2",
				"garbage-first heap total 262144K, used 218758K", new OfflineArtifactSource(null, null),
				new OfflineArtifactSource(null, null), null, false, false, false, null, null, null, null, null,
				repeatedSamples, Map.of());

		MemoryGcEvidencePack pack = assembler.build(draft);

		assertThat(pack.repeatedSamplingResult()).isNotNull();
		assertThat(pack.repeatedSamplingResult().samples()).hasSize(3);
	}

	@Test
	void parsesResourceBudgetFromBackgroundNotes() {
		OfflineBundleDraft draft = new OfflineBundleDraft("pid=99999\n-XX:+UseG1GC", "VM.version:\n21.0.2",
				"garbage-first heap total 262144K, used 218758K", new OfflineArtifactSource(null, null),
				new OfflineArtifactSource(null, null), null, false, false, false, null, null, null, null, null, null,
				Map.of("resourceBudget", """
						containerMemoryLimitBytes=1073741824
						processRssBytes=1006632960
						cpuQuotaCores=2.0
						estimatedThreadStackBytes=134217728
						"""));

		MemoryGcEvidencePack pack = assembler.build(draft);

		ResourceBudgetEvidence resourceBudget = pack.resourceBudgetEvidence();
		assertThat(resourceBudget).isNotNull();
		assertThat(resourceBudget.containerMemoryLimitBytes()).isEqualTo(1_024L * 1024L * 1024L);
		assertThat(resourceBudget.processRssBytes()).isEqualTo(960L * 1024L * 1024L);
		assertThat(resourceBudget.cpuQuotaCores()).isEqualTo(2.0d);
		assertThat(resourceBudget.estimatedThreadStackBytes()).isEqualTo(128L * 1024L * 1024L);
	}

	@Test
	void degradesMalformedResourceBudgetNotesWithoutFailingAssembly() {
		OfflineBundleDraft draft = new OfflineBundleDraft("pid=99999\n-XX:+UseG1GC", "VM.version:\n21.0.2",
				"garbage-first heap total 262144K, used 218758K", new OfflineArtifactSource(null, null),
				new OfflineArtifactSource(null, null), null, false, false, false, null, null, null, null, null, null,
				Map.of("resourceBudget", """
						containerMemoryLimitBytes=999999999999999999999999999999999999
						processRssBytes=not-a-number
						cpuQuotaCores=not-a-number
						"""));

		MemoryGcEvidencePack pack = assembler.build(draft);

		ResourceBudgetEvidence resourceBudget = pack.resourceBudgetEvidence();
		assertThat(resourceBudget).isNotNull();
		assertThat(resourceBudget.containerMemoryLimitBytes()).isNull();
		assertThat(resourceBudget.processRssBytes()).isNull();
		assertThat(resourceBudget.cpuQuotaCores()).isNull();
		assertThat(resourceBudget.missingData()).contains("containerMemoryLimit", "processRss");
		assertThat(resourceBudget.warnings())
			.contains("Resource budget evidence did not include container limit, RSS, or CPU quota");
	}

}
