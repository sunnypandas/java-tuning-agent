package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;

public interface HeapRetentionAnalyzer {

	HeapRetentionAnalysisResult analyze(Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars,
			String analysisDepth, List<String> focusTypes, List<String> focusPackages);

}
