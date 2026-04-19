package com.alibaba.cloud.ai.examples.javatuning.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClassHistogramParserTest {

	@Test
	void shouldParseSimpleHistogramOutput() {
		String output = """
			 num     #instances         #bytes  class name
			----------------------------------------------
			   1:            10             400  java.lang.Object
			   2:             2              80  [B
			""";

		ClassHistogramSummary summary = new ClassHistogramParser().parse(output);

		assertThat(summary.entries()).hasSize(2);
		assertThat(summary.totalInstances()).isEqualTo(12L);
		assertThat(summary.totalBytes()).isEqualTo(480L);
		assertThat(summary.entries().get(0))
			.isEqualTo(new ClassHistogramEntry(1L, 10L, 400L, "java.lang.Object"));
		assertThat(summary.entries().get(1)).isEqualTo(new ClassHistogramEntry(2L, 2L, 80L, "[B"));
	}

}
