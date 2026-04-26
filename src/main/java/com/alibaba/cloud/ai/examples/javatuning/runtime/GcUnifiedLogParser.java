package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class GcUnifiedLogParser {

	private static final Pattern PAUSE_LINE = Pattern.compile(
			"GC\\(\\d+\\)\\s+Pause\\s+([^\\s]+).*?((\\d+(?:\\.\\d+)?)([KMGTP]?))->((\\d+(?:\\.\\d+)?)([KMGTP]?))\\((\\d+(?:\\.\\d+)?)([KMGTP]?)\\)\\s+(\\d+(?:\\.\\d+)?)ms",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern PAREN = Pattern.compile("\\(([^()]*)\\)");

	public GcLogSummary parse(String text) {
		if (text == null || text.isBlank()) {
			return new GcLogSummary(0, 0, 0, 0, 0.0d, 0.0d, null, null, 0, 0, Map.of(), List.of());
		}
		int pauses = 0;
		int young = 0;
		int full = 0;
		int concurrent = 0;
		int humongous = 0;
		int toSpaceExhausted = 0;
		double maxPause = 0.0d;
		double totalPause = 0.0d;
		Long maxBefore = null;
		Long minAfter = null;
		Map<String, Long> causes = new LinkedHashMap<>();
		List<String> warnings = new ArrayList<>();
		for (String line : text.lines().toList()) {
			String lower = line.toLowerCase(Locale.ROOT);
			if (lower.contains("to-space exhausted")) {
				toSpaceExhausted++;
			}
			Matcher matcher = PAUSE_LINE.matcher(line);
			if (!matcher.find()) {
				continue;
			}
			pauses++;
			String kind = matcher.group(1).toLowerCase(Locale.ROOT);
			if (kind.contains("young")) {
				young++;
			}
			else if (kind.contains("full")) {
				full++;
			}
			else {
				concurrent++;
			}
			double pauseMs = Double.parseDouble(matcher.group(10));
			totalPause += pauseMs;
			maxPause = Math.max(maxPause, pauseMs);
			long before = toBytes(matcher.group(3), matcher.group(4));
			long after = toBytes(matcher.group(6), matcher.group(7));
			maxBefore = maxBefore == null ? before : Math.max(maxBefore, before);
			minAfter = minAfter == null ? after : Math.min(minAfter, after);
			List<String> groups = parenthesizedGroups(line);
			if (!groups.isEmpty()) {
				String cause = groups.get(groups.size() - 1);
				causes.merge(cause, 1L, Long::sum);
				if (cause.toLowerCase(Locale.ROOT).contains("humongous")) {
					humongous++;
				}
			}
			else if (lower.contains("humongous")) {
				humongous++;
			}
		}
		if (pauses == 0) {
			warnings.add("GC log text was present but no unified GC pause lines were parsed");
		}
		return new GcLogSummary(pauses, young, full, concurrent, maxPause, totalPause, maxBefore, minAfter, humongous,
				toSpaceExhausted, topCauses(causes), warnings);
	}

	private static List<String> parenthesizedGroups(String line) {
		List<String> groups = new ArrayList<>();
		Matcher matcher = PAREN.matcher(line);
		while (matcher.find()) {
			String value = matcher.group(1);
			if (!value.matches("\\d+(?:\\.\\d+)?[KMGTP]?")) {
				groups.add(value);
			}
		}
		return groups;
	}

	private static Map<String, Long> topCauses(Map<String, Long> causes) {
		return causes.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry::getKey))
			.limit(8)
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
	}

	private static long toBytes(String value, String unit) {
		double parsed = Double.parseDouble(value);
		long multiplier = switch (unit == null ? "" : unit.toUpperCase(Locale.ROOT)) {
			case "K" -> 1024L;
			case "M" -> 1024L * 1024L;
			case "G" -> 1024L * 1024L * 1024L;
			case "T" -> 1024L * 1024L * 1024L * 1024L;
			case "P" -> 1024L * 1024L * 1024L * 1024L * 1024L;
			default -> 1L;
		};
		return Math.round(parsed * multiplier);
	}

}
