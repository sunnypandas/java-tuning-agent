package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ClassloaderMetaspaceParser {

	public ClassloaderMetaspaceSummary parse(String text) {
		if (text == null || text.isBlank()) {
			return new ClassloaderMetaspaceSummary(List.of(), 0L, 0L, List.of());
		}
		List<ClassloaderMetaspaceEntry> entries = new ArrayList<>();
		for (String rawLine : text.split("\\R")) {
			String line = rawLine.trim();
			if (skipLine(line)) {
				continue;
			}
			ClassloaderMetaspaceEntry entry = parseLine(line);
			if (entry != null) {
				entries.add(entry);
			}
		}
		List<String> warnings = entries.isEmpty()
				? List.of("Unable to parse classloader metaspace evidence: no classloader statistic rows found")
				: List.of();
		long totalClassCount = entries.stream().map(ClassloaderMetaspaceEntry::classCount).filter(v -> v != null)
			.mapToLong(Long::longValue)
			.sum();
		long totalBytes = entries.stream().map(ClassloaderMetaspaceEntry::bytes).filter(v -> v != null)
			.mapToLong(Long::longValue)
			.sum();
		return new ClassloaderMetaspaceSummary(entries, totalClassCount, totalBytes, warnings);
	}

	private static boolean skipLine(String line) {
		if (line.isBlank() || line.startsWith("-") || line.startsWith("=")) {
			return true;
		}
		String lower = line.toLowerCase(Locale.ROOT);
		return lower.startsWith("classloader ") || lower.startsWith("index ") || lower.startsWith("total ")
				|| lower.startsWith("jcmd ") || lower.startsWith("jmap ") || lower.matches("\\d+:");
	}

	private static ClassloaderMetaspaceEntry parseLine(String line) {
		String[] tokens = line.split("\\s+");
		if (tokens.length >= 6 && isBooleanToken(tokens[2]) && isNumber(tokens[3]) && isNumber(tokens[4])) {
			return parseClstatsLine(tokens, line);
		}
		if (tokens.length >= 7 && isNumber(tokens[3]) && isNumber(tokens[4]) && isNumber(tokens[5])) {
			return parseVmClassloaderStatsLine(tokens, line);
		}
		return null;
	}

	private static ClassloaderMetaspaceEntry parseVmClassloaderStatsLine(String[] tokens, String rawLine) {
		Long classCount = parseLong(tokens[3]);
		Long chunkBytes = parseLong(tokens[4]);
		Long blockBytes = parseLong(tokens[5]);
		Long bytes = null;
		if (chunkBytes != null || blockBytes != null) {
			bytes = valueOrZero(chunkBytes) + valueOrZero(blockBytes);
		}
		return new ClassloaderMetaspaceEntry(join(tokens, 6), tokens[1], classCount, bytes, null, rawLine);
	}

	private static ClassloaderMetaspaceEntry parseClstatsLine(String[] tokens, String rawLine) {
		return new ClassloaderMetaspaceEntry(join(tokens, 5), tokens[1], parseLong(tokens[3]), parseLong(tokens[4]),
				Boolean.valueOf(tokens[2]), rawLine);
	}

	private static boolean isBooleanToken(String token) {
		return "true".equalsIgnoreCase(token) || "false".equalsIgnoreCase(token);
	}

	private static boolean isNumber(String token) {
		return parseLong(token) != null;
	}

	private static Long parseLong(String token) {
		if (token == null || token.isBlank()) {
			return null;
		}
		String normalized = token.replace(",", "");
		try {
			return Long.valueOf(normalized);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static long valueOrZero(Long value) {
		return value == null ? 0L : value;
	}

	private static String join(String[] tokens, int start) {
		if (tokens.length <= start) {
			return "";
		}
		return String.join(" ", java.util.Arrays.copyOfRange(tokens, start, tokens.length));
	}

}
