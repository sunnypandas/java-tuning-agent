package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NativeMemorySummaryParser {

	private static final Pattern TOTAL_PATTERN = Pattern.compile(
			"(?i)total:\\s*reserved=([+-]?\\d+)\\s*([kmg]?b?)(?:\\s+([+-]\\d+)\\s*([kmg]?b?))?\\s*,\\s*committed=([+-]?\\d+)\\s*([kmg]?b?)(?:\\s+([+-]\\d+)\\s*([kmg]?b?))?");

	private static final Pattern CATEGORY_PATTERN = Pattern.compile(
			"(?i)^-\\s*([A-Za-z][A-Za-z\\s/_-]*)\\s*\\(reserved=([+-]?\\d+)\\s*([kmg]?b?)(?:\\s+([+-]\\d+)\\s*([kmg]?b?))?\\s*,\\s*committed=([+-]?\\d+)\\s*([kmg]?b?)(?:\\s+([+-]\\d+)\\s*([kmg]?b?))?\\)");

	private static final Pattern DIFF_DELTA_PATTERN = Pattern.compile("(?i)reserved=[^,)]*\\s+[+-]\\d+\\s*[kmg]?b?");

	public NativeMemorySummary parse(String text) {
		if (text == null || text.isBlank()) {
			return new NativeMemorySummary(0L, 0L, null, null, null, null, List.of("NMT summary is blank"));
		}
		long totalReserved = 0L;
		long totalCommitted = 0L;
		Long directReserved = null;
		Long directCommitted = null;
		Long classReserved = null;
		Long classCommitted = null;
		List<String> warnings = new ArrayList<>();
		Map<String, NativeMemorySummary.CategoryUsage> categories = new LinkedHashMap<>();
		Map<String, NativeMemorySummary.CategoryGrowth> growth = new LinkedHashMap<>();
		boolean diffBlock = text.toLowerCase(Locale.ROOT).contains("summary.diff")
				|| text.contains("reserved=+") || text.contains("reserved=-")
				|| DIFF_DELTA_PATTERN.matcher(text).find();

		Matcher total = TOTAL_PATTERN.matcher(text);
		if (total.find()) {
			totalReserved = toBytes(total.group(3) != null ? total.group(3) : total.group(1),
					total.group(3) != null ? total.group(4) : total.group(2));
			totalCommitted = toBytes(total.group(7) != null ? total.group(7) : total.group(5),
					total.group(7) != null ? total.group(8) : total.group(6));
		}
		else {
			warnings.add("Could not parse NMT total reserved/committed bytes");
		}

		for (String line : text.split("\\R")) {
			Matcher category = CATEGORY_PATTERN.matcher(line.trim());
			if (!category.find()) {
				continue;
			}
			String name = category.group(1).trim().toLowerCase();
			long reserved = toBytes(diffBlock && category.group(4) != null ? category.group(4) : category.group(2),
					diffBlock && category.group(4) != null ? category.group(5) : category.group(3));
			long committed = toBytes(diffBlock && category.group(8) != null ? category.group(8) : category.group(6),
					diffBlock && category.group(8) != null ? category.group(9) : category.group(7));
			if (diffBlock) {
				growth.put(name, new NativeMemorySummary.CategoryGrowth(reserved, committed));
			}
			else {
				categories.put(name, new NativeMemorySummary.CategoryUsage(reserved, committed));
			}
			if (name.equals("nio")) {
				directReserved = reserved;
				directCommitted = committed;
			}
			if (name.equals("class")) {
				classReserved = reserved;
				classCommitted = committed;
			}
		}
		if (directReserved == null || directCommitted == null) {
			warnings.add("NMT did not expose NIO category for direct buffer analysis");
		}
		if (classReserved == null || classCommitted == null) {
			warnings.add("NMT did not expose Class category for metaspace analysis");
		}
		return new NativeMemorySummary(totalReserved, totalCommitted, directReserved, directCommitted, classReserved,
				classCommitted, categories, growth, warnings);
	}

	private long toBytes(String value, String unit) {
		long parsed = Long.parseLong(value);
		if (unit == null || unit.isBlank()) {
			return parsed;
		}
		return switch (unit.toLowerCase(Locale.ROOT)) {
			case "k", "kb" -> parsed * 1024L;
			case "m", "mb" -> parsed * 1024L * 1024L;
			case "g", "gb" -> parsed * 1024L * 1024L * 1024L;
			default -> parsed;
		};
	}

}
