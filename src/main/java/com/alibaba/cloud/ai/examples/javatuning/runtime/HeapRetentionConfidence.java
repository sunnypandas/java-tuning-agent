package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Confidence, limitations, and engine notes for retention evidence.")
public record HeapRetentionConfidence(
		@JsonPropertyDescription("Confidence label such as low, medium, or high.") String confidence,
		@JsonPropertyDescription("Limitations or caveats that reduce certainty.") List<String> limitations,
		@JsonPropertyDescription("Implementation or engine notes that explain the evidence path.") List<String> engineNotes) {

	public HeapRetentionConfidence {
		confidence = confidence == null ? "low" : confidence;
		limitations = limitations == null ? List.of() : List.copyOf(limitations);
		engineNotes = engineNotes == null ? List.of() : List.copyOf(engineNotes);
	}

	public static HeapRetentionConfidence empty() {
		return new HeapRetentionConfidence(null, null, null);
	}
}
