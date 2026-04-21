package com.alibaba.cloud.ai.examples.javatuning.runtime;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("One holder row explaining what keeps objects alive.")
public record SuspectedHolderSummary(
		@JsonPropertyDescription("Holder type or class name.") String holderType,
		@JsonPropertyDescription("Normalized role of the holder such as collection, map, or thread-local.") String holderRole,
		@JsonPropertyDescription("Approximate retained bytes when a retained-style engine can justify the value; null otherwise.") Long retainedBytesApprox,
		@JsonPropertyDescription("Approximate size of the reachable subgraph; not equivalent to retained size.") long reachableSubgraphBytesApprox,
		@JsonPropertyDescription("Approximate object count retained by this holder row.") long retainedObjectCountApprox,
		@JsonPropertyDescription("Example field path showing how the holder reaches the retained objects.") String exampleFieldPath,
		@JsonPropertyDescription("Example target type reached by the holder.") String exampleTargetType,
		@JsonPropertyDescription("Additional notes or caveats for the holder row.") String notes) {

	public SuspectedHolderSummary {
		holderType = holderType == null ? "" : holderType;
		holderRole = holderRole == null ? "" : holderRole;
		exampleFieldPath = exampleFieldPath == null ? "" : exampleFieldPath;
		exampleTargetType = exampleTargetType == null ? "" : exampleTargetType;
		notes = notes == null ? "" : notes;
	}
}
