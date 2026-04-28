package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.ArrayList;
import java.util.List;

public final class JvmCapabilitiesPolicy {

	private static final List<String> SUPPORTED_COLLECTORS = List.of("G1", "Parallel", "CMS", "ZGC", "Serial");

	public NativeMemoryCapability nativeMemoryCapability(String collector, String jvmVersion) {
		String normalizedCollector = collector == null || collector.isBlank() ? "unknown" : collector;
		JdkRelease release = JdkRelease.fromJvmVersion(jvmVersion);
		List<String> warnings = new ArrayList<>();
		List<String> missingData = new ArrayList<>();

		if (!SUPPORTED_COLLECTORS.contains(normalizedCollector)) {
			warnings.add("Native memory evidence will be probed independently of unsupported/unknown GC collector "
					+ normalizedCollector);
		}
		if ("CMS".equals(normalizedCollector) && release.major() >= 14) {
			warnings.add("GC collector/JDK signals look inconsistent: CMS is unsupported on JDK " + release.major());
		}
		if (release == JdkRelease.LEGACY || release == JdkRelease.UNKNOWN) {
			warnings.add("Native memory evidence may be partial: unknown/legacy JDK capabilities");
		}
		return new NativeMemoryCapability(true, List.copyOf(warnings), List.copyOf(missingData));
	}

	public record NativeMemoryCapability(boolean nmtSupported, List<String> warnings, List<String> missingData) {
	}

	public static String nativeMemoryDegradeWarning(String reason) {
		return "Native memory evidence degrade: NMT unavailable - " + reason;
	}

}
