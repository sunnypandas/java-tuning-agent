package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineJvmSnapshotAssemblerTest {

	private final OfflineJvmSnapshotAssembler assembler = new OfflineJvmSnapshotAssembler();

	@Test
	void assemblesPidHeapAndGcFromOfflineTexts() {
		String identity = """
				20380:
				-XX:+UseG1GC -Xmx512m
				""";
		String runtime = """
				garbage-first heap   total reserved 262144K, committed 262144K, used 218758K
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""";
		OfflineBundleDraft draft = new OfflineBundleDraft(identity, """
				VM.version:
				21.0.1
				""", runtime, new OfflineArtifactSource(null, null), new OfflineArtifactSource(null, null), null, false,
				false, false, null, null, null, null, null, null, Map.of());

		JvmRuntimeSnapshot snap = assembler.assemble(draft);

		assertThat(snap.pid()).isEqualTo(20380L);
		assertThat(snap.gc()).isNotNull();
		assertThat(snap.gc().youngGcCount()).isEqualTo(145L);
		assertThat(snap.gc().collector()).isEqualTo("G1");
		assertThat(snap.memory().heapUsedBytes()).isEqualTo(218758L * 1024L);
		assertThat(snap.collectionMetadata().commandsRun()).containsExactly("offline-import");
	}

	@Test
	void parsesPidEqualsForm() {
		String runtime = """
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00   0.00   0.00   0.00   0.00       0    0.000       0    0.000       -       -     0.000
				""";
		OfflineBundleDraft draft = new OfflineBundleDraft("pid=424242", "", runtime,
				new OfflineArtifactSource(null, null), new OfflineArtifactSource(null, null), null, false, false, false,
				null, null, null, null, null, null, Map.of());

		JvmRuntimeSnapshot snap = assembler.assemble(draft);

		assertThat(snap.pid()).isEqualTo(424242L);
		assertThat(snap.gc()).isNotNull();
	}

	@Test
	void infersSupportedCollectorsFromOfflineText() {
		OfflineBundleDraft zgcDraft = new OfflineBundleDraft("pid=1\n-XX:+UseZGC", "", "",
				new OfflineArtifactSource(null, null), new OfflineArtifactSource(null, null), null, false, false, false,
				null, null, null, null, null, null, Map.of());
		OfflineBundleDraft serialDraft = new OfflineBundleDraft("pid=2\n-XX:+UseSerialGC", "", "",
				new OfflineArtifactSource(null, null), new OfflineArtifactSource(null, null), null, false, false, false,
				null, null, null, null, null, null, Map.of());
		OfflineBundleDraft parallelDraft = new OfflineBundleDraft("pid=3\n-XX:+UseParallelGC", "", "",
				new OfflineArtifactSource(null, null), new OfflineArtifactSource(null, null), null, false, false, false,
				null, null, null, null, null, null, Map.of());
		OfflineBundleDraft cmsDraft = new OfflineBundleDraft("pid=4\n-XX:+UseConcMarkSweepGC", "", "",
				new OfflineArtifactSource(null, null), new OfflineArtifactSource(null, null), null, false, false, false,
				null, null, null, null, null, null, Map.of());

		assertThat(assembler.assemble(zgcDraft).gc().collector()).isEqualTo("ZGC");
		assertThat(assembler.assemble(serialDraft).gc().collector()).isEqualTo("Serial");
		assertThat(assembler.assemble(parallelDraft).gc().collector()).isEqualTo("Parallel");
		assertThat(assembler.assemble(cmsDraft).gc().collector()).isEqualTo("CMS");
	}

}
