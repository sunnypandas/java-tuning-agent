package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.GcLogSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.GcUnifiedLogParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapDumpShallowSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummaryParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResultParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ResourceBudgetEvidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ResourceBudgetEvidenceParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCapabilitiesPolicy;
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

	private final GcUnifiedLogParser gcLogParser = new GcUnifiedLogParser();

	private final SharkHeapDumpSummarizer heapDumpSummarizer;

	private final NativeMemorySummaryParser nativeMemorySummaryParser = new NativeMemorySummaryParser();

	private final RepeatedSamplingResultParser repeatedSamplingResultParser = new RepeatedSamplingResultParser();

	private final ResourceBudgetEvidenceParser resourceBudgetEvidenceParser = new ResourceBudgetEvidenceParser();

	private final JvmCapabilitiesPolicy capabilitiesPolicy = new JvmCapabilitiesPolicy();

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
		GcLogSummary gcLogSummary = loadGcLog(draft, warnings, missingData);
		NativeMemorySummary nativeMemorySummary = loadNativeMemorySummary(draft, snapshot, warnings, missingData);
		RepeatedSamplingResult repeatedSamplingResult = loadRepeatedSamples(draft, warnings, missingData);
		ResourceBudgetEvidence resourceBudgetEvidence = loadResourceBudget(draft, snapshot, nativeMemorySummary);

		String heapDumpPath = draft.heapDumpAbsolutePath();

		HeapDumpShallowSummary heapShallowSummary = summarizeHeapDumpIfEligible(heapDumpPath, warnings);

		return new MemoryGcEvidencePack(snapshot, classHistogram, threadDump, List.copyOf(missingData),
				List.copyOf(warnings), heapDumpPath, heapShallowSummary).withGcLogSummary(gcLogSummary)
			.withNativeMemorySummary(nativeMemorySummary)
			.withRepeatedSamplingResult(repeatedSamplingResult)
			.withResourceBudgetEvidence(resourceBudgetEvidence);
	}

	public MemoryGcEvidencePack build(OfflineBundleDraft draft, HeapRetentionAnalysisResult heapRetentionAnalysis) {
		return build(draft).withHeapRetentionAnalysis(heapRetentionAnalysis);
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

	private GcLogSummary loadGcLog(OfflineBundleDraft draft, List<String> warnings, List<String> missingData) {
		String source = draft.gcLogPathOrText();
		if (source == null || source.isBlank()) {
			return null;
		}
		try {
			String text = loadPathOrInlineText(source);
			GcLogSummary summary = gcLogParser.parse(text);
			warnings.addAll(summary.warnings());
			if (!summary.hasPauseData()) {
				missingData.add("gcLogSummary");
			}
			return summary;
		}
		catch (IOException ex) {
			missingData.add("gcLog");
			warnings.add("Unable to load GC log: " + ex.getMessage());
			return null;
		}
		catch (RuntimeException ex) {
			missingData.add("gcLogSummary");
			warnings.add("Unable to parse GC log: " + ex.getMessage());
			return null;
		}
	}

	private NativeMemorySummary loadNativeMemorySummary(OfflineBundleDraft draft, JvmRuntimeSnapshot snapshot,
			List<String> warnings, List<String> missingData) {
		JvmCapabilitiesPolicy.NativeMemoryCapability capability = capabilitiesPolicy
			.nativeMemoryCapability(snapshot.gc().collector(), snapshot.jvmVersion());
		warnings.addAll(capability.warnings());
		if (!capability.nmtSupported()) {
			missingData.addAll(capability.missingData());
			return null;
		}
		String text = loadNativeSummaryText(draft);
		if (text == null || text.isBlank()) {
			missingData.add("nativeMemorySummary");
			return null;
		}
		try {
			NativeMemorySummary summary = nativeMemorySummaryParser.parse(extractNativeSummaryBlock(text));
			NativeMemorySummary diff = null;
			String diffBlock = extractNativeSummaryDiffBlock(text);
			if (diffBlock != null && !diffBlock.isBlank()) {
				diff = nativeMemorySummaryParser.parse(diffBlock);
			}
			summary = mergeNativeMemorySummary(summary, diff);
			warnings.addAll(summary.warnings());
			if (!summary.hasTotals()) {
				missingData.add("nativeMemorySummary");
			}
			return summary;
		}
		catch (RuntimeException ex) {
			missingData.add("nativeMemorySummary");
			warnings.add("Unable to parse native memory summary: " + ex.getMessage());
			return null;
		}
	}

	private RepeatedSamplingResult loadRepeatedSamples(OfflineBundleDraft draft, List<String> warnings,
			List<String> missingData) {
		String source = draft.repeatedSamplesPathOrText();
		if (source == null || source.isBlank()) {
			return null;
		}
		try {
			RepeatedSamplingResult result = repeatedSamplingResultParser.parse(loadPathOrInlineText(source));
			if (result == null || result.samples().isEmpty()) {
				missingData.add("repeatedSamples");
				warnings.add("Repeated samples text was present but could not be parsed");
				return null;
			}
			return result;
		}
		catch (IOException ex) {
			missingData.add("repeatedSamples");
			warnings.add("Unable to load repeated samples: " + ex.getMessage());
			return null;
		}
	}

	private ResourceBudgetEvidence loadResourceBudget(OfflineBundleDraft draft, JvmRuntimeSnapshot snapshot,
			NativeMemorySummary nativeMemorySummary) {
		String text = resourceBudgetText(draft);
		if (text.isBlank()) {
			return null;
		}
		return resourceBudgetEvidenceParser.parse(text, snapshot, nativeMemorySummary);
	}

	private static String resourceBudgetText(OfflineBundleDraft draft) {
		StringBuilder text = new StringBuilder();
		String note = draft.backgroundNotes().get("resourceBudget");
		if (note != null && !note.isBlank()) {
			text.append(note).append('\n');
		}
		String runtime = draft.runtimeSnapshotText();
		if (runtime != null && containsResourceBudgetHint(runtime)) {
			text.append(runtime);
		}
		return text.toString();
	}

	private static boolean containsResourceBudgetHint(String text) {
		String lower = text.toLowerCase(java.util.Locale.ROOT);
		return lower.contains("containermemorylimit") || lower.contains("memory.max") || lower.contains("vmrss")
				|| lower.contains("processrss") || lower.contains("cpuquotacores");
	}

	private String loadNativeSummaryText(OfflineBundleDraft draft) {
		try {
			String explicitNative = OfflineTextLoader.load(draft.nativeMemorySummary());
			if (!explicitNative.isBlank()) {
				return explicitNative;
			}
		}
		catch (IOException ex) {
			return "";
		}
		String runtimeText = draft.runtimeSnapshotText();
		if (runtimeText == null || runtimeText.isBlank()) {
			return "";
		}
		if (!runtimeText.contains("VM.native_memory") && !runtimeText.contains("Total: reserved=")) {
			return "";
		}
		return runtimeText;
	}

	private static String loadPathOrInlineText(String pathOrText) throws IOException {
		if (pathOrText.contains("\n") || pathOrText.contains("\r")) {
			return pathOrText;
		}
		Path path = Path.of(pathOrText.trim());
		if (Files.isRegularFile(path)) {
			return Files.readString(path);
		}
		return pathOrText;
	}

	private static String extractNativeSummaryBlock(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		int diffIndex = text.indexOf("VM.native_memory summary.diff");
		if (diffIndex <= 0) {
			return text;
		}
		return text.substring(0, diffIndex);
	}

	private static String extractNativeSummaryDiffBlock(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		int diffIndex = text.indexOf("VM.native_memory summary.diff");
		if (diffIndex < 0) {
			return "";
		}
		return text.substring(diffIndex);
	}

	private static NativeMemorySummary mergeNativeMemorySummary(NativeMemorySummary summary, NativeMemorySummary diff) {
		if (summary == null) {
			return diff;
		}
		if (diff == null || diff.categoryGrowth().isEmpty()) {
			return summary;
		}
		return new NativeMemorySummary(summary.totalReservedBytes(), summary.totalCommittedBytes(), summary.directReservedBytes(),
				summary.directCommittedBytes(), summary.classReservedBytes(), summary.classCommittedBytes(),
				summary.categories(), diff.categoryGrowth(), summary.warnings());
	}

}
