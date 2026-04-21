package com.alibaba.cloud.ai.examples.javatuning.runtime;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("One retained-type row in the retention summary.")
public record RetainedTypeSummary(
		@JsonPropertyDescription("Retained type or class name being summarized.") String typeName,
		@JsonPropertyDescription("Approximate retained bytes when defensible; null otherwise.") Long retainedBytesApprox,
		@JsonPropertyDescription("Approximate object count associated with this type.") long objectCountApprox,
		@JsonPropertyDescription("Share of tracked retained bytes, expressed as a percentage from 0 to 100.") double shareOfTrackedRetainedApprox,
		@JsonPropertyDescription("Shallow bytes for the terminal objects used as the stable fallback signal.") long terminalShallowBytes) {

	public RetainedTypeSummary {
		typeName = typeName == null ? "" : typeName;
	}
}
