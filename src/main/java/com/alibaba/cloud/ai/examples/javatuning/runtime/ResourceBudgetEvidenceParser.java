package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ResourceBudgetEvidenceParser {

	private static final Pattern KEY_VALUE = Pattern.compile("(?im)^\\s*([a-zA-Z][a-zA-Z0-9_.-]*)\\s*[:=]\\s*([^\\r\\n]+)");

	public ResourceBudgetEvidence parse(String text, JvmRuntimeSnapshot snapshot, NativeMemorySummary nativeMemorySummary) {
		List<String> warnings = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		Long containerLimit = readLong(text, "containerMemoryLimitBytes", "container.memory.limit.bytes",
				"memory.max", "memoryLimitBytes");
		Long rss = readLong(text, "processRssBytes", "rssBytes", "process.rss.bytes", "VmRSS");
		Double cpuQuota = readDouble(text, "cpuQuotaCores", "cpu.quota.cores");
		Long threadStack = readLong(text, "estimatedThreadStackBytes", "threadStackBytes",
				"estimated.thread.stack.bytes");

		Long heapCommitted = snapshot != null && snapshot.memory() != null ? snapshot.memory().heapCommittedBytes() : null;
		Long heapMax = snapshot != null && snapshot.memory() != null ? snapshot.memory().heapMaxBytes() : null;
		Long nativeCommitted = nativeMemorySummary != null && nativeMemorySummary.totalCommittedBytes() > 0L
				? nativeMemorySummary.totalCommittedBytes() : null;
		if (threadStack == null && snapshot != null && snapshot.threadCount() != null) {
			threadStack = snapshot.threadCount() * 1024L * 1024L;
		}
		Long estimatedTotal = sumPositive(heapCommitted, nativeCommitted, threadStack);

		if (containerLimit == null) {
			missing.add("containerMemoryLimit");
		}
		if (rss == null) {
			missing.add("processRss");
		}
		if (containerLimit == null && rss == null && cpuQuota == null) {
			warnings.add("Resource budget evidence did not include container limit, RSS, or CPU quota");
		}
		ResourceBudgetEvidence evidence = new ResourceBudgetEvidence(containerLimit, rss, cpuQuota, heapCommitted,
				heapMax, nativeCommitted, threadStack, estimatedTotal, warnings, missing);
		return evidence.hasAnyMemorySignal() || cpuQuota != null ? evidence : null;
	}

	private static Long readLong(String text, String... keys) {
		String value = readValue(text, keys);
		if (value == null) {
			return null;
		}
		return parseBytes(value);
	}

	private static Double readDouble(String text, String... keys) {
		String value = readValue(text, keys);
		if (value == null) {
			return null;
		}
		try {
			return Double.parseDouble(value.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String readValue(String text, String... keys) {
		if (text == null || text.isBlank()) {
			return null;
		}
		Matcher matcher = KEY_VALUE.matcher(text);
		while (matcher.find()) {
			String key = normalizeKey(matcher.group(1));
			for (String expected : keys) {
				if (key.equals(normalizeKey(expected))) {
					return matcher.group(2).trim();
				}
			}
		}
		return null;
	}

	static Long parseBytes(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String value = raw.trim().replace(",", "");
		Matcher matcher = Pattern.compile("(?i)^([+-]?\\d+)(?:\\s*([kmgt]i?b?|bytes?))?.*").matcher(value);
		if (!matcher.matches()) {
			return null;
		}
		try {
			long parsed = Long.parseLong(matcher.group(1));
			if (parsed < 0L) {
				return null;
			}
			String unit = matcher.group(2) == null ? "" : matcher.group(2).toLowerCase(Locale.ROOT);
			long multiplier = switch (unit) {
				case "k", "kb", "kib" -> 1024L;
				case "m", "mb", "mib" -> 1024L * 1024L;
				case "g", "gb", "gib" -> 1024L * 1024L * 1024L;
				case "t", "tb", "tib" -> 1024L * 1024L * 1024L * 1024L;
				default -> 1L;
			};
			return Math.multiplyExact(parsed, multiplier);
		}
		catch (NumberFormatException | ArithmeticException ex) {
			return null;
		}
	}

	private static String normalizeKey(String key) {
		return key == null ? "" : key.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
	}

	private static Long sumPositive(Long... values) {
		long sum = 0L;
		boolean any = false;
		for (Long value : values) {
			if (value != null && value > 0L) {
				sum += value;
				any = true;
			}
		}
		return any ? sum : null;
	}
}
