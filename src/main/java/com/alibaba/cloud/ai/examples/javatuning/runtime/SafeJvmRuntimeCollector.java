package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapDumpSummarizer;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapDumpShallowSummary;

public class SafeJvmRuntimeCollector implements JvmRuntimeCollector {

	private static final String JCMD = "jcmd";

	private static final String JSTAT = "jstat";

	private static final Pattern FLAG_XMS_PATTERN = Pattern
		.compile("(?i)(?<!\\S)(?:-Xms|(?:-XX:InitialHeapSize=))(\\d+)([kmg]?)(?!\\S)");

	private static final Pattern FLAG_XMX_PATTERN = Pattern
		.compile("(?i)(?<!\\S)(?:-Xmx|(?:-XX:MaxHeapSize=))(\\d+)([kmg]?)(?!\\S)");

	private static final Pattern FLAG_G1_PATTERN = Pattern.compile("(?i)-XX:\\+UseG1GC");

	private final ClassHistogramParser classHistogramParser = new ClassHistogramParser();

	private final ThreadDumpParser threadDumpParser = new ThreadDumpParser();

	private final VmVersionParser vmVersionParser = new VmVersionParser();

	private final PerfCounterLiveThreadsParser perfCounterLiveThreadsParser = new PerfCounterLiveThreadsParser();

	private final JstatClassLoadedParser jstatClassLoadedParser = new JstatClassLoadedParser();

	private final CommandExecutor executor;

	private final RuntimeCollectionPolicy policy;

	private final SharkHeapDumpSummarizer heapDumpSummarizer;

	private final boolean autoHeapSummary;

	private final RepeatedSamplingProperties repeatedSamplingProperties;

	private final JfrRecordingProperties jfrRecordingProperties;

	private final JfrSummaryParserAdapter jfrSummaryParser;

	private final LongConsumer sleeper;

	public SafeJvmRuntimeCollector(CommandExecutor executor, RuntimeCollectionPolicy policy,
			SharkHeapDumpSummarizer heapDumpSummarizer, boolean autoHeapSummary) {
		this(executor, policy, heapDumpSummarizer, autoHeapSummary, RepeatedSamplingProperties.defaults(),
				SafeJvmRuntimeCollector::sleepUnchecked);
	}

	public SafeJvmRuntimeCollector(CommandExecutor executor, RuntimeCollectionPolicy policy,
			SharkHeapDumpSummarizer heapDumpSummarizer, boolean autoHeapSummary,
			RepeatedSamplingProperties repeatedSamplingProperties) {
		this(executor, policy, heapDumpSummarizer, autoHeapSummary, repeatedSamplingProperties,
				SafeJvmRuntimeCollector::sleepUnchecked);
	}

	public SafeJvmRuntimeCollector(CommandExecutor executor, RuntimeCollectionPolicy policy,
			SharkHeapDumpSummarizer heapDumpSummarizer, boolean autoHeapSummary,
			RepeatedSamplingProperties repeatedSamplingProperties, JfrRecordingProperties jfrRecordingProperties) {
		this(executor, policy, heapDumpSummarizer, autoHeapSummary, repeatedSamplingProperties, jfrRecordingProperties,
				new JfrSummaryParser(jfrRecordingProperties.topLimit()), SafeJvmRuntimeCollector::sleepUnchecked);
	}

	SafeJvmRuntimeCollector(CommandExecutor executor, RuntimeCollectionPolicy policy,
			SharkHeapDumpSummarizer heapDumpSummarizer, boolean autoHeapSummary,
			RepeatedSamplingProperties repeatedSamplingProperties, LongConsumer sleeper) {
		this(executor, policy, heapDumpSummarizer, autoHeapSummary, repeatedSamplingProperties,
				JfrRecordingProperties.defaults(), new JfrSummaryParser(JfrRecordingProperties.defaults().topLimit()),
				sleeper);
	}

	SafeJvmRuntimeCollector(CommandExecutor executor, RuntimeCollectionPolicy policy,
			SharkHeapDumpSummarizer heapDumpSummarizer, boolean autoHeapSummary,
			RepeatedSamplingProperties repeatedSamplingProperties, JfrRecordingProperties jfrRecordingProperties,
			JfrSummaryParserAdapter jfrSummaryParser, LongConsumer sleeper) {
		this.executor = executor;
		this.policy = policy;
		this.heapDumpSummarizer = heapDumpSummarizer;
		this.autoHeapSummary = autoHeapSummary;
		this.repeatedSamplingProperties = repeatedSamplingProperties;
		this.jfrRecordingProperties = jfrRecordingProperties;
		this.jfrSummaryParser = jfrSummaryParser;
		this.sleeper = sleeper;
	}

