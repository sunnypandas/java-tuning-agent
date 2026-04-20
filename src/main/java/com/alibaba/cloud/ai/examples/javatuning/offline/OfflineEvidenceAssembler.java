package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapDumpShallowSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ThreadDumpParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ThreadDumpSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import org.springframework.beans.factory.annotation.Value;

/**
 * Assembles {@link MemoryGcEvidencePack} from an {@link OfflineBundleDraft} using parsed runtime
 * snapshot plus optional histogram and thread dump artifacts.
 */
public class OfflineEvidenceAssembler {

	private final OfflineJvmSnapshotAssembler snapshotAssembler;

	private final ClassHistogramParser histogramParser;

	private final ThreadDumpParser threadDumpParser;

	private final SharkHeapDumpSummarizer heapDumpSummarizer;

	private final boolean autoHeapSummary;

	public OfflineEvidenceAssembler(OfflineJvmSnapshotAssembler snapshotAssembler,
			ClassHistogramParser histogramParser, ThreadDumpParser threadDumpParser,
			SharkHeapDumpSummarizer heapDumpSummarizer,
			@Value("${java-tuning-agent.heap-summary.auto-enabled:true}") boolean autoHeapSummary) {
		this.snapshotAssembler = snapshotAssembler;
		this.histogramParser = histogramParser;
		this.threadDumpParser = threadDumpParser;
		this.heapDumpSummarizer = heapDumpSummarizer;
		this.autoHeapSummary = autoHeapSummary;
	}

	public MemoryGcEvidencePack build(OfflineBundleDraft draft) {
		JvmRuntimeSnapshot snapshot = snapshotAssembler.assemble(draft);
		List<String> warnings = new ArrayList<>(snapshot.warnings());
		List<String> missingData = new ArrayList<>();

		ClassHistogramSummary classHistogram = loadHistogram(draft, warnings, missingData);
		ThreadDumpSummary threadDump = loadThreadDump(draft, warnings, missingData);

		String heapDumpPath = draft.heapDumpAbsolutePath();

		HeapDumpShallowSummary heapShallowSummary = summarizeHeapDumpIfEligible(heapDumpPath, warnings);

		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, List.copyOf(missingData),
				List.copyOf(warnings), heapDumpPath, heapShallowSummary);
	}

	private HeapDumpShallowSummary summarizeHeapDumpIfEligible(String heapDumpAbsolutePath, List<String> warnings) {
		if (!autoHeapSummary || heapDumpAbsolutePath == null || heapDumpAbsolutePath.isBlank()) {
			return null;
		}
		Path path = Path.of(heapDumpAbsolutePath.trim()).toAbsolutePath().normalize();
		if (!Files.isRegularFile(path)) {
			warnings.add("heapDumpAbsolutePath is set but file is missing or not a regular file: " + path);
			return null;
		}
		HeapDumpShallowSummary summary = heapDumpSummarizer.summarize(path, null, null);
		if (!summary.analysisSucceeded()) {
			warnings.add("Heap dump shallow summary failed: " + summary.errorMessage());
		}
		return summary;
	}

	private ClassHistogramSummary loadHistogram(OfflineBundleDraft draft, List<String> warnings,
			List<String> missingData) {
		try {
			String text = OfflineTextLoader.load(draft.classHistogram());
			if (text.isBlank()) {
				missingData.add("classHistogram");
				return null;
			}
			ClassHistogramSummary parsed = histogramParser.parse(text);
			if (parsed.entries().isEmpty()) {
				missingData.add("classHistogram");
				warnings.add("GC.class_histogram output was missing or could not be parsed");
			}
			return parsed;
		}
		catch (IOException ex) {
			missingData.add("classHistogram");
			warnings.add("Unable to load class histogram: " + ex.getMessage());
			return null;
		}
		catch (RuntimeException ex) {
			missingData.add("classHistogram");
			warnings.add("Unable to parse class histogram: " + ex.getMessage());
			return null;
		}
	}

	private ThreadDumpSummary loadThreadDump(OfflineBundleDraft draft, List<String> warnings,
			List<String> missingData) {
		try {
			String text = OfflineTextLoader.load(draft.threadDump());
			if (text.isBlank()) {
				missingData.add("threadDump");
				return null;
			}
			ThreadDumpSummary parsed = threadDumpParser.parse(text);
			if (parsed.threadCount() == 0) {
				missingData.add("threadDump");
				warnings.add("Thread dump text was present but could not be parsed");
			}
			return parsed;
		}
		catch (IOException ex) {
			missingData.add("threadDump");
			warnings.add("Unable to load thread dump: " + ex.getMessage());
			return null;
		}
		catch (RuntimeException ex) {
			missingData.add("threadDump");
			warnings.add("Unable to parse thread dump: " + ex.getMessage());
			return null;
		}
	}

}
