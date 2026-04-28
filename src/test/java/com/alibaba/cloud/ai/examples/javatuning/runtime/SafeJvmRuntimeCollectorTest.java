package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapDumpSummarizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SafeJvmRuntimeCollectorTest {

	private static final SharkHeapDumpSummarizer TEST_HEAP_SUMMARIZER = new SharkHeapDumpSummarizer(40, 32000);

	private static SafeJvmRuntimeCollector testCollector(CommandExecutor executor) {
		return new SafeJvmRuntimeCollector(executor, RuntimeCollectionPolicy.safeReadonly(), TEST_HEAP_SUMMARIZER,
				false);
	}

	private static SafeJvmRuntimeCollector testCollector(CommandExecutor executor, RuntimeCollectionPolicy policy) {
		return new SafeJvmRuntimeCollector(executor, policy, TEST_HEAP_SUMMARIZER, false);
	}

	private static void stubExtendedCollectors(CommandExecutor executor, String pid) {
		given(executor.run(List.of("jcmd", pid, "VM.version"))).willReturn(pid + ":\nOpenJDK 64-Bit Server VM (test)");
		given(executor.run(List.of("jstat", "-class", pid))).willReturn("""
				Loaded  Bytes  Unloaded  Bytes     Time
				   12  24.00        0     0.00       0.01
				""");
		given(executor.run(List.of("jcmd", pid, "PerfCounter.print"))).willReturn("java.threads.live            4\n");
		given(executor.run(List.of("jcmd", pid, "VM.native_memory", "summary"))).willReturn("""
				Total: reserved=1024M, committed=768M
				-                            NIO (reserved=128M, committed=96M)
				-                          Class (reserved=256M, committed=192M)
				""");
		given(executor.run(List.of("jcmd", pid, "help", "VM.native_memory"))).willReturn("""
				VM.native_memory
				Print native memory usage
				""");
	}

	@Test
	void shouldCollectSafeReadonlySnapshot() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-XX:+UseG1GC -Xms512m -Xmx1024m");
		stubExtendedCollectors(executor, "123");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				garbage-first heap total 1048576K, used 524288K
				G1 Old Generation
				  used 256000K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
		""");

		SafeJvmRuntimeCollector collector = testCollector(executor);

		JvmRuntimeSnapshot snapshot = collector.collect(123L, RuntimeCollectionPolicy.CollectionRequest.safeReadonly());

		assertThat(snapshot.memory().heapUsedBytes()).isEqualTo(524288L * 1024L);
		assertThat(snapshot.memory().heapMaxBytes()).isEqualTo(1024L * 1024L * 1024L);
		assertThat(snapshot.memory().xmsBytes()).isEqualTo(512L * 1024L * 1024L);
		assertThat(snapshot.memory().xmxBytes()).isEqualTo(1024L * 1024L * 1024L);
		assertThat(snapshot.memory().oldGenUsedBytes()).isEqualTo(256000L * 1024L);
		assertThat(snapshot.gc().youngGcCount()).isEqualTo(145L);
		assertThat(snapshot.gc().youngGcTimeMs()).isEqualTo(1234L);
		assertThat(snapshot.gc().fullGcCount()).isEqualTo(2L);
		assertThat(snapshot.gc().fullGcTimeMs()).isEqualTo(456L);
		assertThat(snapshot.gc().oldUsagePercent()).isEqualTo(78.90d);
		assertThat(snapshot.gc().collector()).isEqualTo("G1");
		assertThat(snapshot.jvmVersion()).contains("OpenJDK");
		assertThat(snapshot.threadCount()).isEqualTo(4L);
		assertThat(snapshot.loadedClassCount()).isEqualTo(12L);
		assertThat(snapshot.collectionMetadata().commandsRun()).containsExactly("jcmd 123 VM.flags", "jcmd 123 VM.version",
				"jcmd 123 GC.heap_info", "jstat -gcutil 123", "jstat -class 123", "jcmd 123 PerfCounter.print");
		assertThat(snapshot.vmFlags()).contains("-XX:+UseG1GC");
		assertThat(snapshot.warnings()).isEmpty();
	}

	@Test
	void shouldParseThreadsLiveFromPerfCounterKeyValueFormat() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-XX:+UseG1GC -Xms512m -Xmx1024m");
		given(executor.run(List.of("jcmd", "123", "VM.version"))).willReturn("123:\nOpenJDK 64-Bit Server VM (test)");
		given(executor.run(List.of("jstat", "-class", "123"))).willReturn("""
				Loaded  Bytes  Unloaded  Bytes     Time
				   12  24.00        0     0.00       0.01
				""");
		given(executor.run(List.of("jcmd", "123", "PerfCounter.print")))
				.willReturn("java.threads.daemon=20\njava.threads.live=24\njava.threads.livePeak=24\n");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				garbage-first heap total 1048576K, used 524288K
				G1 Old Generation
				  used 256000K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
		""");

		SafeJvmRuntimeCollector collector = testCollector(executor);
		JvmRuntimeSnapshot snapshot = collector.collect(123L, RuntimeCollectionPolicy.CollectionRequest.safeReadonly());

		assertThat(snapshot.threadCount()).isEqualTo(24L);
		assertThat(snapshot.warnings())
				.noneMatch(w -> w.contains("Could not parse java.threads.live from jcmd PerfCounter.print"));
	}

	@Test
	void shouldCollectNormalizedHeapFlagsSnapshot() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("""
				-XX:+UseG1GC -XX:InitialHeapSize=536870912 -XX:MaxHeapSize=1073741824
				""");
		stubExtendedCollectors(executor, "123");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				garbage-first heap total 1048576K, used 524288K
				G1 Old Generation
				  used 256000K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""");

		SafeJvmRuntimeCollector collector = testCollector(executor);

		JvmRuntimeSnapshot snapshot = collector.collect(123L, RuntimeCollectionPolicy.CollectionRequest.safeReadonly());

		assertThat(snapshot.memory().xmsBytes()).isEqualTo(512L * 1024L * 1024L);
		assertThat(snapshot.memory().xmxBytes()).isEqualTo(1024L * 1024L * 1024L);
		assertThat(snapshot.memory().heapMaxBytes()).isEqualTo(1024L * 1024L * 1024L);
		assertThat(snapshot.gc().collector()).isEqualTo("G1");
		assertThat(snapshot.warnings()).isEmpty();
	}

	@Test
	void shouldIgnoreSoftMaxHeapSizeWhenParsingHeapFlags() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("""
				-XX:+UseG1GC -XX:SoftMaxHeapSize=268435456 -XX:MaxHeapSize=1073741824
				""");
		stubExtendedCollectors(executor, "123");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				garbage-first heap total 1048576K, used 524288K
				G1 Old Generation
				  used 256000K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""");

		SafeJvmRuntimeCollector collector = testCollector(executor);

		JvmRuntimeSnapshot snapshot = collector.collect(123L, RuntimeCollectionPolicy.CollectionRequest.safeReadonly());

		assertThat(snapshot.memory().xmxBytes()).isEqualTo(1024L * 1024L * 1024L);
		assertThat(snapshot.memory().heapMaxBytes()).isEqualTo(1024L * 1024L * 1024L);
		assertThat(snapshot.warnings()).isEmpty();
	}

	@Test
	void shouldWarnWhenCollectorCannotBeInferred() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-XX:MaxHeapSize=1073741824");
		stubExtendedCollectors(executor, "123");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				parallel heap total 1048576K, used 524288K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""");

		SafeJvmRuntimeCollector collector = testCollector(executor);

		JvmRuntimeSnapshot snapshot = collector.collect(123L, RuntimeCollectionPolicy.CollectionRequest.safeReadonly());

		assertThat(snapshot.gc().collector()).isEqualTo("Parallel");
		assertThat(snapshot.warnings()).isEmpty();
	}

	@Test
	void shouldRejectPrivilegedCollectionWithoutConfirmation() {
		SafeJvmRuntimeCollector collector = testCollector(mock(CommandExecutor.class));

		assertThatThrownBy(() -> collector.collect(123L,
				new RuntimeCollectionPolicy.CollectionRequest(true, true, false, false, null)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confirmationToken");
	}

	@Test
	void shouldRejectMemoryGcEvidenceWithoutConfirmationForHistogramCollection() {
		SafeJvmRuntimeCollector collector = testCollector(mock(CommandExecutor.class));

		assertThatThrownBy(() -> collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, true, false, false, "", null)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confirmationToken");
	}

	@Test
	void shouldRejectHeapDumpWithoutOutputPath() {
		SafeJvmRuntimeCollector collector = testCollector(mock(CommandExecutor.class));

		assertThatThrownBy(() -> collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, false, false, true, "", "confirmed")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("heapDumpOutputPath");
	}

	@Test
	void shouldRejectHeapDumpRelativeOutputPath() {
		SafeJvmRuntimeCollector collector = testCollector(mock(CommandExecutor.class));

		assertThatThrownBy(() -> collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, false, false, true, "relative.hprof", "confirmed")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("absolute");
	}

	@Test
	void shouldRejectHeapDumpOutputPathWithoutHprofExtension(@TempDir Path tempDir) {
		SafeJvmRuntimeCollector collector = testCollector(mock(CommandExecutor.class));
		String output = tempDir.resolve("heap.bin").toAbsolutePath().normalize().toString();

		assertThatThrownBy(() -> collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, false, false, true, output, "confirmed")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(".hprof");
	}

	@Test
	void shouldRejectHeapDumpOutputPathWithMissingParent(@TempDir Path tempDir) {
		SafeJvmRuntimeCollector collector = testCollector(mock(CommandExecutor.class));
		String output = tempDir.resolve("missing").resolve("heap.hprof").toAbsolutePath().normalize().toString();

		assertThatThrownBy(() -> collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, false, false, true, output, "confirmed")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("parent");
	}

	@Test
	void shouldRejectExistingHeapDumpOutputFile(@TempDir Path tempDir) throws Exception {
		SafeJvmRuntimeCollector collector = testCollector(mock(CommandExecutor.class));
		Path output = tempDir.resolve("heap.hprof").toAbsolutePath().normalize();
		Files.writeString(output, "existing");

		assertThatThrownBy(() -> collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, false, false, true, output.toString(), "confirmed")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("already exists");
	}

	@Test
	void shouldRejectHeapDumpWithoutConfirmationToken() {
		SafeJvmRuntimeCollector collector = testCollector(mock(CommandExecutor.class));

		assertThatThrownBy(() -> collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, false, false, true, "C:/tmp/dump.hprof", "  ")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confirmationToken");
	}

	@Test
	void shouldCollectMemoryGcEvidenceWithHistogramWhenConfirmed() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-XX:+UseG1GC -Xms512m -Xmx1024m");
		stubExtendedCollectors(executor, "123");
		given(executor.run(List.of("jcmd", "123", "VM.version"))).willReturn("123:\nOpenJDK 64-Bit Server VM 21.0.2");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				garbage-first heap total 1048576K, used 524288K
				G1 Old Generation
				  used 256000K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""");
		given(executor.run(List.of("jcmd", "123", "GC.class_histogram"))).willReturn("""
				 num     #instances         #bytes  class name
				----------------------------------------------
				   1:            10             400  java.lang.Object
				   2:             2              80  [B
				""");
		given(executor.run(List.of("jcmd", "123", "help", "VM.native_memory"))).willReturn("VM.native_memory");
		given(executor.run(List.of("jcmd", "123", "VM.native_memory", "summary"))).willReturn("""
				Total: reserved=1024M, committed=640M
				-                            NIO (reserved=80M, committed=40M)
				-                          Class (reserved=120M, committed=60M)
				""");

		SafeJvmRuntimeCollector collector = testCollector(executor);

		MemoryGcEvidencePack pack = collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, true, false, false, "", "confirmed"));

		assertThat(pack.snapshot().pid()).isEqualTo(123L);
		assertThat(pack.snapshot().collectionMetadata().commandsRun()).contains("jcmd 123 GC.class_histogram");
		assertThat(pack.classHistogram()).isNotNull();
		assertThat(pack.classHistogram().entries()).hasSize(2);
		assertThat(pack.threadDump()).isNull();
		assertThat(pack.missingData()).isEmpty();
		assertThat(pack.warnings()).isEmpty();
		assertThat(pack.heapDumpPath()).isNull();
	}

	@Test
	void shouldCollectHeapDumpWhenConfirmed(@TempDir Path tempDir) throws Exception {
		Path dumpFile = tempDir.resolve("heap.hprof");
		String abs = dumpFile.toAbsolutePath().normalize().toString();
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-XX:+UseG1GC -Xms512m -Xmx1024m");
		stubExtendedCollectors(executor, "123");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				garbage-first heap total 1048576K, used 524288K
				G1 Old Generation
				  used 256000K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""");
		given(executor.run(List.of("jcmd", "123", "GC.heap_dump", abs))).willAnswer(inv -> {
			Files.createFile(dumpFile);
			return "Heap dump file created";
		});

		SafeJvmRuntimeCollector collector = testCollector(executor);

		MemoryGcEvidencePack pack = collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, false, false, true, abs, "confirmed"));

		assertThat(pack.heapDumpPath()).isEqualTo(abs);
		assertThat(pack.missingData()).doesNotContain("heapDump");
		assertThat(pack.snapshot().collectionMetadata().commandsRun()).contains("jcmd 123 GC.heap_dump " + abs);
		assertThat(pack.snapshot().collectionMetadata().privilegedCollection()).isTrue();
	}

	@Test
	void shouldMarkHeapDumpMissingWhenOutputFileNeverAppears(@TempDir Path tempDir) {
		Path dumpFile = tempDir.resolve("missing.hprof");
		String abs = dumpFile.toAbsolutePath().normalize().toString();
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-XX:+UseG1GC -Xms512m -Xmx1024m");
		stubExtendedCollectors(executor, "123");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				garbage-first heap total 1048576K, used 524288K
				G1 Old Generation
				  used 256000K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""");
		given(executor.run(List.of("jcmd", "123", "GC.heap_dump", abs))).willReturn("done\n");

		SafeJvmRuntimeCollector collector = testCollector(executor);
		MemoryGcEvidencePack pack = collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, false, false, true, abs, "confirmed"));

		assertThat(pack.heapDumpPath()).isNull();
		assertThat(pack.missingData()).contains("heapDump");
		assertThat(pack.warnings()).anyMatch(w -> w.contains("file was not found"));
	}

	@Test
	void shouldCollectThreadDumpWhenConfirmed() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-XX:+UseG1GC -Xms512m -Xmx1024m");
		stubExtendedCollectors(executor, "123");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				garbage-first heap total 1048576K, used 524288K
				G1 Old Generation
				  used 256000K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""");
		given(executor.run(List.of("jcmd", "123", "Thread.print"))).willReturn("""
				2026-01-01 00:00:00
				Full thread dump OpenJDK 64-Bit Server VM:

				"main" #1 prio=5 os_prio=0 cpu=10.00ms elapsed=1.00s tid=0x00007 nid=0x1 runnable [0x00000000]
				   java.lang.Thread.State: RUNNABLE
				       at java.lang.Thread.sleep(Native Method)
				""");

		SafeJvmRuntimeCollector collector = testCollector(executor);

		MemoryGcEvidencePack pack = collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, false, true, false, "", "confirmed"));

		assertThat(pack.threadDump()).isNotNull();
		assertThat(pack.threadDump().threadCount()).isEqualTo(1);
		assertThat(pack.threadDump().threadsByState()).containsKey("RUNNABLE");
		assertThat(pack.missingData()).isEmpty();
		assertThat(pack.snapshot().collectionMetadata().commandsRun()).contains("jcmd 123 Thread.print");
	}

	@Test
	void shouldMarkBlankHistogramOutputAsMissingData() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-XX:+UseG1GC -Xms512m -Xmx1024m");
		stubExtendedCollectors(executor, "123");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				garbage-first heap total 1048576K, used 524288K
				G1 Old Generation
				  used 256000K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""");
		given(executor.run(List.of("jcmd", "123", "GC.class_histogram"))).willReturn("   ");

		SafeJvmRuntimeCollector collector = testCollector(executor);

		MemoryGcEvidencePack pack = collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, true, false, false, "", "confirmed"));

		assertThat(pack.snapshot().collectionMetadata().commandsRun()).contains("jcmd 123 GC.class_histogram");
		assertThat(pack.classHistogram()).isNull();
		assertThat(pack.missingData()).containsExactly("classHistogram");
		assertThat(pack.warnings()).doesNotContain("GC.class_histogram output was non-blank but could not be parsed");
	}

	@Test
	void shouldWarnWhenHistogramOutputIsMalformed() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-XX:+UseG1GC -Xms512m -Xmx1024m");
		stubExtendedCollectors(executor, "123");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				garbage-first heap total 1048576K, used 524288K
				G1 Old Generation
				  used 256000K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""");
		given(executor.run(List.of("jcmd", "123", "GC.class_histogram"))).willReturn("not a histogram");

		SafeJvmRuntimeCollector collector = testCollector(executor);

		MemoryGcEvidencePack pack = collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(123L, true, false, false, "", "confirmed"));

		assertThat(pack.snapshot().collectionMetadata().commandsRun()).contains("jcmd 123 GC.class_histogram");
		assertThat(pack.classHistogram()).isNotNull();
		assertThat(pack.classHistogram().entries()).isEmpty();
		assertThat(pack.missingData()).isEmpty();
		assertThat(pack.warnings()).contains("GC.class_histogram output was non-blank but could not be parsed");
	}

	@Test
	void shouldWarnWhenPerfCounterDoesNotExposeLiveThreads() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-XX:+UseG1GC -Xms512m -Xmx1024m");
		given(executor.run(List.of("jcmd", "123", "VM.version"))).willReturn("123:\nOpenJDK");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				garbage-first heap total 1048576K, used 524288K
				G1 Old Generation
				  used 256000K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""");
		given(executor.run(List.of("jstat", "-class", "123"))).willReturn("""
				Loaded  Bytes  Unloaded  Bytes     Time
				   1  1.00        0     0.00       0.01
				""");
		given(executor.run(List.of("jcmd", "123", "PerfCounter.print"))).willReturn("some.other.counter 1\n");

		SafeJvmRuntimeCollector collector = testCollector(executor);
		JvmRuntimeSnapshot snapshot = collector.collect(123L, RuntimeCollectionPolicy.CollectionRequest.safeReadonly());

		assertThat(snapshot.threadCount()).isNull();
		assertThat(snapshot.warnings()).contains("Could not parse java.threads.live from jcmd PerfCounter.print");
	}

	@Test
	void shouldCollectNativeMemorySummaryWhenCommandSupported() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-XX:+UseG1GC -Xms512m -Xmx1024m");
		given(executor.run(List.of("jcmd", "123", "VM.version"))).willReturn("123:\nOpenJDK 64-Bit Server VM 17.0.10");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("""
				123:
				garbage-first heap total 1048576K, used 524288K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""");
		given(executor.run(List.of("jstat", "-class", "123"))).willReturn("Loaded  Bytes\n1 1.00\n");
		given(executor.run(List.of("jcmd", "123", "PerfCounter.print"))).willReturn("java.threads.live=2");
		given(executor.run(List.of("jcmd", "123", "help", "VM.native_memory"))).willReturn("VM.native_memory");
		given(executor.run(List.of("jcmd", "123", "VM.native_memory", "summary"))).willReturn("""
				Native Memory Tracking:
				Total: reserved=2048M, committed=1536M
				-                            NIO (reserved=256M, committed=220M)
				-                          Class (reserved=640M, committed=540M)
				""");
		given(executor.run(List.of("jcmd", "123", "VM.native_memory", "summary.diff"))).willReturn("""
				Total: reserved=+128M, committed=+96M
				-                            NIO (reserved=+32M, committed=+20M)
				-                          Class (reserved=+48M, committed=+40M)
				""");

		MemoryGcEvidencePack pack = testCollector(executor)
			.collectMemoryGcEvidence(new MemoryGcEvidenceRequest(123L, false, false, false, "", null));

		assertThat(pack.nativeMemorySummary()).isNotNull();
		assertThat(pack.missingData()).doesNotContain("nativeMemorySummary");
		assertThat(pack.nativeMemorySummary().categoryGrowth().get("nio").committedDeltaBytes())
			.isEqualTo(20L * 1024L * 1024L);
	}

	@Test
	void shouldDegradeNativeCollectionWhenProbeFails() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-XX:+UseG1GC -Xmx1024m");
		given(executor.run(List.of("jcmd", "123", "VM.version"))).willReturn("123:\nOpenJDK 64-Bit Server VM 21.0.3");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("garbage-first heap total 1024K, used 1K");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0 S1 E O M CCS YGC YGCT FGC FGCT CGC CGCT GCT
				  0.00 0.00 1.00 1.00 1.00 1.00 1 0.001 0 0.000 - - 0.001
				""");
		given(executor.run(List.of("jstat", "-class", "123"))).willReturn("Loaded  Bytes\n1 1.00\n");
		given(executor.run(List.of("jcmd", "123", "PerfCounter.print"))).willReturn("java.threads.live=2");
		given(executor.run(List.of("jcmd", "123", "help", "VM.native_memory")))
			.willThrow(new IllegalStateException("attach denied"));

		MemoryGcEvidencePack pack = testCollector(executor)
			.collectMemoryGcEvidence(new MemoryGcEvidenceRequest(123L, false, false, false, "", null));

		assertThat(pack.nativeMemorySummary()).isNull();
		assertThat(pack.missingData()).contains("nativeMemorySummary");
		assertThat(pack.warnings()).anyMatch(w -> w.contains("unable to probe VM.native_memory support"));
	}

	@Test
	void shouldStillProbeNativeMemoryWhenCollectorIsUnknown() {
		CommandExecutor executor = mock(CommandExecutor.class);
		given(executor.run(List.of("jcmd", "123", "VM.flags"))).willReturn("-Xmx1024m");
		given(executor.run(List.of("jcmd", "123", "VM.version"))).willReturn("123:\nOpenJDK 64-Bit Server VM 21.0.3");
		given(executor.run(List.of("jcmd", "123", "GC.heap_info"))).willReturn("some heap text without collector");
		given(executor.run(List.of("jstat", "-gcutil", "123"))).willReturn("""
				  S0 S1 E O M CCS YGC YGCT FGC FGCT CGC CGCT GCT
				  0.00 0.00 1.00 1.00 1.00 1.00 1 0.001 0 0.000 - - 0.001
				""");
		given(executor.run(List.of("jstat", "-class", "123"))).willReturn("Loaded  Bytes\n1 1.00\n");
		given(executor.run(List.of("jcmd", "123", "PerfCounter.print"))).willReturn("java.threads.live=2");
		given(executor.run(List.of("jcmd", "123", "help", "VM.native_memory"))).willReturn("VM.native_memory");
		given(executor.run(List.of("jcmd", "123", "VM.native_memory", "summary"))).willReturn("""
				Total: reserved=1024M, committed=768M
				-                            NIO (reserved=128M, committed=96M)
				-                          Class (reserved=256M, committed=192M)
				""");
		given(executor.run(List.of("jcmd", "123", "VM.native_memory", "summary.diff"))).willReturn("");

		MemoryGcEvidencePack pack = testCollector(executor)
			.collectMemoryGcEvidence(new MemoryGcEvidenceRequest(123L, false, false, false, "", null));

		assertThat(pack.nativeMemorySummary()).isNotNull();
		assertThat(pack.missingData()).doesNotContain("nativeMemorySummary");
		assertThat(pack.warnings()).anyMatch(w -> w.contains("unsupported/unknown GC collector unknown"));
	}

	@Test
	void shouldKeepResourceBudgetCollectionBestEffortWhenProcessRssUnavailable() {
		CommandExecutor executor = mock(CommandExecutor.class);
		String pid = "987654321";
		given(executor.run(List.of("jcmd", pid, "VM.flags"))).willReturn("-XX:+UseG1GC -Xmx1024m");
		given(executor.run(List.of("jcmd", pid, "VM.version"))).willReturn(pid + ":\nOpenJDK 64-Bit Server VM 21.0.3");
		given(executor.run(List.of("jcmd", pid, "GC.heap_info")))
			.willReturn("garbage-first heap total 1048576K, used 524288K");
		given(executor.run(List.of("jstat", "-gcutil", pid))).willReturn("""
				  S0 S1 E O M CCS YGC YGCT FGC FGCT CGC CGCT GCT
				  0.00 0.00 1.00 1.00 1.00 1.00 1 0.001 0 0.000 - - 0.001
				""");
		given(executor.run(List.of("jstat", "-class", pid))).willReturn("Loaded  Bytes\n1 1.00\n");
		given(executor.run(List.of("jcmd", pid, "PerfCounter.print"))).willReturn("java.threads.live=2");
		given(executor.run(List.of("jcmd", pid, "help", "VM.native_memory")))
			.willThrow(new IllegalStateException("attach denied"));
		given(executor.run(List.of("ps", "-o", "rss=", "-p", pid)))
			.willThrow(new IllegalStateException("ps unavailable"));

		MemoryGcEvidencePack pack = testCollector(executor)
			.collectMemoryGcEvidence(new MemoryGcEvidenceRequest(Long.parseLong(pid), false, false, false, "", null));

		assertThat(pack.resourceBudgetEvidence()).isNotNull();
		assertThat(pack.resourceBudgetEvidence().processRssBytes()).isNull();
		assertThat(pack.resourceBudgetEvidence().heapCommittedBytes()).isPositive();
		assertThat(pack.resourceBudgetEvidence().estimatedThreadStackBytes()).isEqualTo(2L * 1024L * 1024L);
	}

}
