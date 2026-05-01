package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GcHeapInfoParser {

	/**
	 * JDK 21+ G1: {@code garbage-first heap  total reserved NK, committed NK, used NK} (see
	 * {@code G1CollectedHeap::print_heap_on} in OpenJDK).
	 */
	private static final Pattern HEAP_PATTERN_G1_MODERN = Pattern.compile(
			"(?i)garbage-first heap\\s+total reserved\\s+(\\d+)K,\\s*committed\\s+(\\d+)K,\\s*used\\s+(\\d+)K");

	/** Older G1 line: {@code garbage-first heap total NK, used NK} */
	private static final Pattern HEAP_PATTERN_G1_LEGACY = Pattern
		.compile("(?i)garbage-first heap total\\s+(\\d+)K,\\s*used\\s+(\\d+)K");

	/** Compact exported line: {@code G1 heap committed NK used NK}. */
	private static final Pattern HEAP_PATTERN_G1_COMPACT = Pattern
		.compile("(?i)G1\\s+heap\\s+committed\\s+(\\d+)K\\s+used\\s+(\\d+)K");

	private static final Pattern METASPACE_PATTERN = Pattern
		.compile("(?i)Metaspace\\s+used\\s+(\\d+)K,\\s*committed\\s+(\\d+)K,\\s*reserved\\s+(\\d+)K");

	private static final Pattern OLD_GEN_FULL = Pattern
		.compile("(?i)used\\s+(\\d+)K,\\s*capacity\\s+(\\d+)K,\\s*committed\\s+(\\d+)K");

	public JvmMemorySnapshot parse(String output) {
		if (output == null || output.isBlank()) {
			return new JvmMemorySnapshot(0L, 0L, 0L, null, null, null, null, null);
		}

		long heapUsedBytes = 0L;
		long heapCommittedBytes = 0L;
		long heapMaxBytes = 0L;
		Long oldGenUsedBytes = null;
		Long oldGenCommittedBytes = null;
		Long metaspaceUsedBytes = null;
		Long xmsBytes = null;
		Long xmxBytes = null;
		boolean inOldGeneration = false;

		for (String rawLine : output.split("\\R")) {
			String line = rawLine.trim();
			if (line.isEmpty()) {
				continue;
			}
			Matcher heapModern = HEAP_PATTERN_G1_MODERN.matcher(line);
			if (heapModern.find()) {
				heapCommittedBytes = toBytes(heapModern.group(2));
				heapUsedBytes = toBytes(heapModern.group(3));
				continue;
			}
			Matcher heapLegacy = HEAP_PATTERN_G1_LEGACY.matcher(line);
			if (heapLegacy.find()) {
				heapCommittedBytes = toBytes(heapLegacy.group(1));
				heapUsedBytes = toBytes(heapLegacy.group(2));
				continue;
			}
			Matcher heapCompact = HEAP_PATTERN_G1_COMPACT.matcher(line);
			if (heapCompact.find()) {
				heapCommittedBytes = toBytes(heapCompact.group(1));
				heapUsedBytes = toBytes(heapCompact.group(2));
				continue;
			}
			if (line.toLowerCase().contains("g1 old generation")) {
				inOldGeneration = true;
				continue;
			}
			if (inOldGeneration) {
				Matcher fullOld = OLD_GEN_FULL.matcher(line);
				if (fullOld.find()) {
					oldGenUsedBytes = toBytes(fullOld.group(1));
					oldGenCommittedBytes = toBytes(fullOld.group(3));
					continue;
				}
				if (oldGenUsedBytes == null) {
					Matcher oldGenMatcher = Pattern.compile("(?i)used\\s+(\\d+)K").matcher(line);
					if (oldGenMatcher.find()) {
						oldGenUsedBytes = toBytes(oldGenMatcher.group(1));
						continue;
					}
				}
			}
			Matcher metaspaceMatcher = METASPACE_PATTERN.matcher(line);
			if (metaspaceMatcher.find()) {
				metaspaceUsedBytes = toBytes(metaspaceMatcher.group(1));
			}
		}

		return new JvmMemorySnapshot(heapUsedBytes, heapCommittedBytes, heapMaxBytes, oldGenUsedBytes,
				oldGenCommittedBytes, metaspaceUsedBytes, xmsBytes, xmxBytes);
	}

	private long toBytes(String value) {
		try {
			return Long.parseLong(value) * 1024L;
		}
		catch (NumberFormatException ex) {
			return 0L;
		}
	}

}
