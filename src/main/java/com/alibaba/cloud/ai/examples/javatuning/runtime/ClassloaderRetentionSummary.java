package com.alibaba.cloud.ai.examples.javatuning.runtime;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("Classloader-level retained-style heap hint derived from local hprof analysis.")
public record ClassloaderRetentionSummary(
		@JsonPropertyDescription("Classloader class name, bootstrap, or a stable fallback label.") String classLoaderName,
		@JsonPropertyDescription("Classloader object id from the heap dump when available; null for bootstrap or unknown loaders.") Long classLoaderObjectId,
		@JsonPropertyDescription("Approximate reachable subgraph bytes for candidate objects associated with this classloader.") long reachableSubgraphBytesApprox,
		@JsonPropertyDescription("Approximate candidate object count associated with this classloader.") long objectCountApprox,
		@JsonPropertyDescription("Approximate retained bytes when a retained-style engine can justify the value; null otherwise.") Long retainedBytesApprox,
		@JsonPropertyDescription("Terminal shallow bytes for candidate objects associated with this classloader.") long terminalShallowBytes,
		@JsonPropertyDescription("Example holder type reaching one of the classloader-owned candidates.") String exampleHolderType,
		@JsonPropertyDescription("Example holder field path reaching one of the classloader-owned candidates.") String exampleFieldPath,
		@JsonPropertyDescription("Example target type associated with this classloader.") String exampleTargetType,
		@JsonPropertyDescription("Caveats for this classloader-level retained-style row.") String notes) {

	public ClassloaderRetentionSummary {
		classLoaderName = classLoaderName == null ? "" : classLoaderName;
		exampleHolderType = exampleHolderType == null ? "" : exampleHolderType;
		exampleFieldPath = exampleFieldPath == null ? "" : exampleFieldPath;
		exampleTargetType = exampleTargetType == null ? "" : exampleTargetType;
		notes = notes == null ? "" : notes;
	}
}
