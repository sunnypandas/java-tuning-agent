package com.alibaba.cloud.ai.examples.javatuning.runtime;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("A hint about a nearby GC root kind.")
public record GcRootHint(
		@JsonPropertyDescription("GC-root kind such as system-class, thread-object, or jni-global.") String rootKind,
		@JsonPropertyDescription("Example owner type near the hinted root.") String exampleOwnerType,
		@JsonPropertyDescription("Approximate number of occurrences for this hint.") long occurrenceCountApprox,
		@JsonPropertyDescription("Additional context or caveats for the hint.") String notes) {

	public GcRootHint {
		rootKind = rootKind == null ? "" : rootKind;
		exampleOwnerType = exampleOwnerType == null ? "" : exampleOwnerType;
		notes = notes == null ? "" : notes;
	}
}
