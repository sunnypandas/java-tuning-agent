package com.alibaba.cloud.ai.examples.javatuning.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JstatGcUtilParserTest {

	@Test
	void shouldParseGcUtilCounters() {
		String output = """
				  S0     S1     E      O      M      CCS     YGC     YGCT    FGC    FGCT     CGC    CGCT       GCT
				  0.00   0.00  12.34  78.90  92.21   88.12     145    1.234      2    0.456      -       -     1.690
				""";

		JvmGcSnapshot parsed = new JstatGcUtilParser().parse(output);

		assertThat(parsed.collector()).isEqualTo("unknown");
		assertThat(parsed.youngGcCount()).isEqualTo(145L);
		assertThat(parsed.youngGcTimeMs()).isEqualTo(1234L);
		assertThat(parsed.fullGcCount()).isEqualTo(2L);
		assertThat(parsed.fullGcTimeMs()).isEqualTo(456L);
		assertThat(parsed.oldUsagePercent()).isEqualTo(78.90d);
	}

}
