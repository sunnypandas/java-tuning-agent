package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;
import java.util.stream.Collectors;

import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ThreadDumpSummary;

/**
 * Uses {@link ThreadDumpSummary} from {@code jcmd Thread.print} for deadlock and blocking signals.
 * Tuned for fewer false positives on large thread pools while retaining recall via a secondary tier.
 */
public final class ThreadDumpInsightsRule implements DiagnosisRule {

	public static final String DEADLOCK_FINDING_TITLE = "Java-level deadlock detected";

	public static final String BLOCKED_SEVERE_TITLE = "Many BLOCKED threads (thread dump)";

	public static final String BLOCKED_MODERATE_TITLE = "Elevated BLOCKED threads (thread dump)";

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		ThreadDumpSummary td = evidence.threadDump();
		if (td == null || td.threadCount() <= 0) {
			return;
		}
		if (isLikelyDeadlockSection(td.deadlockHints())) {
			String preview = td.deadlockHints().stream().limit(5).collect(Collectors.joining(" | "));
			scratch.addFinding(new TuningFinding(DEADLOCK_FINDING_TITLE, "critical",
					"threadDump deadlockSectionPreview=" + truncate(preview, 500), "rule-based",
					"Deadlocks halt progress; participating threads may hold monitors and indirect heap retention"));
			scratch.addRecommendation(new TuningRecommendation(
					"Break the lock cycle shown in the thread dump (ordering, timeouts, or structure)",
					"code-review", "Refactor nested locks; consider java.util.concurrent locks with tryLock",
					"Restores liveness", "Requires code or library change", "Use dump stacks to name exact locks"));
			scratch.addNextStep("Open the deadlock section of the dump and map each thread to the monitor or lock it waits on");
		}
		BlockedTier blockedTier = classifyBlocked(td);
		if (blockedTier == BlockedTier.SEVERE) {
			long blocked = td.threadsByState().getOrDefault("BLOCKED", 0L);
			scratch.addFinding(new TuningFinding(BLOCKED_SEVERE_TITLE, "high",
					evidenceLine(td, blocked), "inferred-from-evidence",
					"Strong BLOCKED signal: high count or high share of the parsed dump — likely lock contention or monitor stalls"));
			scratch.addRecommendation(new TuningRecommendation("Reduce critical sections and review pool/queue backpressure",
					"code-review", "Profile lock hotspots; align thread pool sizes with downstream capacity",
					"Less scheduling stalls", "May need load test to validate", "Pair with histogram if heap is also high"));
		}
		else if (blockedTier == BlockedTier.MODERATE) {
			long blocked = td.threadsByState().getOrDefault("BLOCKED", 0L);
			scratch.addFinding(new TuningFinding(BLOCKED_MODERATE_TITLE, "medium", evidenceLine(td, blocked),
					"inferred-from-evidence",
					"Moderate BLOCKED footprint worth validating under load (may be transient or pool sizing)"));
			scratch.addRecommendation(new TuningRecommendation(
					"Validate whether BLOCKED threads persist; sample another dump under steady load", "code-review",
					"If persistent, profile lock order and external waits", "Avoid chasing one-off stalls",
					"Possible benign spikes", "Compare two dumps minutes apart"));
		}
	}

	private static String evidenceLine(ThreadDumpSummary td, long blocked) {
		return "threadDump threadCount=" + td.threadCount() + " BLOCKED=" + blocked + " stateHistogram=" + td.threadsByState();
	}

	/**
	 * Reduces false positives from unrelated lines containing the word "deadlock" while keeping standard HotSpot output.
	 */
	static boolean isLikelyDeadlockSection(List<String> hints) {
		if (hints == null || hints.isEmpty()) {
			return false;
		}
		long nonBlank = hints.stream().filter(s -> s != null && !s.isBlank()).count();
		if (nonBlank < 2) {
			return false;
		}
		return hints.stream().anyMatch(ThreadDumpInsightsRule::lineLooksLikeJvmDeadlockHeader);
	}

	private static boolean lineLooksLikeJvmDeadlockHeader(String line) {
		if (line == null) {
			return false;
		}
		String lower = line.toLowerCase();
		// HotSpot wording; avoid generic "deadlock" substring in unrelated stack text.
		return lower.contains("found one java-level deadlock");
	}

	private enum BlockedTier {
		NONE, MODERATE, SEVERE
	}

	/**
	 * SEVERE: strong absolute or high share (small pools) or high share with enough threads.
	 * MODERATE: recall path for large pools (absolute count + minimum share) without firing on sparse noise.
	 */
	private static BlockedTier classifyBlocked(ThreadDumpSummary td) {
		long blocked = td.threadsByState().getOrDefault("BLOCKED", 0L);
		int tc = td.threadCount();
		if (blocked <= 0L || tc <= 0) {
			return BlockedTier.NONE;
		}
		double pct = 100.0d * (double) blocked / (double) tc;

		boolean severe = blocked >= 11L
				|| (blocked >= 6L && tc >= 20 && pct >= 11.5d)
				|| (blocked >= 5L && tc <= 52 && pct >= 17.0d);
		if (severe) {
			return BlockedTier.SEVERE;
		}
		boolean moderate = (blocked >= 5L && tc >= 38 && pct >= 7.5d) || (blocked >= 7L && tc >= 48)
				|| (blocked >= 6L && tc >= 55 && pct >= 6.5d);
		if (moderate) {
			return BlockedTier.MODERATE;
		}
		return BlockedTier.NONE;
	}

	private static String truncate(String s, int max) {
		if (s == null || s.length() <= max) {
			return s == null ? "" : s;
		}
		return s.substring(0, max) + "...";
	}
}
