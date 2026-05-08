package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JstatGcUtilParser {

	private static final Pattern LABELED_VALUE = Pattern
		.compile("(?i)\\b(YGC|YGCT|FGC|FGCT|O|M|CCS)\\b\\s+(-|\\d+(?:\\.\\d+)?)");

	public JvmGcSnapshot parse(String output) {
		if (output == null || output.isBlank()) {
			return new JvmGcSnapshot("unknown", 0L, 0L, 0L, 0L, null);
		}

		List<String> lines = output.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
		JvmGcSnapshot compact = parseLabeledGcUtil(lines);
		if (compact != null) {
			return compact;
		}
		if (lines.size() < 2) {
			return new JvmGcSnapshot("unknown", 0L, 0L, 0L, 0L, null);
		}

		String[] values = lines.get(lines.size() - 1).trim().split("\\s+");
		long youngGcCount = values.length > 6 ? parseLongOrZero(values[6]) : 0L;
		long youngGcTimeMs = values.length > 7 ? secondsToMillis(values[7]) : 0L;
		long fullGcCount = values.length > 8 ? parseLongOrZero(values[8]) : 0L;
		long fullGcTimeMs = values.length > 9 ? secondsToMillis(values[9]) : 0L;

		Double oldUsagePercent = values.length > 3 ? parseDoubleOrNull(values[3]) : null;
		Double metaspaceUtilPercent = values.length > 4 ? parseDoubleOrNull(values[4]) : null;
		Double compressedClassSpaceUtilPercent = values.length > 5 ? parseDoubleOrNull(values[5]) : null;
		return new JvmGcSnapshot("unknown", youngGcCount, youngGcTimeMs, fullGcCount, fullGcTimeMs,
				oldUsagePercent, metaspaceUtilPercent, compressedClassSpaceUtilPercent);
	}

	private JvmGcSnapshot parseLabeledGcUtil(List<String> lines) {
		String labeledLine = lines.stream()
			.filter(line -> line.toLowerCase(Locale.ROOT).contains("jstat -gcutil:"))
			.findFirst()
			.orElse("");
		if (labeledLine.isBlank()) {
			return null;
		}
		long youngGcCount = 0L;
		long youngGcTimeMs = 0L;
		long fullGcCount = 0L;
		long fullGcTimeMs = 0L;
		Double oldUsagePercent = null;
		Double metaspaceUtilPercent = null;
		Double compressedClassSpaceUtilPercent = null;
		Matcher matcher = LABELED_VALUE.matcher(labeledLine);
		while (matcher.find()) {
			String label = matcher.group(1).toUpperCase(Locale.ROOT);
			String value = matcher.group(2);
			switch (label) {
				case "YGC" -> youngGcCount = parseLongOrZero(value);
				case "YGCT" -> youngGcTimeMs = secondsToMillis(value);
				case "FGC" -> fullGcCount = parseLongOrZero(value);
				case "FGCT" -> fullGcTimeMs = secondsToMillis(value);
				case "O" -> oldUsagePercent = parseDoubleOrNull(value);
				case "M" -> metaspaceUtilPercent = parseDoubleOrNull(value);
				case "CCS" -> compressedClassSpaceUtilPercent = parseDoubleOrNull(value);
				default -> {
				}
			}
		}
		return new JvmGcSnapshot("unknown", youngGcCount, youngGcTimeMs, fullGcCount, fullGcTimeMs,
				oldUsagePercent, metaspaceUtilPercent, compressedClassSpaceUtilPercent);
	}

	private long parseLongOrZero(String value) {
		if ("-".equals(value)) {
			return 0L;
		}
		try {
			return Long.parseLong(value);
		}
		catch (NumberFormatException ex) {
			return 0L;
		}
	}

	private Double parseDoubleOrNull(String value) {
		if ("-".equals(value)) {
			return null;
		}
		try {
			return Double.valueOf(value);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private long secondsToMillis(String value) {
		if ("-".equals(value)) {
			return 0L;
		}
		try {
			return Math.round(Double.parseDouble(value) * 1000d);
		}
		catch (NumberFormatException ex) {
			return 0L;
		}
	}

}