	@Override
	public JvmRuntimeSnapshot collect(long pid, RuntimeCollectionPolicy.CollectionRequest request) {
		long startedAt = System.currentTimeMillis();
		policy.validate(request);
		String pidValue = Long.toString(pid);
		String flags = executor.run(vmFlagsCommand(pidValue));
		String versionRaw = executor.run(vmVersionCommand(pidValue));
		String heapInfo = executor.run(heapInfoCommand(pidValue));
		String gcUtil = executor.run(gcUtilCommand(pidValue));
		String classOut = executor.run(classCommand(pidValue));
		String perfOut = executor.run(perfCounterCommand(pidValue));
		long finishedAt = System.currentTimeMillis();
		List<String> vmFlags = flags.isBlank() ? List.of() : List.of(flags.trim().split("\\s+"));
		JvmMemorySnapshot memory = new GcHeapInfoParser().parse(heapInfo);
		Long xmsBytes = parseFlagSize(flags, FLAG_XMS_PATTERN);
		Long xmxBytes = parseFlagSize(flags, FLAG_XMX_PATTERN);
		long heapMaxBytes = xmxBytes != null ? xmxBytes : 0L;
		JvmMemorySnapshot structuredMemory = new JvmMemorySnapshot(memory.heapUsedBytes(), memory.heapCommittedBytes(),
				heapMaxBytes, memory.oldGenUsedBytes(), memory.oldGenCommittedBytes(), memory.metaspaceUsedBytes(),
				xmsBytes, xmxBytes);
		String collector = inferCollector(vmFlags);
		JvmGcSnapshot gc = new JstatGcUtilParser().parse(gcUtil);
		gc = new JvmGcSnapshot(collector, gc.youngGcCount(), gc.youngGcTimeMs(), gc.fullGcCount(),
				gc.fullGcTimeMs(), gc.oldUsagePercent());
		String jvmVersion = vmVersionParser.parse(versionRaw);
		Long threadCount = perfCounterLiveThreadsParser.parse(perfOut);
		Long loadedClassCount = jstatClassLoadedParser.parseLoadedClasses(classOut);
		boolean perfBlank = perfOut == null || perfOut.isBlank();
		List<String> warnings = buildWarnings(heapInfo, collector, threadCount, perfBlank);
		JvmCollectionMetadata metadata = new JvmCollectionMetadata(
				List.of(String.join(" ", vmFlagsCommand(pidValue)), String.join(" ", vmVersionCommand(pidValue)),
						String.join(" ", heapInfoCommand(pidValue)), String.join(" ", gcUtilCommand(pidValue)),
						String.join(" ", classCommand(pidValue)), String.join(" ", perfCounterCommand(pidValue))),
				startedAt, finishedAt - startedAt, request.includeThreadDump() || request.includeClassHistogram()
						|| request.includeJfr() || request.includeHeapDump());
		return new JvmRuntimeSnapshot(pid, structuredMemory, gc, vmFlags, jvmVersion, threadCount, loadedClassCount,
				metadata, warnings);
	}

