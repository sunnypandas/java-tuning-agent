package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedRuntimeSample;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResult;

public final class RepeatedSamplingTrendRule implements DiagnosisRule {

	public static final String RISING_HEAP_TITLE = "Repeated samples show rising heap pressure";

	public static final String ELEVATED_GC_TITLE = "Repeated samples show elevated GC activity";

	public static final String GROWING_FOOTPRINT_TITLE = "Repeated samples show growing runtime footprint";

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		RepeatedSamplingResult repeated = evidence.repeatedSamplingResult();
		if (repeated == null || repeated.samples().size() < 2) {
			return;
		}
		List<RepeatedRuntimeSample> samples = repeated.samples();
		RepeatedRuntimeSample first = samples.get(0);
		RepeatedRuntimeSample last = samples.get(samples.size() - 1);
		boolean strongSignal = false;

		if (isRisingHeapPressure(samples)) {
			long heapDeltaMb = toMb(last.memory().heapUsedBytes() - first.memory().heapUsedBytes());
			Double oldDelta = oldPercentDelta(first.gc(), last.gc());
			scratch.addFinding(new TuningFinding(RISING_HEAP_TITLE, "medium",
					"samples=" + samples.size() + ", heapDeltaMb=" + heapDeltaMb + ", oldGenDeltaPct="
							+ formatDouble(oldDelta),
					"trend-based",
					"Repeated safe-readonly samples suggest heap occupancy is rising instead of returning to baseline"));
			scratch.addRecommendation(new TuningRecommendation("Extend live sampling or capture retention evidence", "memory",
					"Repeat sampling across a longer steady-load window; consider class histogram or heap dump with confirmation",
					"Separates transient spikes from sustained retention growth",
					"Longer windows add collection time", "Prefer representative traffic when sampling"));
			strongSignal = true;
		}

		if (isElevatedGcActivity(samples)) {
			long ygcDelta = last.gc().youngGcCount() - first.gc().youngGcCount();
			long ygctDeltaMs = last.gc().youngGcTimeMs() - first.gc().youngGcTimeMs();
			long fgcDelta = last.gc().fullGcCount() - first.gc().fullGcCount();
			scratch.addFinding(new TuningFinding(ELEVATED_GC_TITLE, "medium",
					"samples=" + samples.size() + ", ygcDelta=" + ygcDelta + ", ygctDeltaMs=" + ygctDeltaMs + ", fgcDelta="
							+ fgcDelta,
					"trend-based", "Repeated samples suggest GC work is increasing over the sampling window"));
			strongSignal = true;
		}

		if (isGrowingFootprint(samples)) {
			long threadDelta = delta(first.threadCount(), last.threadCount());
			long classDelta = delta(first.loadedClassCount(), last.loadedClassCount());
			scratch.addFinding(new TuningFinding(GROWING_FOOTPRINT_TITLE, "low",
					"samples=" + samples.size() + ", threadDelta=" + threadDelta + ", classDelta=" + classDelta,
					"trend-based", "Repeated samples suggest runtime footprint is expanding over time"));
			strongSignal = true;
		}

		if (!strongSignal) {
			scratch.addNextStep(
					"No strong repeated-sampling trend detected; extend sampling duration or add histogram/JFR evidence under representative load");
		}
	}

	private static boolean isRisingHeapPressure(List<RepeatedRuntimeSample> samples) {
		RepeatedRuntimeSample first = samples.get(0);
		RepeatedRuntimeSample last = samples.get(samples.size() - 1);
		long heapDelta = last.memory().heapUsedBytes() - first.memory().heapUsedBytes();
		long heapMax = last.memory().heapMaxBytes() > 0L ? last.memory().heapMaxBytes() : first.memory().heapMaxBytes();
		boolean monotonicHeap = samples.stream()
			.map(RepeatedRuntimeSample::memory)
			.mapToLong(JvmMemorySnapshot::heapUsedBytes)
			.reduce((previous, current) -> current >= previous ? current : Long.MIN_VALUE)
			.orElse(Long.MIN_VALUE) != Long.MIN_VALUE;
		Double oldDelta = oldPercentDelta(first.gc(), last.gc());
		boolean oldRising = samples.size() >= 3 && oldDelta != null && oldDelta >= 15.0d;
		boolean heapRising = monotonicHeap && (heapDelta >= 128L * 1024L * 1024L
				|| (heapMax > 0L && heapDelta >= Math.round(heapMax * 0.20d)));
		return heapRising || oldRising;
	}

	private static boolean isElevatedGcActivity(List<RepeatedRuntimeSample> samples) {
		RepeatedRuntimeSample first = samples.get(0);
		RepeatedRuntimeSample last = samples.get(samples.size() - 1);
		long elapsedMs = Math.max(1L, last.sampledAtEpochMs() - first.sampledAtEpochMs());
		double minutes = elapsedMs / 60_000.0d;
		long ygcDelta = last.gc().youngGcCount() - first.gc().youngGcCount();
		long ygctDeltaMs = last.gc().youngGcTimeMs() - first.gc().youngGcTimeMs();
		long fgcDelta = last.gc().fullGcCount() - first.gc().fullGcCount();
		double ygcPerMinute = minutes <= 0.0d ? 0.0d : ygcDelta / minutes;
		double ygctPerMinute = minutes <= 0.0d ? 0.0d : ygctDeltaMs / minutes;
		return fgcDelta >= 1L || ygcPerMinute >= 30.0d || ygctPerMinute >= 1_000.0d;
	}

	private static boolean isGrowingFootprint(List<RepeatedRuntimeSample> samples) {
		RepeatedRuntimeSample first = samples.get(0);
		RepeatedRuntimeSample last = samples.get(samples.size() - 1);
		return delta(first.threadCount(), last.threadCount()) >= 20L
				|| delta(first.loadedClassCount(), last.loadedClassCount()) >= 500L;
	}

	private static Double oldPercentDelta(JvmGcSnapshot first, JvmGcSnapshot last) {
		if (first.oldUsagePercent() == null || last.oldUsagePercent() == null) {
			return null;
		}
		return last.oldUsagePercent() - first.oldUsagePercent();
	}

	private static long delta(Long first, Long last) {
		if (first == null || last == null) {
			return 0L;
		}
		return last - first;
	}

	private static long toMb(long bytes) {
		return bytes / (1024L * 1024L);
	}

	private static String formatDouble(Double value) {
		if (value == null) {
			return "n/a";
		}
		return String.format("%.2f", value);
	}

}
