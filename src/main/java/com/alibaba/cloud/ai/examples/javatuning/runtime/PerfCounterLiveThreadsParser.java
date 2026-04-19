package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PerfCounterLiveThreadsParser {

	/**
	 * Matches {@code java.threads.live} from {@code jcmd PerfCounter.print}. Older HotSpot used
	 * space-separated columns; newer JDKs (e.g. 25+) emit {@code java.threads.live=24}.
	 */
	private static final Pattern LIVE_THREADS = Pattern
			.compile("(?m)^java\\.threads\\.live(?:\\s+|\\s*=\\s*)(\\d+)\\s*$");

	public Long parse(String output) {
		if (output == null || output.isBlank()) {
			return null;
		}
		Matcher matcher = LIVE_THREADS.matcher(output);
		if (matcher.find()) {
			return Long.parseLong(matcher.group(1));
		}
		return null;
	}
}
