package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapDumpSummarizer;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapDumpShallowSummary;

public class SafeJvmRuntimeCollector implements JvmRuntimeCollector {

	private static final String JCMD = "jcmd";

	private static final String JSTAT = "jstat";

	private static final Pattern FLAG_XMS_PATTERN = Pattern
		.compile("(?i)(?<!\\S)(?:-Xms|(?:-XX:InitialHeapSize=))(\\d+)([kmg]?)(?!\\S)");

	private static final Pattern FLAG_XMX_PATTERN = Pattern
		.compile("(?i)(?<!\\S)(?:-Xmx|(?:-XX:MaxHeapSize=))(\\d+)([kmg]?)(?!\\S)");

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

	private final GcCollectorDetector gcCollectorDetector = new GcCollectorDetector();

	private final NativeMemorySummaryParser nativeMemorySummaryParser = new NativeMemorySummaryParser();

	private final ResourceBudgetEvidenceParser resourceBudgetEvidenceParser = new ResourceBudgetEvidenceParser();

	private final JvmCapabilitiesPolicy capabilitiesPolicy = new JvmCapabilitiesPolicy();

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
		String collector = inferCollector(vmFlags, heapInfo);
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
		NativeMemorySummary nativeMemorySummary = null;
		boolean nativeCommandAttempted = false;
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
		JvmCapabilitiesPolicy.NativeMemoryCapability capability = capabilitiesPolicy
			.nativeMemoryCapability(snapshot.gc().collector(), snapshot.jvmVersion());
		warnings.addAll(capability.warnings());
		if (!capability.nmtSupported()) {
			missingData.addAll(capability.missingData());
		}
		else if (!supportsNativeMemoryCommand(Long.toString(request.pid()), warnings)) {
			missingData.add("nativeMemorySummary");
		}
		else {
			try {
				nativeCommandAttempted = true;
				String output = executor.run(nativeMemorySummaryCommand(Long.toString(request.pid())));
				if (output == null || output.isBlank()) {
					missingData.add("nativeMemorySummary");
				}
				else {
					nativeMemorySummary = nativeMemorySummaryParser.parse(output);
					NativeMemorySummary diffSummary = collectNativeMemoryDiff(Long.toString(request.pid()), warnings);
					nativeMemorySummary = mergeNativeMemorySummary(nativeMemorySummary, diffSummary);
					warnings.addAll(nativeMemorySummary.warnings().stream().filter(w -> w != null && !w.isBlank())
							.collect(Collectors.toList()));
					if (!nativeMemorySummary.hasTotals()) {
						missingData.add("nativeMemorySummary");
					}
				}
			}
			catch (RuntimeException ex) {
				missingData.add("nativeMemorySummary");
				warnings.add("Unable to collect VM.native_memory summary: " + ex.getMessage());
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
		if (nativeCommandAttempted) {
			commandsRun.add(String.join(" ", nativeMemorySummaryCommand(Long.toString(request.pid()))));
		}
		JvmCollectionMetadata metadata = new JvmCollectionMetadata(List.copyOf(commandsRun),
				snapshot.collectionMetadata().collectedAtEpochMs(), snapshot.collectionMetadata().elapsedMs(),
				snapshot.collectionMetadata().privilegedCollection());
		JvmRuntimeSnapshot enrichedSnapshot = new JvmRuntimeSnapshot(snapshot.pid(), snapshot.memory(), snapshot.gc(),
				snapshot.vmFlags(), snapshot.jvmVersion(), snapshot.threadCount(), snapshot.loadedClassCount(), metadata,
				snapshot.warnings());
		ResourceBudgetEvidence resourceBudgetEvidence = collectResourceBudget(request.pid(), enrichedSnapshot,
				nativeMemorySummary);
		return new MemoryGcEvidencePack(enrichedSnapshot, classHistogram, threadDump, List.copyOf(missingData),
				List.copyOf(warnings), heapDumpPathResult, heapShallowSummary).withNativeMemorySummary(nativeMemorySummary)
			.withDiagnosisWindow(DiagnosisWindow.fromSnapshot(enrichedSnapshot, "live"))
			.withResourceBudgetEvidence(resourceBudgetEvidence);
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

	private List<String> nativeMemorySummaryCommand(String pidValue) {
		return List.of(JCMD, pidValue, "VM.native_memory", "summary");
	}

	private List<String> nativeMemorySummaryDiffCommand(String pidValue) {
		return List.of(JCMD, pidValue, "VM.native_memory", "summary.diff");
	}

	private List<String> nativeMemoryHelpCommand(String pidValue) {
		return List.of(JCMD, pidValue, "help", "VM.native_memory");
	}

	private ResourceBudgetEvidence collectResourceBudget(long pid, JvmRuntimeSnapshot snapshot,
			NativeMemorySummary nativeMemorySummary) {
		StringBuilder text = new StringBuilder();
		appendKeyValue(text, "containerMemoryLimitBytes", readContainerMemoryLimitBytes());
		appendKeyValue(text, "processRssBytes", readProcessRssBytes(pid));
		appendKeyValue(text, "cpuQuotaCores", readCpuQuotaCores());
		return resourceBudgetEvidenceParser.parse(text.toString(), snapshot, nativeMemorySummary);
	}

	private Long readProcessRssBytes(long pid) {
		Long procRss = readLinuxProcRssBytes(pid);
		if (procRss != null) {
			return procRss;
		}
		try {
			String output = executor.run(List.of("ps", "-o", "rss=", "-p", Long.toString(pid)));
			if (output == null || output.isBlank()) {
				return null;
			}
			String first = output.trim().lines().findFirst().orElse("").trim();
			if (first.isBlank()) {
				return null;
			}
			return Long.parseLong(first) * 1024L;
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

	private static Long readLinuxProcRssBytes(long pid) {
		Path status = Path.of("/proc", Long.toString(pid), "status");
		try {
			if (!Files.isRegularFile(status)) {
				return null;
			}
			for (String line : Files.readAllLines(status)) {
				if (line.startsWith("VmRSS:")) {
					return ResourceBudgetEvidenceParser.parseBytes(line.substring("VmRSS:".length()));
				}
			}
		}
		catch (Exception ex) {
			return null;
		}
		return null;
	}

	private static Long readContainerMemoryLimitBytes() {
		Long cgroupV2 = readLimitFile(Path.of("/sys/fs/cgroup/memory.max"));
		if (cgroupV2 != null) {
			return cgroupV2;
		}
		return readLimitFile(Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));
	}

	private static Long readLimitFile(Path path) {
		try {
			if (!Files.isRegularFile(path)) {
				return null;
			}
			String value = Files.readString(path).trim();
			if (value.isBlank() || "max".equalsIgnoreCase(value)) {
				return null;
			}
			Long parsed = ResourceBudgetEvidenceParser.parseBytes(value);
			if (parsed == null || parsed >= Long.MAX_VALUE / 4096L) {
				return null;
			}
			return parsed;
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static Double readCpuQuotaCores() {
		Double cgroupV2 = readCpuMax(Path.of("/sys/fs/cgroup/cpu.max"));
		if (cgroupV2 != null) {
			return cgroupV2;
		}
		return readCpuCfsQuota(Path.of("/sys/fs/cgroup/cpu/cpu.cfs_quota_us"),
				Path.of("/sys/fs/cgroup/cpu/cpu.cfs_period_us"));
	}

	private static Double readCpuMax(Path path) {
		try {
			if (!Files.isRegularFile(path)) {
				return null;
			}
			String[] parts = Files.readString(path).trim().split("\\s+");
			if (parts.length < 2 || "max".equalsIgnoreCase(parts[0])) {
				return null;
			}
			long quota = Long.parseLong(parts[0]);
			long period = Long.parseLong(parts[1]);
			return quota > 0L && period > 0L ? quota / (double) period : null;
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static Double readCpuCfsQuota(Path quotaPath, Path periodPath) {
		try {
			if (!Files.isRegularFile(quotaPath) || !Files.isRegularFile(periodPath)) {
				return null;
			}
			long quota = Long.parseLong(Files.readString(quotaPath).trim());
			long period = Long.parseLong(Files.readString(periodPath).trim());
			return quota > 0L && period > 0L ? quota / (double) period : null;
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static void appendKeyValue(StringBuilder text, String key, Object value) {
		if (value != null) {
			text.append(key).append('=').append(value).append('\n');
		}
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

	private String inferCollector(List<String> vmFlags, String heapInfo) {
		return gcCollectorDetector.infer(String.join(" ", vmFlags), heapInfo);
	}

	private boolean supportsNativeMemoryCommand(String pidValue, List<String> warnings) {
		try {
			String support = executor.run(nativeMemoryHelpCommand(pidValue));
			if (support == null || support.isBlank()) {
				warnings.add(JvmCapabilitiesPolicy.nativeMemoryDegradeWarning("VM.native_memory help output was blank"));
				return false;
			}
			if (!support.contains("VM.native_memory")) {
				warnings.add(JvmCapabilitiesPolicy.nativeMemoryDegradeWarning("VM.native_memory is unavailable"));
				return false;
			}
			return true;
		}
		catch (RuntimeException ex) {
			warnings.add(JvmCapabilitiesPolicy
				.nativeMemoryDegradeWarning("unable to probe VM.native_memory support: " + ex.getMessage()));
			return false;
		}
	}

	private NativeMemorySummary collectNativeMemoryDiff(String pidValue, List<String> warnings) {
		try {
			String diff = executor.run(nativeMemorySummaryDiffCommand(pidValue));
			if (diff == null || diff.isBlank() || !diff.contains("reserved=")) {
				return null;
			}
			return nativeMemorySummaryParser.parse(diff);
		}
		catch (RuntimeException ex) {
			warnings.add(JvmCapabilitiesPolicy
				.nativeMemoryDegradeWarning("VM.native_memory summary.diff is unavailable: " + ex.getMessage()));
			return null;
		}
	}

	private NativeMemorySummary mergeNativeMemorySummary(NativeMemorySummary summary, NativeMemorySummary diff) {
		if (summary == null) {
			return diff;
		}
		if (diff == null || diff.categoryGrowth().isEmpty()) {
			return summary;
		}
		return new NativeMemorySummary(summary.totalReservedBytes(), summary.totalCommittedBytes(), summary.directReservedBytes(),
				summary.directCommittedBytes(), summary.classReservedBytes(), summary.classCommittedBytes(),
				summary.categories(), diff.categoryGrowth(), summary.warnings());
	}

	private List<String> buildWarnings(String heapInfo, String collector, Long threadCountFromPerf,
			boolean perfOutputBlank) {
		List<String> warnings = new java.util.ArrayList<>();
		if ("unknown".equals(collector)) {
			warnings.add("Unable to infer GC collector from VM.flags");
		}
		if (heapInfo == null || heapInfo.isBlank()) {
			warnings.add("GC.heap_info output was blank");
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
