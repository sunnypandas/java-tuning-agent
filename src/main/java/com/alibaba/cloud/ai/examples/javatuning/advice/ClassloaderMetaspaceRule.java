package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassloaderMetaspaceEntry;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassloaderMetaspaceSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;

public final class ClassloaderMetaspaceRule implements DiagnosisRule {

	private static final long HIGH_CLASS_COUNT = 500L;

	private static final long HIGH_BYTES = 32L * 1024L * 1024L;

	private static final long SIGNIFICANT_CLASS_GROWTH_BYTES = 32L * 1024L * 1024L;

	private static final double HIGH_UTILIZATION_PERCENT = 90.0d;

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		ClassloaderMetaspaceSummary summary = evidence.classloaderMetaspaceSummary();
		if (summary == null || summary.entries().isEmpty()) {
			return;
		}
		ClassloaderMetaspaceEntry topByClassCount = summary.entries()
			.stream()
			.max(Comparator.comparingLong(entry -> valueOrZero(entry.classCount())))
			.orElse(null);
		ClassloaderMetaspaceEntry topByBytes = summary.entries()
			.stream()
			.max(Comparator.comparingLong(entry -> valueOrZero(entry.bytes())))
			.orElse(null);
		long topClassCount = topByClassCount == null ? 0L : valueOrZero(topByClassCount.classCount());
		long topBytes = topByBytes == null ? 0L : valueOrZero(topByBytes.bytes());
		String repeatedPattern = repeatedPattern(summary);
		boolean nmtGrowthCorroborated = classCommittedGrowth(evidence.nativeMemorySummary()) >= SIGNIFICANT_CLASS_GROWTH_BYTES;
		boolean metaspaceUtilCorroborated = metaspaceUtilization(evidence) >= HIGH_UTILIZATION_PERCENT;
		if (topClassCount < HIGH_CLASS_COUNT && topBytes < HIGH_BYTES && repeatedPattern.isBlank()
				&& !nmtGrowthCorroborated && !metaspaceUtilCorroborated) {
			return;
		}
		String topName = topByClassCount != null ? topByClassCount.classLoaderName() : "unknown";
		scratch.addFinding(new TuningFinding("Suspected classloader retention or churn", "medium",
				"topClassLoader=" + topName + ", topClassCount=" + topClassCount + ", topBytesMb=" + mb(topBytes)
						+ ", repeatedLoaderPattern=" + (repeatedPattern.isBlank() ? "none" : repeatedPattern)
						+ ", nmtClassGrowthCorroborated=" + nmtGrowthCorroborated
						+ ", metaspaceUtilCorroborated=" + metaspaceUtilCorroborated,
				"rule-based",
				"Classloader statistics point to retained or repeatedly generated class metadata; confirm lifecycle ownership before treating it as a leak"));
		scratch.addRecommendation(new TuningRecommendation("Inspect classloader retention and generated class lifecycle",
				"application-code", "Compare VM.classloader_stats or jmap -clstats before and after a steady window",
				"Identify dynamic proxy, plugin, script, JSP, hot-deploy, or tenant loaders that are not released",
				"Classloader stats alone show suspicion, not definitive retained roots",
				"Need owner mapping for custom classloader creation and unload points"));
	}

	private static String repeatedPattern(ClassloaderMetaspaceSummary summary) {
		Map<String, Long> counts = summary.entries()
			.stream()
			.map(entry -> pattern(entry.classLoaderName()))
			.filter(pattern -> !pattern.isBlank())
			.collect(Collectors.groupingBy(pattern -> pattern, Collectors.counting()));
		return counts.entrySet()
			.stream()
			.filter(entry -> entry.getValue() >= 3L)
			.max(Map.Entry.comparingByValue())
			.map(Map.Entry::getKey)
			.orElse("");
	}

	private static String pattern(String name) {
		if (name == null || name.isBlank()) {
			return "";
		}
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.contains("proxy")) {
			return "proxy";
		}
		if (lower.contains("bytebuddy")) {
			return "bytebuddy";
		}
		if (lower.contains("cglib")) {
			return "cglib";
		}
		if (lower.contains("groovy")) {
			return "groovy";
		}
		if (lower.contains("jsp")) {
			return "jsp";
		}
		if (lower.contains("plugin")) {
			return "plugin";
		}
		if (lower.contains("generated")) {
			return "generated";
		}
		return "";
	}

	private static long classCommittedGrowth(NativeMemorySummary summary) {
		if (summary == null || summary.categoryGrowth().isEmpty()) {
			return 0L;
		}
		NativeMemorySummary.CategoryGrowth growth = summary.categoryGrowth().get("class");
		return growth == null ? 0L : growth.committedDeltaBytes();
	}

	private static double metaspaceUtilization(MemoryGcEvidencePack evidence) {
		if (evidence.snapshot() == null || evidence.snapshot().gc() == null) {
			return 0.0d;
		}
		Double metaspace = evidence.snapshot().gc().metaspaceUtilPercent();
		Double compressed = evidence.snapshot().gc().compressedClassSpaceUtilPercent();
		return Math.max(metaspace == null ? 0.0d : metaspace, compressed == null ? 0.0d : compressed);
	}

	private static long valueOrZero(Long value) {
		return value == null ? 0L : value;
	}

	private static long mb(long bytes) {
		return Math.max(0L, bytes / (1024L * 1024L));
	}

}
