package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadDumpInsightsRuleTest {

	@Test
	void shouldRequireMultipleHintLinesForDeadlock() {
		assertThat(ThreadDumpInsightsRule.isLikelyDeadlockSection(List.of("Found one Java-level deadlock:"))).isFalse();
		assertThat(ThreadDumpInsightsRule.isLikelyDeadlockSection(
				List.of("Found one Java-level deadlock:", "\"t1\" waiting to lock monitor"))).isTrue();
	}

	@Test
	void shouldRejectDeadlockWithoutJvmStyleHeader() {
		assertThat(ThreadDumpInsightsRule.isLikelyDeadlockSection(List.of("random line", "another line"))).isFalse();
	}
}
