package com.alibaba.cloud.ai.examples.javatuning.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GcCollectorDetectorTest {

	private final GcCollectorDetector detector = new GcCollectorDetector();

	@Test
	void detectsCommonCollectorsFromFlagsOrHeapInfo() {
		assertThat(detector.infer("-XX:+UseG1GC", "")).isEqualTo("G1");
		assertThat(detector.infer("-XX:+UseParallelGC", "")).isEqualTo("Parallel");
		assertThat(detector.infer("-XX:+UseParallelOldGC", "")).isEqualTo("Parallel");
		assertThat(detector.infer("-XX:+UseSerialGC", "")).isEqualTo("Serial");
		assertThat(detector.infer("-XX:+UseConcMarkSweepGC", "")).isEqualTo("CMS");
		assertThat(detector.infer("-XX:+UseZGC", "")).isEqualTo("ZGC");
		assertThat(detector.infer("", "parallel heap total 1K")).isEqualTo("Parallel");
		assertThat(detector.infer("", "PSYoungGen total 1K\nParOldGen total 1K")).isEqualTo("Parallel");
		assertThat(detector.infer("", "DefNew total 1K\nTenured generation total 1K")).isEqualTo("Serial");
		assertThat(detector.infer("", "ZHeap used 1M")).isEqualTo("ZGC");
	}

	@Test
	void returnsUnknownWhenNoSignalsExist() {
		assertThat(detector.infer("", "")).isEqualTo("unknown");
	}

}
