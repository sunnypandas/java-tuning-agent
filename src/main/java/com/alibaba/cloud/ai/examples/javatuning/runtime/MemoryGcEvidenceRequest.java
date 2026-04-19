package com.alibaba.cloud.ai.examples.javatuning.runtime;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
		Selects optional jcmd evidence to attach to the JVM runtime snapshot. \
		Any of includeClassHistogram, includeThreadDump, or includeHeapDump requires a non-blank confirmationToken. \
		includeHeapDump also requires heapDumpOutputPath as an absolute path ending in .hprof.""")
public record MemoryGcEvidenceRequest(
		@JsonPropertyDescription("Target JVM process id (decimal); must match a PID from listJavaApps.") long pid,
		@JsonPropertyDescription("Run jcmd GC.class_histogram (pause/CPU cost; needs confirmationToken).") boolean includeClassHistogram,
		@JsonPropertyDescription("Run jcmd Thread.print (needs confirmationToken).") boolean includeThreadDump,
		@JsonPropertyDescription("Run jcmd GC.heap_dump to heapDumpOutputPath (needs confirmationToken and absolute .hprof path).") boolean includeHeapDump,
		@JsonPropertyDescription("Absolute filesystem path for the heap dump file, e.g. C:/tmp/app.hprof; required when includeHeapDump is true.") String heapDumpOutputPath,
		@JsonPropertyDescription("Non-blank caller-provided approval token whenever any privileged include* flag is true.") String confirmationToken) {

	public MemoryGcEvidenceRequest {
		heapDumpOutputPath = heapDumpOutputPath == null ? "" : heapDumpOutputPath.trim();
	}
}
