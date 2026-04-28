package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.util.List;
import java.util.Objects;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceRequest;
import com.alibaba.cloud.ai.examples.javatuning.agent.JavaTuningWorkflowService;
import com.alibaba.cloud.ai.examples.javatuning.discovery.JavaApplicationDescriptor;
import com.alibaba.cloud.ai.examples.javatuning.discovery.JavaProcessDiscoveryService;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrRecordingRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrRecordingResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidenceRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ResourceBudgetEvidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RuntimeCollectionPolicy;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public class JavaTuningMcpTools {

	private final JavaProcessDiscoveryService discoveryService;

	private final JvmRuntimeCollector collector;

	private final JavaTuningWorkflowService workflowService;

	public JavaTuningMcpTools(JavaProcessDiscoveryService discoveryService, JvmRuntimeCollector collector,
			JavaTuningWorkflowService workflowService) {
		this.discoveryService = discoveryService;
		this.collector = collector;
		this.workflowService = workflowService;
	}

	@Tool(description = """
			List local Java applications visible to the current machine user (typically via jps -lvm). \
			中文：列出当前用户可见的本机 Java/JVM 进程，用返回的 pid 进入后续诊断。 \
			No parameters. Use returned pid values with inspectJvmRuntime / collectMemoryGcEvidence / generateTuningAdvice / generateTuningAdviceFromEvidence.""")
	public List<JavaApplicationDescriptor> listJavaApps() {
		return discoveryService.listJavaApplications();
	}

	@Tool(description = """
			Collect a safe read-only JVM runtime snapshot for the given PID using jcmd/jstat only (no histogram, thread dump, or heap dump). \
			中文：对指定 pid 做轻量只读 JVM 快照，不采集直方图、线程栈或堆转储。 \
			Example arguments JSON: {"pid": 12345}""")
	public JvmRuntimeSnapshot inspectJvmRuntime(
			@ToolParam(description = "Target JVM process id (decimal); must match a pid from listJavaApps.") long pid) {
		return collector.collect(pid, RuntimeCollectionPolicy.CollectionRequest.safeReadonly());
	}

	@Tool(description = """
			Collect repeated safe read-only JVM runtime samples for a PID using bounded jcmd/jstat commands. \
			中文：按固定间隔采集多次轻量只读样本，用于短窗口趋势判断。 \
			This P0 repeated mode does not collect class histograms, thread dumps, heap dumps, or JFR, and does not require confirmationToken. \
			Example arguments JSON: {"request":{"pid":12345,"sampleCount":3,"intervalMillis":10000,"includeThreadCount":true,"includeClassCount":true,"confirmationToken":""}}""")
	public RepeatedSamplingResult inspectJvmRuntimeRepeated(
			@ToolParam(description = "RepeatedSamplingRequest JSON: pid, sampleCount, intervalMillis, includeThreadCount, includeClassCount.") RepeatedSamplingRequest request) {
		return collector.collectRepeated(request);
	}

	@Tool(description = """
			Record one short Java Flight Recorder session for a target JVM and return the .jfr path plus a lightweight summary. \
			中文：为目标 JVM 录制一次短时 JFR，并返回 .jfr 路径与 GC/分配/线程等摘要。 \
			Requires a non-blank confirmationToken and an absolute jfrOutputPath ending in .jfr. \
			Example: {"request":{"pid":12345,"durationSeconds":30,"settings":"profile","jfrOutputPath":"C:/tmp/app.jfr","maxSummaryEvents":200000,"confirmationToken":"user-approved"}}""")
	public JfrRecordingResult recordJvmFlightRecording(
			@ToolParam(description = "JfrRecordingRequest JSON: pid, durationSeconds, settings, jfrOutputPath, maxSummaryEvents, confirmationToken.") JfrRecordingRequest request) {
		return collector.recordJfr(request);
	}

	@Tool(description = """
			Collect medium-cost memory/GC evidence: optional class histogram, thread dump, and/or heap dump (jcmd GC.heap_dump). \
			中文：按用户选择采集中等成本内存/GC 证据，可包含类直方图、线程栈和/或 heap dump。 \
			Privileged options require a non-blank confirmationToken; heap dump also requires heapDumpOutputPath (absolute .hprof path, existing parent directory, target file must not already exist). \
			Example: {"request":{"pid":12345,"includeClassHistogram":true,"includeThreadDump":false,"includeHeapDump":false,"heapDumpOutputPath":"","confirmationToken":"user-approved"}}""")
	public MemoryGcEvidencePack collectMemoryGcEvidence(
			@ToolParam(description = "Evidence selection and privileged-field policy; see MemoryGcEvidenceRequest JSON shape.") MemoryGcEvidenceRequest request) {
		return workflowService.collectEvidence(request);
	}

	@Tool(description = """
			Generate structured tuning advice from runtime data and code context. \
			中文：基于运行时数据与源码上下文生成结构化 JVM 调优建议，默认只做轻量快照。 \
			Returns TuningAdviceReport JSON: findings, recommendations, suspectedCodeHotspots, missingData, nextSteps, confidence, confidenceReasons, and formattedSummary. \
			The formattedSummary field is stable Markdown (same sections in a fixed order) for user-facing display—hosts should prefer showing it so suspectedCodeHotspots are not dropped. \
			By default collects a lightweight jcmd/jstat snapshot only (all three collect* flags false, confirmationToken may be blank). \
			Set collectClassHistogram=true and non-blank confirmationToken to run GC.class_histogram for retention signals and source file hints when sourceRoots are set. \
			Set collectThreadDump=true and/or includeHeapDump=true (plus absolute .hprof heapDumpOutputPath whose parent exists and target does not) for deeper diagnostics; confirmationToken is required when any privileged flag is true. \
			If you already have a MemoryGcEvidencePack from collectMemoryGcEvidence, call generateTuningAdviceFromEvidence instead so evidence is not collected twice. \
			Example (lightweight): {"codeContextSummary":{"dependencies":[],"configuration":{},"applicationNames":[],"sourceRoots":[],"candidatePackages":[]},"pid":12345,"environment":"local","optimizationGoal":"reduce GC pause","collectClassHistogram":false,"collectThreadDump":false,"includeHeapDump":false,"heapDumpOutputPath":"","confirmationToken":""} \
			Example (histogram): same as above but collectClassHistogram=true and confirmationToken non-blank. \
			Optional baselineEvidence: prior MemoryGcEvidencePack (e.g. earlier collectMemoryGcEvidence) to populate Key Deltas vs current run. \
			Optional jfrSummary: JFR summary object (e.g. recordJvmFlightRecording result summary) so JFR-backed findings are included. \
			Optional repeatedSamplingResult: inspectJvmRuntimeRepeated output from the same diagnosis window so trend findings are included. \
			Optional resourceBudgetEvidence: cgroup/RSS/CPU budget evidence when supplied by the caller or imported bundle.""")
	public TuningAdviceReport generateTuningAdvice(
			@ToolParam(description = "CodeContextSummary JSON: dependencies, configuration, applicationNames, sourceRoots, candidatePackages.") CodeContextSummary codeContextSummary,
			@ToolParam(description = "Target JVM process id (decimal); must match listJavaApps.") long pid,
			@ToolParam(description = "Deployment or runtime label, e.g. prod, stage, local.") String environment,
			@ToolParam(description = "What to optimize for: latency, throughput, footprint, diagnose leak, etc.") String optimizationGoal,
			@ToolParam(required = false, description = "When true, runs GC.class_histogram before advice (requires non-blank confirmationToken).") boolean collectClassHistogram,
			@ToolParam(required = false, description = "When true, runs Thread.print before advice (requires non-blank confirmationToken).") boolean collectThreadDump,
			@ToolParam(required = false, description = "When true, runs GC.heap_dump to heapDumpOutputPath (requires non-blank confirmationToken and an absolute .hprof path that does not already exist).") boolean includeHeapDump,
			@ToolParam(required = false, description = "Absolute .hprof output path when includeHeapDump is true; parent directory must exist and target file must not already exist. Otherwise \"\".") String heapDumpOutputPath,
			@ToolParam(required = false, description = "Non-blank user approval token whenever any of collectClassHistogram, collectThreadDump, or includeHeapDump is true.") String confirmationToken,
			@ToolParam(required = false, description = "Optional prior MemoryGcEvidencePack for Key Deltas (omit or null for none). Same JSON shape as collectMemoryGcEvidence output.") MemoryGcEvidencePack baselineEvidence,
			@ToolParam(required = false, description = "Optional JfrSummary merged into diagnosis (omit or null). Use summary from recordJvmFlightRecording or inline parsed summary.") JfrSummary jfrSummary,
			@ToolParam(required = false, description = "Optional RepeatedSamplingResult from inspectJvmRuntimeRepeated for trend-aware findings (omit or null).") RepeatedSamplingResult repeatedSamplingResult,
			@ToolParam(required = false, description = "Optional ResourceBudgetEvidence with cgroup/RSS/CPU and heap/native/thread-stack budget fields (omit or null).") ResourceBudgetEvidence resourceBudgetEvidence) {
		environment = environment == null ? "" : environment;
		optimizationGoal = optimizationGoal == null ? "" : optimizationGoal;
		confirmationToken = confirmationToken == null ? "" : confirmationToken;
		heapDumpOutputPath = heapDumpOutputPath == null ? "" : heapDumpOutputPath.trim();
		boolean privileged = collectClassHistogram || collectThreadDump || includeHeapDump;
		if (privileged) {
			if (confirmationToken.isBlank()) {
				throw new IllegalArgumentException(
						"collectClassHistogram, collectThreadDump, and/or includeHeapDump require a non-blank confirmationToken");
			}
			MemoryGcEvidencePack pack = workflowService.collectEvidence(new MemoryGcEvidenceRequest(pid,
					collectClassHistogram, collectThreadDump, includeHeapDump, heapDumpOutputPath, confirmationToken));
			pack = attachOptionalEvidenceFields(pack, baselineEvidence, jfrSummary, repeatedSamplingResult,
					resourceBudgetEvidence);
			return workflowService.generateAdviceFromEvidence(pack, codeContextSummary, environment, optimizationGoal);
		}
		JvmRuntimeSnapshot snapshot = collector.collect(pid, RuntimeCollectionPolicy.CollectionRequest.safeReadonly());
		return workflowService.generateAdvice(new TuningAdviceRequest(snapshot, codeContextSummary, environment,
				optimizationGoal, null, baselineEvidence, jfrSummary, repeatedSamplingResult, resourceBudgetEvidence));
	}

	@Tool(description = """
			Generate structured tuning advice from an existing MemoryGcEvidencePack and code context. \
			中文：复用已有 MemoryGcEvidencePack 生成调优建议，不再次采集 jcmd/jstat、histogram、thread dump 或 heap dump。 \
			This is the advise-from-evidence path: it does not collect jcmd/jstat data, does not run GC.class_histogram, Thread.print, or GC.heap_dump, and does not require confirmationToken. \
			Use this after collectMemoryGcEvidence when the user already saw/approved the evidence pack, especially for class histogram, thread dump, or heap dump evidence. \
			Returns the same TuningAdviceReport JSON as generateTuningAdvice, including formattedSummary Markdown; hosts should render formattedSummary without an outer Markdown fence.""")
	public TuningAdviceReport generateTuningAdviceFromEvidence(
			@ToolParam(description = "Existing MemoryGcEvidencePack JSON, typically the exact collectMemoryGcEvidence response to reuse without recollection.") MemoryGcEvidencePack evidence,
			@ToolParam(description = "CodeContextSummary JSON: dependencies, configuration, applicationNames, sourceRoots, candidatePackages.") CodeContextSummary codeContextSummary,
			@ToolParam(description = "Deployment or runtime label, e.g. prod, stage, local.") String environment,
			@ToolParam(description = "What to optimize for: latency, throughput, footprint, diagnose leak, etc.") String optimizationGoal) {
		Objects.requireNonNull(evidence, "evidence must not be null");
		Objects.requireNonNull(codeContextSummary, "codeContextSummary must not be null");
		environment = environment == null ? "" : environment;
		optimizationGoal = optimizationGoal == null ? "" : optimizationGoal;
		return workflowService.generateAdviceFromEvidence(evidence, codeContextSummary, environment, optimizationGoal);
	}

	public TuningAdviceReport generateTuningAdvice(CodeContextSummary codeContextSummary, long pid, String environment,
			String optimizationGoal, boolean collectClassHistogram, boolean collectThreadDump, boolean includeHeapDump,
			String heapDumpOutputPath, String confirmationToken, MemoryGcEvidencePack baselineEvidence,
			JfrSummary jfrSummary) {
		return generateTuningAdvice(codeContextSummary, pid, environment, optimizationGoal, collectClassHistogram,
				collectThreadDump, includeHeapDump, heapDumpOutputPath, confirmationToken, baselineEvidence, jfrSummary,
				null, null);
	}

	private static MemoryGcEvidencePack attachOptionalEvidenceFields(MemoryGcEvidencePack pack,
			MemoryGcEvidencePack baselineEvidence, JfrSummary jfrSummary, RepeatedSamplingResult repeatedSamplingResult,
			ResourceBudgetEvidence resourceBudgetEvidence) {
		MemoryGcEvidencePack out = pack;
		if (baselineEvidence != null) {
			out = out.withBaselineEvidence(baselineEvidence);
		}
		if (jfrSummary != null) {
			out = out.withJfrSummary(jfrSummary);
		}
		if (repeatedSamplingResult != null) {
			out = out.withRepeatedSamplingResult(repeatedSamplingResult);
		}
		if (resourceBudgetEvidence != null) {
			out = out.withResourceBudgetEvidence(resourceBudgetEvidence);
		}
		return out;
	}

}