	@Override
	public MemoryGcEvidencePack collectMemoryGcEvidence(MemoryGcEvidenceRequest request) {
		policy.validate(request);
		JvmRuntimeSnapshot snapshot = collect(request.pid(), new RuntimeCollectionPolicy.CollectionRequest(
				request.includeThreadDump(), request.includeClassHistogram(), false, request.includeHeapDump(),
				request.confirmationToken()));

		List<String> missingData = new java.util.ArrayList<>();
		List<String> warnings = new java.util.ArrayList<>(snapshot.warnings());
		ClassHistogramSummary classHistogram = null;
		ThreadDumpSummary threadDump = null;
		String heapDumpPathResult = null;
		if (request.includeThreadDump()) {
			try {
				String output = executor.run(threadPrintCommand(Long.toString(request.pid())));
				threadDump = threadDumpParser.parse(output);
				if (output == null || output.isBlank() || threadDump.threadCount() == 0) {
					missingData.add("threadDump");
					warnings.add("Thread.print output was missing or could not be parsed");
				}
			}
			catch (RuntimeException ex) {
				missingData.add("threadDump");
				warnings.add("Unable to collect Thread.print: " + ex.getMessage());
			}
		}
		if (request.includeClassHistogram()) {
			try {
				String output = executor.run(classHistogramCommand(Long.toString(request.pid())));
				if (output == null || output.isBlank()) {
					missingData.add("classHistogram");
				}
				else {
					classHistogram = classHistogramParser.parse(output);
					if (classHistogram.entries().isEmpty()) {
						warnings.add("GC.class_histogram output was non-blank but could not be parsed");
					}
				}
			}
			catch (RuntimeException ex) {
				missingData.add("classHistogram");
				warnings.add("Unable to collect GC.class_histogram: " + ex.getMessage());
			}
		}
		HeapDumpShallowSummary heapShallowSummary = null;
		if (request.includeHeapDump()) {
			Path dumpPath = Path.of(request.heapDumpOutputPath()).toAbsolutePath().normalize();
			try {
				String output = executor.run(heapDumpCommand(Long.toString(request.pid()), dumpPath.toString()));
				if (Files.isRegularFile(dumpPath)) {
					heapDumpPathResult = dumpPath.toString();
					if (autoHeapSummary) {
						heapShallowSummary = heapDumpSummarizer.summarize(dumpPath, null, null);
						if (!heapShallowSummary.analysisSucceeded()) {
							warnings.add("Heap dump shallow summary failed: " + heapShallowSummary.errorMessage());
						}
					}
				}
				else {
					missingData.add("heapDump");
					warnings.add("jcmd GC.heap_dump finished but file was not found at " + dumpPath);
					if (output != null && !output.isBlank()) {
						String firstLine = output.trim().lines().findFirst().orElse("");
						if (!firstLine.isBlank()) {
							warnings.add("GC.heap_dump output (first line): " + firstLine);
						}
					}
				}
			}
			catch (RuntimeException ex) {
				missingData.add("heapDump");
				warnings.add("Unable to collect GC.heap_dump: " + ex.getMessage());
			}
		}
		List<String> commandsRun = new java.util.ArrayList<>(snapshot.collectionMetadata().commandsRun());
		if (request.includeClassHistogram()) {
			commandsRun.add(String.join(" ", classHistogramCommand(Long.toString(request.pid()))));
		}
		if (request.includeThreadDump()) {
			commandsRun.add(String.join(" ", threadPrintCommand(Long.toString(request.pid()))));
		}
		if (request.includeHeapDump()) {
			commandsRun.add(String.join(" ",
					heapDumpCommand(Long.toString(request.pid()),
							Path.of(request.heapDumpOutputPath()).toAbsolutePath().normalize().toString())));
		}
		JvmCollectionMetadata metadata = new JvmCollectionMetadata(List.copyOf(commandsRun),
				snapshot.collectionMetadata().collectedAtEpochMs(), snapshot.collectionMetadata().elapsedMs(),
				snapshot.collectionMetadata().privilegedCollection());
		JvmRuntimeSnapshot enrichedSnapshot = new JvmRuntimeSnapshot(snapshot.pid(), snapshot.memory(), snapshot.gc(),
				snapshot.vmFlags(), snapshot.jvmVersion(), snapshot.threadCount(), snapshot.loadedClassCount(), metadata,
				snapshot.warnings());
		return new MemoryGcEvidencePack(enrichedSnapshot, classHistogram, threadDump, List.copyOf(missingData),
				List.copyOf(warnings), heapDumpPathResult, heapShallowSummary);
	}

