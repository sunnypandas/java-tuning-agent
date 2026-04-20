package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharkHeapDumpSummarizerTest {

	@Test
	void missingFileReturnsError() {
		var summarizer = new SharkHeapDumpSummarizer(40, 1000);
		Path fake = Path.of(System.getProperty("java.io.tmpdir"), "nonexistent-heap-" + System.nanoTime() + ".hprof");
		var result = summarizer.summarize(fake, null, null);
		assertThat(result.analysisSucceeded()).isFalse();
		assertThat(result.errorMessage()).contains("Not a regular file");
	}

}
