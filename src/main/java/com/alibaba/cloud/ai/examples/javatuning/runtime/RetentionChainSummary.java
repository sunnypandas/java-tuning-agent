package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("One representative retention chain template.")
public record RetentionChainSummary(
		@JsonPropertyDescription("GC-root kind or holder category that anchors the chain.") String rootKind,
		@JsonPropertyDescription("Collapsed chain segments that explain the reference path.") List<RetentionChainSegment> segments,
		@JsonPropertyDescription("Terminal retained type reached by the chain.") String terminalType,
		@JsonPropertyDescription("Shallow bytes for the terminal object or array.") long terminalShallowBytes,
		@JsonPropertyDescription("Approximate number of equivalent chains represented by this template.") long chainCountApprox,
		@JsonPropertyDescription("Approximate retained bytes when defensible; null otherwise.") Long retainedBytesApprox,
		@JsonPropertyDescription("Approximate size of the reachable subgraph; not equivalent to retained size.") long reachableSubgraphBytesApprox) {

	public RetentionChainSummary {
		rootKind = rootKind == null ? "" : rootKind;
		segments = segments == null ? List.of() : List.copyOf(segments);
		terminalType = terminalType == null ? "" : terminalType;
	}
}
