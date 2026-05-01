package com.alibaba.cloud.ai.examples.javatuning.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GcHeapInfoParserTest {

	@Test
	void shouldParseG1HeapInfoModernJdk() {
		String output = """
				20380:
				garbage-first heap   total reserved 262144K, committed 262144K, used 218758K  [0x00000000, 0x00000000)
				region size 1M, 1 eden (1M), 1 survivor (1M), 1 old (1M), 0 humongous (0M), 1 free (1M)
				""";

		JvmMemorySnapshot parsed = new GcHeapInfoParser().parse(output);

		assertThat(parsed.heapUsedBytes()).isEqualTo(218758L * 1024L);
		assertThat(parsed.heapCommittedBytes()).isEqualTo(262144L * 1024L);
	}

	@Test
	void shouldParseG1HeapInfo() {
		String output = """
				14628:
				garbage-first heap total 262144K, used 218758K
				G1 Young Generation
				  Eden regions: 12->0(24)
				  Survivor regions: 4->4(4)
				G1 Old Generation
				  used 131072K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""";

		JvmMemorySnapshot parsed = new GcHeapInfoParser().parse(output);

		assertThat(parsed.heapUsedBytes()).isEqualTo(218758L * 1024L);
		assertThat(parsed.heapCommittedBytes()).isEqualTo(262144L * 1024L);
		assertThat(parsed.heapMaxBytes()).isZero();
		assertThat(parsed.oldGenUsedBytes()).isEqualTo(131072L * 1024L);
		assertThat(parsed.metaspaceUsedBytes()).isEqualTo(8192L * 1024L);
		assertThat(parsed.xmsBytes()).isNull();
		assertThat(parsed.xmxBytes()).isNull();
		assertThat(parsed.oldGenCommittedBytes()).isNull();
	}

	@Test
	void shouldParseOldGenCommittedWhenPresent() {
		String output = """
				14628:
				garbage-first heap total 262144K, used 218758K
				G1 Old Generation
				  used 131072K, capacity 200000K, committed 200000K
				Metaspace       used 8192K, committed 9216K, reserved 65536K
				""";

		JvmMemorySnapshot parsed = new GcHeapInfoParser().parse(output);

		assertThat(parsed.oldGenUsedBytes()).isEqualTo(131072L * 1024L);
		assertThat(parsed.oldGenCommittedBytes()).isEqualTo(200000L * 1024L);
	}

	@Test
	void shouldIgnoreMalformedHumanTokens() {
		String output = """
				heap.max=268435456
				G1 heap committed nopeK used 227391K
				Metaspace       used not-a-numberK, committed 9216K, reserved 65536K
				""";

		JvmMemorySnapshot parsed = new GcHeapInfoParser().parse(output);

		assertThat(parsed.heapUsedBytes()).isZero();
		assertThat(parsed.heapCommittedBytes()).isZero();
		assertThat(parsed.metaspaceUsedBytes()).isNull();
	}

	@Test
	void shouldParseCompactG1HeapLine() {
		String output = """
				heap.max=268435456
				G1 heap committed 262144K used 227391K
				""";

		JvmMemorySnapshot parsed = new GcHeapInfoParser().parse(output);

		assertThat(parsed.heapCommittedBytes()).isEqualTo(262144L * 1024L);
		assertThat(parsed.heapUsedBytes()).isEqualTo(227391L * 1024L);
	}

}
