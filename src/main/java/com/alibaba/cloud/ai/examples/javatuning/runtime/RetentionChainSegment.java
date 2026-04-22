package com.alibaba.cloud.ai.examples.javatuning.runtime;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("One collapsed step in a retention chain.")
public record RetentionChainSegment(
		@JsonPropertyDescription("Type of the owning object or container.") String ownerType,
		@JsonPropertyDescription("Kind of reference such as field, array-slot, or local variable.") String referenceKind,
		@JsonPropertyDescription("Reference name or slot label for the segment.") String referenceName,
		@JsonPropertyDescription("Type reached by following the segment.") String targetType) {

	public RetentionChainSegment {
		ownerType = ownerType == null ? "" : ownerType;
		referenceKind = referenceKind == null ? "" : referenceKind;
		referenceName = referenceName == null ? "" : referenceName;
		targetType = targetType == null ? "" : targetType;
	}
}
