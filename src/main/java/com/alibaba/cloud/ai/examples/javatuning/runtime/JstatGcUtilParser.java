package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public class JstatGcUtilParser {

	public JvmGcSnapshot parse(String output) {
		if (output == null || output.isBlank()) {
			return new JvmGcSnapshot("unknown", 0L, 0L, 0L, 0L, null);
		}

		List<String> lines = output.lines().map(String::trim).filter(line -> !line.isEmpty()).toList();
		if (lines.size() < 2) {
			return new JvmGcSnapshot("unknown", 0L, 0L, 0L, 0L, null);
		}

		String[] values = lines.get(lines.size() - 1).trim().split("\\s+");
		long youngGcCount = values.length > 6 ? parseLong(values[6]) : 0L;
		long youngGcTimeMs = values.length > 7 ? secondsToMillis(values[7]) : 0L;
		long fullGcCount = values.length > 8 ? parseLong(values[8]) : 0L;
		long fullGcTimeMs = values.length > 9 ? secondsToMillis(values[9]) : 0L;

		Double oldUsagePercent = values.length > 3 ? parseDoubleOrNull(values[3]) : null;
		return new JvmGcSnapshot("unknown", youngGcCount, youngGcTimeMs, fullGcCount, fullGcTimeMs,
				oldUsagePercent);
	}

	private long parseLong(String value) {
		if ("-".equals(value)) {
			return 0L;
		}
		return Long.parseLong(value);
	}

	private Double parseDoubleOrNull(String value) {
		if ("-".equals(value)) {
			return null;
		}
		return Double.valueOf(value);
	}

	private long secondsToMillis(String value) {
		if ("-".equals(value)) {
			return 0L;
		}
		return Math.round(Double.parseDouble(value) * 1000d);
	}

}
