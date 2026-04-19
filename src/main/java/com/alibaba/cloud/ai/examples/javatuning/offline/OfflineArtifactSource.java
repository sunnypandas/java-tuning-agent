package com.alibaba.cloud.ai.examples.javatuning.offline;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record OfflineArtifactSource(String filePath, String inlineText) {

	public OfflineArtifactSource {
		boolean hasPath = filePath != null && !filePath.isBlank();
		boolean hasInline = inlineText != null && !inlineText.isBlank();
		if (hasPath && hasInline) {
			throw new IllegalArgumentException("Specify only one of filePath or inlineText");
		}
	}

	public boolean isPresent() {
		return filePath != null && !filePath.isBlank() || inlineText != null && !inlineText.isBlank();
	}

}
