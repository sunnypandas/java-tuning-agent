package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

public class JfrSummaryParser implements JfrSummaryParserAdapter {

	private static final int STACK_SAMPLE_LIMIT = 8;

	private final int topLimit;

	public JfrSummaryParser(int topLimit) {
		if (topLimit <= 0) {
			throw new IllegalArgumentException("topLimit must be positive");
		}
		this.topLimit = topLimit;
	}

	public JfrSummary parse(Path recordingFile, int maxSummaryEvents) {
		if (recordingFile == null || !Files.isRegularFile(recordingFile)) {
			throw new IllegalArgumentException("JFR recording file does not exist: " + recordingFile);
		}
		if (maxSummaryEvents <= 0) {
			throw new IllegalArgumentException("maxSummaryEvents must be positive");
		}
		ParserState state = new ParserState();
		try (RecordingFile file = new RecordingFile(recordingFile)) {
			while (file.hasMoreEvents()) {
				if (state.totalEvents >= maxSummaryEvents) {
					state.warnings.add("Stopped parsing after maxSummaryEvents=" + maxSummaryEvents);
					break;
				}
				RecordedEvent event = file.readEvent();
				state.observe(event);
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException("Unable to parse JFR recording " + recordingFile + ": " + ex.getMessage(),
					ex);
		}
		state.addMissingCategoryWarnings();
		return state.toSummary(topLimit);
	}

	private static final class ParserState {

		private long totalEvents;
		private Instant firstStart;
		private Instant lastEnd;
		private final Map<String, Long> eventCounts = new HashMap<>();
		private final List<String> warnings = new ArrayList<>();
		private long gcCount;
		private double totalGcPauseMs;
		private double maxGcPauseMs;
		private final Map<String, Long> gcCauses = new HashMap<>();
		private final List<JfrHeapSample> heapSamples = new ArrayList<>();
		private long allocationEventCount;
		private long totalAllocationBytesApprox;
		private final Map<String, CountAndBytesAccumulator> allocatedClasses = new HashMap<>();
		private final Map<String, StackAccumulator> allocationStacks = new HashMap<>();
		private long parkEventCount;
		private long monitorEnterEventCount;
		private double maxBlockedMs;
		private final Map<String, ThreadBlockAccumulator> blockedThreads = new HashMap<>();
		private long executionSampleCount;
		private final Map<String, StackAccumulator> executionSamples = new HashMap<>();

		private void observe(RecordedEvent event) {
			totalEvents++;
			String name = event.getEventType().getName();
			eventCounts.merge(name, 1L, Long::sum);
			Instant start = event.getStartTime();
			Instant end = event.getEndTime();
			if (firstStart == null || (start != null && start.isBefore(firstStart))) {
				firstStart = start;
			}
			if (lastEnd == null || (end != null && end.isAfter(lastEnd))) {
				lastEnd = end;
			}
			try {
				switch (name) {
					case "jdk.GarbageCollection" -> observeGc(event);
					case "jdk.GCHeapSummary" -> observeHeap(event);
					case "jdk.ObjectAllocationInNewTLAB", "jdk.ObjectAllocationOutsideTLAB" -> observeAllocation(event);
					case "jdk.ThreadPark" -> observeThreadBlock(event, true);
					case "jdk.JavaMonitorEnter" -> observeThreadBlock(event, false);
					case "jdk.ExecutionSample" -> observeExecutionSample(event);
					default -> {
					}
				}
			}
			catch (RuntimeException ex) {
				warnings.add("Unable to parse " + name + " event: " + ex.getMessage());
			}
		}

		private void observeGc(RecordedEvent event) {
			gcCount++;
			double pauseMs = durationMs(event.getDuration());
			totalGcPauseMs += pauseMs;
			maxGcPauseMs = Math.max(maxGcPauseMs, pauseMs);
			String cause = stringValue(event, "cause", "unknown");
			gcCauses.merge(cause, 1L, Long::sum);
		}

		private void observeHeap(RecordedEvent event) {
			Long heapUsed = longValue(event, "heapUsed");
			Long heapCommitted = longValue(event, "heapCommitted");
			heapSamples.add(new JfrHeapSample(epochMs(event.getStartTime()), null, null, heapUsed, heapCommitted));
		}

		private void observeAllocation(RecordedEvent event) {
			allocationEventCount++;
			long bytes = longValue(event, "allocationSize", 0L);
			totalAllocationBytesApprox += bytes;
			String className = className(event, "objectClass");
			allocatedClasses.computeIfAbsent(className, CountAndBytesAccumulator::new).add(bytes);
			List<String> stack = stackFrames(event.getStackTrace());
			String frame = stack.isEmpty() ? className : stack.get(0);
			allocationStacks.computeIfAbsent(frame, StackAccumulator::new).add(bytes, stack);
		}

		private void observeThreadBlock(RecordedEvent event, boolean park) {
			if (park) {
				parkEventCount++;
			}
			else {
				monitorEnterEventCount++;
			}
			double blockedMs = durationMs(event.getDuration());
			maxBlockedMs = Math.max(maxBlockedMs, blockedMs);
			RecordedThread thread = value(event, "eventThread", RecordedThread.class);
			String threadName = thread == null || thread.getJavaName() == null ? "unknown" : thread.getJavaName();
			blockedThreads.computeIfAbsent(threadName, ThreadBlockAccumulator::new)
				.add(blockedMs, stackFrames(event.getStackTrace()));
		}

		private void observeExecutionSample(RecordedEvent event) {
			executionSampleCount++;
			List<String> stack = stackFrames(event.getStackTrace());
			if (!stack.isEmpty()) {
				executionSamples.computeIfAbsent(stack.get(0), StackAccumulator::new).add(0L, stack);
			}
		}

		private void addMissingCategoryWarnings() {
			if (allocationEventCount == 0L) {
				warnings.add(
						"JFR allocation events were not present; use settings=profile or custom templates later for allocation detail.");
			}
			if (executionSampleCount == 0L) {
				warnings.add("JFR ExecutionSample events were not present.");
			}
			if (parkEventCount == 0L && monitorEnterEventCount == 0L) {
				warnings.add("JFR thread park/monitor events were not present.");
			}
			if (gcCount == 0L) {
				warnings.add("JFR GC events were not present.");
			}
		}

		private JfrSummary toSummary(int topLimit) {
			Long startMs = epochMs(firstStart);
			Long endMs = epochMs(lastEnd);
			Long durationMs = startMs == null || endMs == null ? null : Math.max(0L, endMs - startMs);
			return new JfrSummary(startMs, endMs, durationMs,
					new JfrGcSummary(gcCount, totalGcPauseMs, maxGcPauseMs, topCounts(gcCauses, topLimit),
							limit(heapSamples, topLimit)),
					new JfrAllocationSummary(totalAllocationBytesApprox, topCountAndBytes(allocatedClasses, topLimit),
							topStacks(allocationStacks, topLimit), allocationEventCount),
					new JfrThreadSummary(parkEventCount, monitorEnterEventCount, maxBlockedMs,
							topThreadBlocks(blockedThreads, topLimit)),
					new JfrExecutionSampleSummary(executionSampleCount, topStacks(executionSamples, topLimit)),
					eventCounts, warnings);
		}

	}

	private static List<JfrCount> topCounts(Map<String, Long> counts, int limit) {
		return counts.entrySet().stream()
			.sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
			.limit(limit)
			.map(e -> new JfrCount(e.getKey(), e.getValue()))
			.toList();
	}

	private static List<JfrCountAndBytes> topCountAndBytes(Map<String, CountAndBytesAccumulator> values, int limit) {
		return values.values().stream()
			.sorted(Comparator.comparingLong(CountAndBytesAccumulator::bytes)
				.reversed()
				.thenComparing(CountAndBytesAccumulator::name))
			.limit(limit)
			.map(v -> new JfrCountAndBytes(v.name(), v.count(), v.bytes()))
			.toList();
	}

	private static List<JfrStackAggregate> topStacks(Map<String, StackAccumulator> values, int limit) {
		return values.values().stream()
			.sorted(Comparator.comparingLong(StackAccumulator::count)
				.reversed()
				.thenComparing(Comparator.comparingLong(StackAccumulator::bytes).reversed())
				.thenComparing(StackAccumulator::frame))
			.limit(limit)
			.map(v -> new JfrStackAggregate(v.frame(), v.count(), v.bytes(), v.sampleStack()))
			.toList();
	}

	private static List<JfrThreadBlockAggregate> topThreadBlocks(Map<String, ThreadBlockAccumulator> values, int limit) {
		return values.values().stream()
			.sorted(Comparator.comparingDouble(ThreadBlockAccumulator::totalBlockedMs)
				.reversed()
				.thenComparing(ThreadBlockAccumulator::threadName))
			.limit(limit)
			.map(v -> new JfrThreadBlockAggregate(v.threadName(), v.count(), v.totalBlockedMs(), v.maxBlockedMs(),
					v.sampleStack()))
			.toList();
	}

	private static <T> List<T> limit(List<T> values, int limit) {
		return values.stream().limit(limit).toList();
	}

	private static double durationMs(Duration duration) {
		return duration == null ? 0.0d : duration.toNanos() / 1_000_000.0d;
	}

	private static Long epochMs(Instant instant) {
		return instant == null ? null : instant.toEpochMilli();
	}

	private static String className(RecordedEvent event, String fieldName) {
		RecordedClass recordedClass = value(event, fieldName, RecordedClass.class);
		if (recordedClass == null || recordedClass.getName() == null) {
			return "unknown";
		}
		return recordedClass.getName();
	}

	private static String stringValue(RecordedEvent event, String fieldName, String fallback) {
		Object value = fieldValue(event, fieldName);
		return value == null ? fallback : value.toString();
	}

	private static Long longValue(RecordedEvent event, String fieldName) {
		Object value = fieldValue(event, fieldName);
		if (value instanceof Number number) {
			return number.longValue();
		}
		return null;
	}

	private static long longValue(RecordedEvent event, String fieldName, long fallback) {
		Long value = longValue(event, fieldName);
		return value == null ? fallback : value;
	}

	private static <T> T value(RecordedEvent event, String fieldName, Class<T> type) {
		Object value = fieldValue(event, fieldName);
		return type.isInstance(value) ? type.cast(value) : null;
	}

	private static Object fieldValue(RecordedEvent event, String fieldName) {
		if (!event.hasField(fieldName)) {
			return null;
		}
		return event.getValue(fieldName);
	}

	private static List<String> stackFrames(RecordedStackTrace stackTrace) {
		if (stackTrace == null || stackTrace.getFrames() == null) {
			return List.of();
		}
		return stackTrace.getFrames().stream().limit(STACK_SAMPLE_LIMIT).map(JfrSummaryParser::frameName).toList();
	}

	private static String frameName(RecordedFrame frame) {
		RecordedMethod method = frame.getMethod();
		if (method == null || method.getType() == null) {
			return "unknown";
		}
		return method.getType().getName() + "." + method.getName();
	}

	private static final class CountAndBytesAccumulator {

		private final String name;
		private long count;
		private long bytes;

		private CountAndBytesAccumulator(String name) {
			this.name = name;
		}

		private void add(long bytes) {
			this.count++;
			this.bytes += bytes;
		}

		private String name() {
			return name;
		}

		private long count() {
			return count;
		}

		private long bytes() {
			return bytes;
		}

	}

	private static final class StackAccumulator {

		private final String frame;
		private long count;
		private long bytes;
		private List<String> sampleStack = List.of();

		private StackAccumulator(String frame) {
			this.frame = frame;
		}

		private void add(long bytes, List<String> stack) {
			this.count++;
			this.bytes += bytes;
			if (sampleStack.isEmpty() && stack != null && !stack.isEmpty()) {
				this.sampleStack = List.copyOf(stack);
			}
		}

		private String frame() {
			return frame;
		}

		private long count() {
			return count;
		}

		private long bytes() {
			return bytes;
		}

		private List<String> sampleStack() {
			return sampleStack;
		}

	}

	private static final class ThreadBlockAccumulator {

		private final String threadName;
		private long count;
		private double totalBlockedMs;
		private double maxBlockedMs;
		private List<String> sampleStack = List.of();

		private ThreadBlockAccumulator(String threadName) {
			this.threadName = threadName;
		}

		private void add(double blockedMs, List<String> stack) {
			this.count++;
			this.totalBlockedMs += blockedMs;
			this.maxBlockedMs = Math.max(this.maxBlockedMs, blockedMs);
			if (sampleStack.isEmpty() && stack != null && !stack.isEmpty()) {
				this.sampleStack = List.copyOf(stack);
			}
		}

		private String threadName() {
			return threadName;
		}

		private long count() {
			return count;
		}

		private double totalBlockedMs() {
			return totalBlockedMs;
		}

		private double maxBlockedMs() {
			return maxBlockedMs;
		}

		private List<String> sampleStack() {
			return sampleStack;
		}

	}

}
