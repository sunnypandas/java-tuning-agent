package com.alibaba.cloud.ai.examples.javatuning.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ThreadDumpParserTest {

	@Test
	void shouldParseTopCpuThreadsFromJcmdThreadPrint() {
		ThreadDumpSummary summary = new ThreadDumpParser().parse("""
				Full thread dump OpenJDK 64-Bit Server VM:

				"http-nio-8091-exec-4" #41 prio=5 os_prio=31 cpu=2450.25ms elapsed=30.20s tid=0x0000600002a10000 nid=0x7b03 runnable [0x000000016f4a7000]
				   java.lang.Thread.State: RUNNABLE
				       at com.alibaba.cloud.ai.compat.memoryleakdemo.churn.JfrWorkloadService.burnCpu(JfrWorkloadService.java:88)
				       at com.alibaba.cloud.ai.compat.memoryleakdemo.web.LeakController.runJfrWorkload(LeakController.java:151)

				"boundedElastic-1" #42 prio=5 os_prio=31 cpu=12.00ms elapsed=30.20s tid=0x0000600002a12000 nid=0x7b04 waiting on condition [0x000000016f5aa000]
				   java.lang.Thread.State: WAITING (parking)
				       at jdk.internal.misc.Unsafe.park(Native Method)
				""");

		assertThat(summary.threadCount()).isEqualTo(2);
		assertThat(summary.topCpuThreads()).hasSize(2);
		assertThat(summary.topCpuThreads().get(0).threadName()).isEqualTo("http-nio-8091-exec-4");
		assertThat(summary.topCpuThreads().get(0).cpuTimeMs()).isEqualTo(2450.25d);
		assertThat(summary.topCpuThreads().get(0).nid()).isEqualTo("0x7b03");
		assertThat(summary.topCpuThreads().get(0).state()).isEqualTo("RUNNABLE");
		assertThat(summary.topCpuThreads().get(0).topFrame())
			.isEqualTo("com.alibaba.cloud.ai.compat.memoryleakdemo.churn.JfrWorkloadService.burnCpu(JfrWorkloadService.java:88)");
	}

}
