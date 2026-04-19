package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TuningAdviceReportFormatterTest {

	@Test
	void includesHotspotSectionInFixedOrder() {
		var report = new TuningAdviceReport(
				List.of(new TuningFinding("F", "high", "e", "rule", "i")),
				List.of(new TuningRecommendation("act", "jvm-gc", "cfg", "ben", "risk", "pre")),
				List.of(new SuspectedCodeHotspot("com.example.A", "/src/A.java", "big", "histogram", "low")),
				List.of("missing-x"),
				List.of("next-y"),
				"medium",
				List.of("reason-1"),
				"");
		String md = TuningAdviceReportFormatter.toMarkdown(report);
		assertThat(md.indexOf("## Findings")).isLessThan(md.indexOf("## Recommendations"));
		assertThat(md.indexOf("## Recommendations")).isLessThan(md.indexOf("## Suspected code hotspots"));
		assertThat(md.indexOf("## Suspected code hotspots")).isLessThan(md.indexOf("## Missing data"));
		assertThat(md).contains("com.example.A").contains("/src/A.java");
		assertThat(md).contains("### 1. F").contains("```text").contains("e");
		assertThat(md).contains("### 1. act").contains("`jvm-gc`");
		assertThat(md).contains("### 1. `com.example.A`");
	}
}
