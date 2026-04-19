package com.alibaba.cloud.ai.examples.javatuning.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.alibaba.cloud.ai.examples.javatuning.advice.SuspectedCodeHotspot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramEntry;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HistogramClassNames;

public final class LocalSourceHotspotFinder {

	private static final int MAX_CLASSES = 8;

	/**
	 * Packages that dominate many Spring Boot heaps but rarely pinpoint app leaks when histogram-ranked.
	 */
	private static final List<String> FRAMEWORK_PREFIXES = List.of("org.springframework.", "ch.qos.logback.",
			"org.apache.logging.", "org.apache.catalina.", "org.apache.tomcat.", "io.netty.");

	public List<Path> findClassSources(List<Path> sourceRoots, String fqcn) {
		if (sourceRoots == null || sourceRoots.isEmpty() || fqcn == null || fqcn.isBlank()) {
			return List.of();
		}
		String suffix = fqcn.replace('.', '/') + ".java";
		List<Path> hits = new ArrayList<>();
		for (Path root : sourceRoots) {
			if (root == null || !Files.isDirectory(root)) {
				continue;
			}
			try (Stream<Path> stream = Files.walk(root)) {
				stream.filter(Files::isRegularFile)
					.filter(path -> path.toString().replace('\\', '/').endsWith(suffix))
					.forEach(hits::add);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}
		return List.copyOf(hits);
	}

	public List<SuspectedCodeHotspot> hotspotsFromHistogram(List<Path> sourceRoots, ClassHistogramSummary histogram) {
		return hotspotsFromHistogram(sourceRoots, histogram, List.of());
	}

	/**
	 * Prefer types under {@code candidatePackages} (e.g. app base packages), then fill remaining slots with
	 * non-JDK, non-framework classes. Skips array descriptors — they never map to a single {@code .java} file.
	 */
	public List<SuspectedCodeHotspot> hotspotsFromHistogram(List<Path> sourceRoots, ClassHistogramSummary histogram,
			List<String> candidatePackages) {
		if (histogram == null || histogram.entries().isEmpty()) {
			return List.of();
		}
		List<ClassHistogramEntry> ranked = new ArrayList<>(histogram.entries());
		ranked.sort(Comparator.comparingLong(ClassHistogramEntry::bytes).reversed());
		List<SuspectedCodeHotspot> out = new ArrayList<>();
		java.util.HashSet<String> seen = new java.util.HashSet<>();
		List<String> packages = candidatePackages == null ? List.of() : candidatePackages;

		if (!packages.isEmpty()) {
			for (ClassHistogramEntry e : ranked) {
				if (out.size() >= MAX_CLASSES) {
					break;
				}
				String normalized = HistogramClassNames.stripModuleSuffix(e.className());
				if (HistogramClassNames.isAnyArrayDescriptor(normalized)) {
					continue;
				}
				if (isJvmInternal(normalized) || isFrameworkNoise(normalized)) {
					continue;
				}
				if (!underCandidatePackage(normalized, packages)) {
					continue;
				}
				if (!seen.add(normalized)) {
					continue;
				}
				out.add(buildHotspot(sourceRoots, e, normalized));
			}
		}

		for (ClassHistogramEntry e : ranked) {
			if (out.size() >= MAX_CLASSES) {
				break;
			}
			String normalized = HistogramClassNames.stripModuleSuffix(e.className());
			if (HistogramClassNames.isAnyArrayDescriptor(normalized)) {
				continue;
			}
			if (isJvmInternal(normalized) || isFrameworkNoise(normalized)) {
				continue;
			}
			if (!seen.add(normalized)) {
				continue;
			}
			out.add(buildHotspot(sourceRoots, e, normalized));
		}

		return List.copyOf(out);
	}

	private SuspectedCodeHotspot buildHotspot(List<Path> sourceRoots, ClassHistogramEntry e, String normalizedFqcn) {
		List<Path> paths = findClassSources(sourceRoots, normalizedFqcn);
		String fileHint = paths.isEmpty() ? "" : paths.get(0).toString();
		String confidence = paths.isEmpty() ? "low" : "medium";
		return new SuspectedCodeHotspot(normalizedFqcn, fileHint,
				"Large retained footprint in class histogram (" + e.bytes() + " bytes, " + e.instanceCount()
						+ " instances)",
				"GC.class_histogram", confidence);
	}

	private static boolean underCandidatePackage(String normalizedFqcn, List<String> candidatePackages) {
		for (String pkg : candidatePackages) {
			if (pkg == null || pkg.isBlank()) {
				continue;
			}
			if (normalizedFqcn.equals(pkg) || normalizedFqcn.startsWith(pkg + ".")) {
				return true;
			}
		}
		return false;
	}

	private static boolean isFrameworkNoise(String className) {
		for (String prefix : FRAMEWORK_PREFIXES) {
			if (className.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isJvmInternal(String className) {
		return className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.")
				|| className.startsWith("sun.") || className.startsWith("com.sun.");
	}
}
