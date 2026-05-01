package com.alibaba.cloud.ai.examples.javatuning.source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.cloud.ai.examples.javatuning.advice.SuspectedCodeHotspot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrStackAggregate;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrThreadBlockAggregate;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RetentionChainSegment;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RetentionChainSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SuspectedHolderSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ThreadDumpSummary;

public final class SourceHotspotCorrelationService {

	private static final int MAX_HOTSPOTS = 8;

	private static final Pattern FQCN_LIKE = Pattern
		.compile("([a-z_][\\w$]*(?:\\.[a-z_][\\w$]*)+\\.[A-Z][\\w$]*(?:\\$[A-Z][\\w$]*)?)");

	private static final Pattern STACK_FRAME = Pattern
		.compile("([a-z_][\\w$]*(?:\\.[a-z_][\\w$]*)+\\.[A-Z][\\w$]*(?:\\$[\\w$]+)?)\\.[a-zA-Z_$][\\w$]*\\(");

	private final LocalSourceHotspotFinder sourceHotspotFinder;

	public SourceHotspotCorrelationService(LocalSourceHotspotFinder sourceHotspotFinder) {
		this.sourceHotspotFinder = sourceHotspotFinder;
	}

	public List<SuspectedCodeHotspot> correlate(List<Path> sourceRoots, MemoryGcEvidencePack evidence,
			List<String> candidatePackages) {
		if (evidence == null) {
			return List.of();
		}
		Map<String, SuspectedCodeHotspot> byClass = new LinkedHashMap<>();
		addThreadDumpCandidates(byClass, sourceRoots, evidence.threadDump(), candidatePackages);
		addRetentionCandidates(byClass, sourceRoots, evidence.heapRetentionAnalysis(), candidatePackages);
		addJfrCandidates(byClass, sourceRoots, evidence.jfrSummary(), candidatePackages);
		addHistogramCandidates(byClass, sourceRoots, evidence, candidatePackages);
		return byClass.values().stream().limit(MAX_HOTSPOTS).toList();
	}

	private void addThreadDumpCandidates(Map<String, SuspectedCodeHotspot> byClass, List<Path> sourceRoots,
			ThreadDumpSummary threadDump, List<String> candidatePackages) {
		if (threadDump == null) {
			return;
		}
		for (String hint : threadDump.deadlockHints()) {
			for (String fqcn : classesFromStackText(hint, candidatePackages)) {
				add(byClass, hotspot(sourceRoots, fqcn, "Thread dump deadlock stack points at " + simpleName(fqcn),
						"Thread.print deadlock", "high"));
			}
		}
	}

	private void addRetentionCandidates(Map<String, SuspectedCodeHotspot> byClass, List<Path> sourceRoots,
			HeapRetentionAnalysisResult retention, List<String> candidatePackages) {
		if (retention == null || !retention.analysisSucceeded()) {
			return;
		}
		HeapRetentionSummary summary = retention.retentionSummary();
		for (SuspectedHolderSummary holder : summary.suspectedHolders()) {
			addRetentionClass(byClass, sourceRoots, retention, holder.holderType(), holder.exampleFieldPath(),
					holder.retainedBytesApprox(), candidatePackages);
		}
		for (RetentionChainSummary chain : summary.retentionChains()) {
			for (RetentionChainSegment segment : chain.segments()) {
				addRetentionClass(byClass, sourceRoots, retention, segment.ownerType(),
						segment.ownerType() + "." + segment.referenceName(), chain.retainedBytesApprox(),
						candidatePackages);
				addRetentionClass(byClass, sourceRoots, retention, segment.targetType(),
						segment.ownerType() + "." + segment.referenceName(), chain.retainedBytesApprox(),
						candidatePackages);
			}
		}
	}

	private void addRetentionClass(Map<String, SuspectedCodeHotspot> byClass, List<Path> sourceRoots,
			HeapRetentionAnalysisResult retention, String rawType, String path, Long retainedBytesApprox,
			List<String> candidatePackages) {
		for (String fqcn : classNamesFromText(rawType, candidatePackages)) {
			String reason = "Heap retention path names holder " + simpleName(fqcn);
			if (path != null && !path.isBlank()) {
				reason += " via " + path;
			}
			if (retainedBytesApprox != null && retainedBytesApprox > 0) {
				reason += " (~" + retainedBytesApprox + " retained bytes)";
			}
			add(byClass, hotspot(sourceRoots, fqcn, reason, "heap-retention:" + retention.engine(), "medium-high"));
		}
	}

	private void addJfrCandidates(Map<String, SuspectedCodeHotspot> byClass, List<Path> sourceRoots, JfrSummary jfr,
			List<String> candidatePackages) {
		if (jfr == null) {
			return;
		}
		if (jfr.threadSummary() != null) {
			for (JfrThreadBlockAggregate blocked : jfr.threadSummary().topBlockedThreads()) {
				addJfrStack(byClass, sourceRoots, "JFR monitor-blocked stack", blocked.sampleStack(), candidatePackages);
			}
		}
		if (jfr.allocationSummary() != null) {
			for (JfrStackAggregate stack : jfr.allocationSummary().topAllocationStacks()) {
				addJfrStack(byClass, sourceRoots, "JFR allocation stack", stack, candidatePackages);
			}
			jfr.allocationSummary()
				.topAllocatedClasses()
				.forEach(row -> addJfrClass(byClass, sourceRoots, row.name(), "JFR allocation class",
						candidatePackages));
		}
		if (jfr.executionSampleSummary() != null) {
			for (JfrStackAggregate stack : jfr.executionSampleSummary().topMethods()) {
				addJfrStack(byClass, sourceRoots, "JFR execution sample", stack, candidatePackages);
			}
		}
	}

