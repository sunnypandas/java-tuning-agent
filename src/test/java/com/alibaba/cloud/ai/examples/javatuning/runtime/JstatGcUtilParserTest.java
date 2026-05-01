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

	@Test
	void shouldIgnoreMalformedGcUtilTokens() {
		String output = """
				targetPid: 1961
				jstat -gcutil: YGC FGC YGCT nope FGC 0 FGCT 0.000
				""";

		JvmGcSnapshot parsed = new JstatGcUtilParser().parse(output);

		assertThat(parsed.youngGcCount()).isZero();
		assertThat(parsed.youngGcTimeMs()).isZero();
		assertThat(parsed.fullGcCount()).isZero();
		assertThat(parsed.fullGcTimeMs()).isZero();
	}

	@Test
	void shouldParseCompactGcUtilLine() {
		String output = """
				targetPid: 1961
				jstat -gcutil: YGC 8 YGCT 0.015 FGC 0 FGCT 0.000
				""";

		JvmGcSnapshot parsed = new JstatGcUtilParser().parse(output);

		assertThat(parsed.youngGcCount()).isEqualTo(8L);
		assertThat(parsed.youngGcTimeMs()).isEqualTo(15L);
		assertThat(parsed.fullGcCount()).isZero();
		assertThat(parsed.fullGcTimeMs()).isZero();
	}

}
