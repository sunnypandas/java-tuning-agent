package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record OfflineBundleDraft(
		String jvmIdentityText,
		String jdkInfoText,
		String runtimeSnapshotText,
		OfflineArtifactSource classHistogram,
		OfflineArtifactSource threadDump,
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