	private void addJfrStack(Map<String, SuspectedCodeHotspot> byClass, List<Path> sourceRoots, String source,
			JfrStackAggregate stack, List<String> candidatePackages) {
		List<String> texts = new ArrayList<>();
		texts.add(stack.frame());
		texts.addAll(stack.sampleStack());
		addJfrStack(byClass, sourceRoots, source, texts, candidatePackages);
	}

	private void addJfrStack(Map<String, SuspectedCodeHotspot> byClass, List<Path> sourceRoots, String source,
			List<String> stackFrames, List<String> candidatePackages) {
		for (String frame : stackFrames) {
			for (String fqcn : classesFromStackText(frame, candidatePackages)) {
				add(byClass, hotspot(sourceRoots, fqcn, source + " points at " + simpleName(fqcn), "JFR:" + source,
						"medium-high"));
			}
		}
	}

	private void addJfrClass(Map<String, SuspectedCodeHotspot> byClass, List<Path> sourceRoots, String rawClass,
			String source, List<String> candidatePackages) {
		for (String fqcn : classNamesFromText(rawClass, candidatePackages)) {
			add(byClass, hotspot(sourceRoots, fqcn, source + " names " + simpleName(fqcn), "JFR:" + source,
					"medium"));
		}
	}

	private void addHistogramCandidates(Map<String, SuspectedCodeHotspot> byClass, List<Path> sourceRoots,
			MemoryGcEvidencePack evidence, List<String> candidatePackages) {
		for (SuspectedCodeHotspot hotspot : sourceHotspotFinder.hotspotsFromHistogram(sourceRoots,
				evidence.classHistogram(), candidatePackages)) {
			add(byClass, hotspot);
		}
	}

	private SuspectedCodeHotspot hotspot(List<Path> sourceRoots, String fqcn, String reason, String evidenceLink,
			String confidence) {
		List<Path> paths = sourceHotspotFinder.findClassSources(sourceRoots, fqcn);
		String fileHint = paths.isEmpty() ? "" : paths.get(0).toString();
		String adjustedConfidence = paths.isEmpty() && "high".equals(confidence) ? "medium-high" : confidence;
		return new SuspectedCodeHotspot(fqcn, fileHint, reason, evidenceLink, adjustedConfidence);
	}

	private static void add(Map<String, SuspectedCodeHotspot> byClass, SuspectedCodeHotspot hotspot) {
		if (hotspot.className().isBlank()) {
			return;
		}
		byClass.putIfAbsent(hotspot.className(), hotspot);
	}

	private static List<String> classesFromStackText(String text, List<String> candidatePackages) {
		List<String> matches = new ArrayList<>();
		Matcher matcher = STACK_FRAME.matcher(text == null ? "" : text);
		while (matcher.find()) {
			String fqcn = matcher.group(1);
			if (isCandidate(fqcn, candidatePackages)) {
				matches.add(fqcn);
			}
		}
		if (!matches.isEmpty()) {
			return matches;
		}
		return classNamesFromText(text, candidatePackages);
	}

	private static List<String> classNamesFromText(String text, List<String> candidatePackages) {
		if (text == null || text.isBlank() || text.contains("[]") || text.startsWith("[")) {
			return List.of();
		}
		List<String> matches = new ArrayList<>();
		Matcher matcher = FQCN_LIKE.matcher(text);
		while (matcher.find()) {
			String fqcn = stripInnerClass(matcher.group(1));
			if (isCandidate(fqcn, candidatePackages) && !matches.contains(fqcn)) {
				matches.add(fqcn);
			}
		}
		return matches;
	}

	private static boolean isCandidate(String fqcn, List<String> candidatePackages) {
		if (fqcn == null || fqcn.isBlank() || isJvmInternal(fqcn)) {
			return false;
		}
		if (candidatePackages == null || candidatePackages.isEmpty()) {
			return true;
		}
		for (String pkg : candidatePackages) {
			if (pkg != null && !pkg.isBlank() && (fqcn.equals(pkg) || fqcn.startsWith(pkg + "."))) {
				return true;
			}
		}
		return false;
	}

	private static String stripInnerClass(String fqcn) {
		int inner = fqcn.indexOf('$');
		return inner < 0 ? fqcn : fqcn.substring(0, inner);
	}

	private static boolean isJvmInternal(String className) {
		String lower = className.toLowerCase(Locale.ROOT);
		return lower.startsWith("java.") || lower.startsWith("javax.") || lower.startsWith("jdk.")
				|| lower.startsWith("sun.") || lower.startsWith("com.sun.");
	}

	private static String simpleName(String fqcn) {
		int lastDot = fqcn.lastIndexOf('.');
		return lastDot < 0 ? fqcn : fqcn.substring(lastDot + 1);
	}

}
