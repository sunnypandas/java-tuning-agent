package com.alibaba.cloud.ai.examples.javatuning.runtime;

/**
 * Normalizes {@code jcmd GC.class_histogram} class name tokens for matching and source lookup.
 */
public final class HistogramClassNames {

	private HistogramClassNames() {
	}

	/**
	 * Strips trailing {@code (module@version)} suffix produced by modern JDK histogram output.
	 */
	public static String stripModuleSuffix(String raw) {
		if (raw == null) {
			return "";
		}
		String s = raw.trim();
		int idx = s.lastIndexOf(" (");
		if (idx > 0) {
			return s.substring(0, idx).trim();
		}
		return s;
	}

	/**
	 * Primitive array field descriptors as emitted for {@code byte[]}, {@code int[]}, etc.
	 */
	public static boolean isPrimitiveArrayDescriptor(String normalized) {
		if (normalized == null || normalized.length() != 2 || normalized.charAt(0) != '[') {
			return false;
		}
		return switch (normalized.charAt(1)) {
			case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z' -> true;
			default -> false;
		};
	}

	public static boolean isAnyArrayDescriptor(String normalized) {
		return normalized != null && normalized.startsWith("[");
	}
}