	@Override
	public RepeatedSamplingResult collectRepeated(RepeatedSamplingRequest request) {
		RepeatedSamplingRequest normalized = request.normalized(repeatedSamplingProperties);
		long startedAt = System.currentTimeMillis();
		List<RepeatedRuntimeSample> samples = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<String> missingData = new ArrayList<>();
		for (int index = 0; index < normalized.sampleCount(); index++) {
			if (index > 0) {
				sleeper.accept(normalized.intervalMillis());
			}
			try {
				JvmRuntimeSnapshot snapshot = collect(normalized.pid(), RuntimeCollectionPolicy.CollectionRequest.safeReadonly());
				samples.add(new RepeatedRuntimeSample(System.currentTimeMillis(), snapshot.memory(), snapshot.gc(),
						normalized.includeThreadCount() ? snapshot.threadCount() : null,
						normalized.includeClassCount() ? snapshot.loadedClassCount() : null, snapshot.warnings()));
			}
			catch (RuntimeException ex) {
				missingData.add("sample[" + index + "]");
				warnings.add("Repeated sample " + (index + 1) + " failed: " + ex.getMessage());
			}
		}
		if (samples.size() < 2) {
			missingData.add("repeatedTrendAnalysis");
			warnings.add("Fewer than two repeated samples succeeded; trend analysis is unavailable.");
		}
		return new RepeatedSamplingResult(normalized.pid(), samples, warnings, missingData, startedAt,
				Math.max(0L, System.currentTimeMillis() - startedAt));
	}

	@Override
	public JfrRecordingResult recordJfr(JfrRecordingRequest request) {
		long startedAt = System.currentTimeMillis();
		JfrRecordingRequest normalized = request.normalized(jfrRecordingProperties);
		String pidValue = Long.toString(normalized.pid());
		Path output = Path.of(normalized.jfrOutputPath()).toAbsolutePath().normalize();
		List<String> commandsRun = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		List<String> missingData = new ArrayList<>();

		List<String> supportCommand = jfrHelpCommand(pidValue);
		commandsRun.add(String.join(" ", supportCommand));
		CommandExecutionResult support = executor.execute(supportCommand, new CommandExecutionOptions(15_000L, 1024 * 1024));
		if (!support.succeeded() || support.output() == null || !support.output().contains("JFR.start")) {
			missingData.add("jfrSupport");
			missingData.add("jfrRecording");
			warnings.add("JFR.start is not available on target JVM: "
					+ firstNonBlank(support.failureMessage(), support.output(), "no help output"));
			return jfrResult(normalized.pid(), null, 0L, startedAt, commandsRun, null, warnings, missingData);
		}

		String recordingName = "java-tuning-agent-" + normalized.pid() + "-" + startedAt;
		List<String> recordCommand = jfrStartCommand(pidValue, recordingName, normalized.settings(),
				normalized.durationSeconds(), output.toString());
		commandsRun.add(String.join(" ", recordCommand));
		long timeoutMs = normalized.durationSeconds() * 1000L + jfrRecordingProperties.completionGraceMs();
		CommandExecutionResult record = executor.execute(recordCommand, new CommandExecutionOptions(timeoutMs, 1024 * 1024));
		if (!record.succeeded()) {
			missingData.add("jfrRecording");
			missingData.add("jfrFile");
			missingData.add("jfrSummary");
			warnings.add("Unable to start JFR recording " + recordingName + ": "
					+ firstNonBlank(record.failureMessage(), record.output(), "unknown failure"));
			return jfrResult(normalized.pid(), null, 0L, startedAt, commandsRun, null, warnings, missingData);
		}

		if (!waitForStableFile(output, jfrRecordingProperties.completionGraceMs())) {
			missingData.add("jfrFile");
			missingData.add("jfrSummary");
			warnings.add("JFR recording command finished but file was not found at " + output);
			return jfrResult(normalized.pid(), null, 0L, startedAt, commandsRun, null, warnings, missingData);
		}

		long fileSize = safeSize(output);
		try {
			JfrSummary summary = jfrSummaryParser.parse(output, normalized.maxSummaryEvents());
			warnings.addAll(summary.parserWarnings());
			return jfrResult(normalized.pid(), output.toString(), fileSize, startedAt, commandsRun, summary, warnings,
					missingData);
		}
		catch (RuntimeException ex) {
			missingData.add("jfrSummary");
			warnings.add("Unable to parse JFR recording: " + ex.getMessage());
			return jfrResult(normalized.pid(), output.toString(), fileSize, startedAt, commandsRun, null, warnings,
					missingData);
		}
	}

	private List<String> vmFlagsCommand(String pidValue) {
		return List.of(JCMD, pidValue, "VM.flags");
	}

	private List<String> vmVersionCommand(String pidValue) {
		return List.of(JCMD, pidValue, "VM.version");
	}

