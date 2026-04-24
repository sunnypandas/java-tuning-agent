package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.agent.JavaTuningWorkflowService;
import com.alibaba.cloud.ai.examples.javatuning.discovery.JavaApplicationDescriptor;
import com.alibaba.cloud.ai.examples.javatuning.discovery.JavaProcessDiscoveryService;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrRecordingRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrRecordingResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidenceRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingRequest;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingResult;
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
			No parameters. Use returned pid values with inspectJvmRuntime / collectMemoryGcEvidence / generateTuningAdvice.""")
	public List<JavaApplicationDescriptor> listJavaApps() {
		return discoveryService.listJavaApplications();
	}

	@Tool(description = """
			Collect a safe read-only JVM runtime snapshot for the given PID using jcmd/jstat only (no histogram, thread dump, or heap dump). \
			Example arguments JSON: {"pid": 12345}""")
	public JvmRuntimeSnapshot inspectJvmRuntime(
			@ToolParam(description = "Target JVM process id (decimal); must match a pid from listJavaApps.") long pid) {
		return collector.collect(pid, RuntimeCollectionPolicy.CollectionRequest.safeReadonly());
	}

	@Tool(description = """
			Collect repeated safe read-only JVM runtime samples for a PID using bounded jcmd/jstat commands. \
			This P0 repeated mode does not collect class histograms, thread dumps, heap dumps, or JFR, and does not require confirmationToken. \
			Example arguments JSON: {"request":{"pid":12345,"sampleCount":3,"intervalMillis":10000,"includeThreadCount":true,"includeClassCount":true,"confirmationToken":""}}""")
	public RepeatedSamplingResult inspectJvmRuntimeRepeated(
			@ToolParam(description = "RepeatedSamplingRequest JSON: pid, sampleCount, intervalMillis, includeThreadCount, includeClassCount.") RepeatedSamplingRequest request) {
		return collector.collectRepeated(request);
	}

	@Tool(description = """
			Record one short Java Flight Recorder session for a target JVM and return the .jfr path plus a lightweight summary. \
			Requires a non-blank confirmationToken and an absolute jfrOutputPath ending in .jfr. \
			Example: {"request":{"pid":12345,"durationSeconds":30,"settings":"profile","jfrOutputPath":"C:/tmp/app.jfr","maxSummaryEvents":200000,"confirmationToken":"user-approved"}}""")
	public JfrRecordingResult recordJvmFlightRecording(
			@ToolParam(description = "JfrRecordingRequest JSON: pid, durationSeconds, settings, jfrOutputPath, maxSummaryEvents, confirmationToken.") JfrRecordingRequest request) {
		return collector.recordJfr(request);
	}

	@Tool(description = """
			Collect medium-cost memory/GC evidence: optional class histogram, thread dump, and/or heap dump (jcmd GC.heap_dump). \
			Privileged options require a non-blank confirmationToken; heap dump also requires heapDumpOutputPath (absolute .hprof path). \
			Example: {"request":{"pid":12345,"includeClassHistogram":true,"includeThreadDump":false,"includeHeapDump":false,"heapDumpOutputPath":"","confirmationToken":"user-approved"}}""")
	public MemoryGcEvidencePack collectMemoryGcEvidence(
			@ToolParam(description = "Evidence selection and privileged-field policy; see MemoryGcEvidenceRequest JSON shape.") MemoryGcEvidenceRequest request) {
		return workflowService.collectEvidence(request);
	}

	@Tool(description = """
			Generate structured tuning advice from runtime data and code context. \
			Returns TuningAdviceReport JSON: findings, recommendations, suspectedCodeHotspots, missingData, nextSteps, confidence, confidenceReasons, and formattedSummary. \
			The formattedSummary field is stable Markdown (same sections in a fixed order) for user-facing display—hosts should prefer showing it so suspectedCodeHotspots are not dropped. \
			By default collects a lightweight jcmd/jstat snapshot only (all three collect* flags false, confirmationToken may be blank). \
			Set collectClassHistogram=true and non-blank confirmationToken to run GC.class_histogram for retention signals and source file hints when sourceRoots are set. \
			Set collectThreadDump=true and/or includeHeapDump=true (plus absolute .hprof heapDumpOutputPath) for deeper diagnostics; confirmationToken is required when any privileged flag is true. \
			Example (lightweight): {"codeContextSummary":{"dependencies":[],"configuration":{},"applicationNames":[],"sourceRoots":[],"candidatePackages":[]},"pid":12345,"environment":"local","optimizationGoal":"reduce GC pause","collectClassHistogram":false,"collectThreadDump":false,"includeHeapDump":false,"heapDumpOutputPath":"","confirmationToken":""} \
			Example (histogram): same as above but collectClassHistogram=true and confirmationToken non-blank.""")
	public TuningAdviceReport generateTuningAdvice(
			@ToolParam(description = "CodeContextSummary JSON: dependencies, configuration, applicationNames, sourceRoots, candidatePackages.") CodeContextSummary codeContextSummary,
			@ToolParam(description = "Target JVM process id (decimal); must match listJavaApps.") long pid,
			@ToolParam(description = "Deployment or runtime label, e.g. prod, stage, local.") String environment,
			@ToolParam(description = "What to optimize for: latency, throughput, footprint, diagnose leak, etc.") String optimizationGoal,
			@ToolParam(description = "When true, runs GC.class_histogram before advice (requires non-blank confirmationToken).") boolean collectClassHistogram,
			@ToolParam(description = "When true, runs Thread.print before advice (requires non-blank confirmationToken).") boolean collectThreadDump,
			@ToolParam(description = "When true, runs GC.heap_dump to heapDumpOutputPath (requires non-blank confirmationToken and absolute .hprof path).") boolean includeHeapDump,
			@ToolParam(description = "Absolute .hprof output path when includeHeapDump is true; otherwise \"\".") String heapDumpOutputPath,
			@ToolParam(description = "Non-blank user approval token whenever any of collectClassHistogram, collectThreadDump, or includeHeapDump is true.") String confirmationToken) {
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
			return workflowService.generateAdviceFromEvidence(pack, codeContextSummary, environment, optimizationGoal);
		}
		JvmRuntimeSnapshot snapshot = collector.collect(pid, RuntimeCollectionPolicy.CollectionRequest.safeReadonly());
		MemoryGcEvidencePack light = new MemoryGcEvidencePack(snapshot, null, null, List.of(), List.of(), null, null);
		return workflowService.generateAdviceFromEvidence(light, codeContextSummary, environment, optimizationGoal);
	}

}
