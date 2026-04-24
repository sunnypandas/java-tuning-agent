package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Path;

@FunctionalInterface
interface JfrSummaryParserAdapter {

	JfrSummary parse(Path path, int maxSummaryEvents);
}