	private List<String> heapInfoCommand(String pidValue) {
		return List.of(JCMD, pidValue, "GC.heap_info");
	}

	private List<String> gcUtilCommand(String pidValue) {
		return List.of(JSTAT, "-gcutil", pidValue);
	}

	private List<String> classCommand(String pidValue) {
		return List.of(JSTAT, "-class", pidValue);
	}

	private List<String> perfCounterCommand(String pidValue) {
		return List.of(JCMD, pidValue, "PerfCounter.print");
	}

	private List<String> classHistogramCommand(String pidValue) {
		return List.of(JCMD, pidValue, "GC.class_histogram");
	}

	private List<String> threadPrintCommand(String pidValue) {
		return List.of(JCMD, pidValue, "Thread.print");
	}

	private List<String> heapDumpCommand(String pidValue, String absolutePath) {
		return List.of(JCMD, pidValue, "GC.heap_dump", absolutePath);
	}

	private List<String> jfrHelpCommand(String pidValue) {
		return List.of(JCMD, pidValue, "help", "JFR.start");
	}

	private List<String> jfrStartCommand(String pidValue, String name, String settings, int durationSeconds,
			String absolutePath) {
		return List.of(JCMD, pidValue, "JFR.start", "name=" + name, "settings=" + settings,
				"duration=" + durationSeconds + "s", "filename=" + absolutePath, "disk=true");
	}

	private boolean waitForStableFile(Path output, long graceMs) {
		long deadline = System.currentTimeMillis() + Math.max(0L, graceMs);
		long previousSize = -1L;
		while (System.currentTimeMillis() <= deadline) {
			long size = safeSize(output);
			if (size > 0L && size == previousSize) {
				return true;
			}
			previousSize = size;
			sleeper.accept(100L);
		}
		return safeSize(output) > 0L;
	}

	private long safeSize(Path output) {
		try {
			return Files.isRegularFile(output) ? Files.size(output) : 0L;
		}
		catch (Exception ex) {
			return 0L;
		}
	}

	private JfrRecordingResult jfrResult(long pid, String path, long fileSizeBytes, long startedAtEpochMs,
			List<String> commandsRun, JfrSummary summary, List<String> warnings, List<String> missingData) {
		return new JfrRecordingResult(pid, path, fileSizeBytes, startedAtEpochMs,
				Math.max(0L, System.currentTimeMillis() - startedAtEpochMs), commandsRun, summary, warnings,
				missingData);
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value.trim();
			}
		}
		return "";
	}

	private Long parseFlagSize(String flags, Pattern pattern) {
		if (flags == null || flags.isBlank()) {
			return null;
		}
		Matcher matcher = pattern.matcher(flags);
		if (!matcher.find()) {
			return null;
		}
		return toBytesWithUnit(matcher.group(1), matcher.group(2));
	}

	private String inferCollector(List<String> vmFlags) {
		for (String flag : vmFlags) {
			if (FLAG_G1_PATTERN.matcher(flag).find()) {
				return "G1";
			}
		}
		return "unknown";
	}

	private List<String> buildWarnings(String heapInfo, String collector, Long threadCountFromPerf,
			boolean perfOutputBlank) {
		List<String> warnings = new java.util.ArrayList<>();
		if (!"G1".equals(collector)) {
			warnings.add("Unable to infer GC collector from VM.flags");
		}
		if (heapInfo == null || heapInfo.isBlank() || !heapInfo.toLowerCase().contains("garbage-first heap")) {
			warnings.add("Unsupported or non-G1 GC.heap_info output");
		}
		if (!perfOutputBlank && threadCountFromPerf == null) {
			warnings.add("Could not parse java.threads.live from jcmd PerfCounter.print");
		}
		return List.copyOf(warnings);
	}

	private long toBytesWithUnit(String value, String unit) {
		long parsed = Long.parseLong(value);
		if (unit == null || unit.isBlank()) {
			return parsed;
		}
		return switch (unit.toLowerCase()) {
			case "k" -> parsed * 1024L;
			case "m" -> parsed * 1024L * 1024L;
			case "g" -> parsed * 1024L * 1024L * 1024L;
			default -> parsed;
		};
	}

	private static void sleepUnchecked(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for repeated JVM sample interval", ex);
		}
	}

	static void sleepUncheckedForTests(long millis) {
	}

}
