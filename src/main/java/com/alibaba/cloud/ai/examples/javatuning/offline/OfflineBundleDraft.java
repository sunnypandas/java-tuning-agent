package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record OfflineBundleDraft(
		@JsonPropertyDescription("Required B1 text. JVM/process identity such as PID, command line, JVM flags, and collection timestamp.")
		String jvmIdentityText,
		@JsonPropertyDescription("Required B2 text. JDK vendor/version details from the exported environment.")
		String jdkInfoText,
		@JsonPropertyDescription("Required B3 text. Exported runtime snapshot content such as jcmd/jstat summaries.")
		String runtimeSnapshotText,
		@JsonPropertyDescription("Required B4 artifact source. Pass an object with filePath or inlineText; bare string is not allowed. Prefer filePath when the histogram already exists locally.")
		OfflineArtifactSource classHistogram,
		@JsonPropertyDescription("Required B5 artifact source. Pass an object with filePath or inlineText; bare string is not allowed. Prefer filePath when the thread dump already exists locally.")
		OfflineArtifactSource threadDump,
		@JsonPropertyDescription("Required B6 string path. Absolute or host-readable .hprof path; unlike classHistogram/threadDump this is a plain string, not an OfflineArtifactSource object.")
		String heapDumpAbsolutePath,
		boolean explicitlyNoGcLog,
		boolean explicitlyNoAppLog,
		boolean explicitlyNoRepeatedSamples,
		String gcLogPathOrText,
		String appLogPathOrText,
		String repeatedSamplesPathOrText,
		Map<String, String> backgroundNotes) {

	public static OfflineBundleDraft empty() {
		return new OfflineBundleDraft(null, null, null, null, null, null, false, false, false, null, null, null,
				Map.of());
	}

	public OfflineBundleDraft {
		classHistogram = classHistogram == null ? new OfflineArtifactSource(null, null) : classHistogram;
		threadDump = threadDump == null ? new OfflineArtifactSource(null, null) : threadDump;
		backgroundNotes = backgroundNotes == null ? Map.of() : Map.copyOf(backgroundNotes);
	}

}
