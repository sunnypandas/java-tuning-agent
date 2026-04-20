package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
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
				null, null, Map.of());

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
				false, false, null, null, null, Map.of());

		MemoryGcEvidencePack pack = assembler.build(draft);

		assertThat(pack.classHistogram().totalInstances()).isEqualTo(600L);
	}

	@Test
	void offlineTextLoader_blankSourceReturnsEmpty() throws Exception {
		assertThat(OfflineTextLoader.load(null)).isEmpty();
		assertThat(OfflineTextLoader.load(new OfflineArtifactSource(null, null))).isEmpty();
	}

}
