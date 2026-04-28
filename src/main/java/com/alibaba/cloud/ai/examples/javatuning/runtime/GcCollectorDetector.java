package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.Locale;

public final class GcCollectorDetector {

	public String infer(String vmFlagsJoined, String heapInfoText) {
		String haystack = ((vmFlagsJoined == null ? "" : vmFlagsJoined) + "\n"
				+ (heapInfoText == null ? "" : heapInfoText)).toLowerCase(Locale.ROOT);
		if (haystack.contains("usezgc") || haystack.contains(" z heap") || haystack.contains("zheap")) {
			return "ZGC";
		}
		if (haystack.contains("useserialgc") || haystack.contains("serial heap")
				|| ((haystack.contains("def new generation") || haystack.contains("defnew"))
						&& haystack.contains("tenured generation"))) {
			return "Serial";
		}
		if (haystack.contains("useparallelgc") || haystack.contains("useparalleloldgc")
				|| haystack.contains("parallel heap") || haystack.contains("psyounggen")
				|| haystack.contains("paroldgen") || haystack.contains("parallel scavenge")) {
			return "Parallel";
		}
		if (haystack.contains("useconcmarksweepgc") || haystack.contains("concurrent mark-sweep")
				|| haystack.contains("concurrentmarksweep")) {
			return "CMS";
		}
		if (haystack.contains("useg1gc") || haystack.contains("garbage-first heap")) {
			return "G1";
		}
		return "unknown";
	}

}
