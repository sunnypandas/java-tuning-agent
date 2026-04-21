package com.alibaba.cloud.ai.examples.javatuning.offline;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record OfflineArtifactSource(
		@JsonPropertyDescription("Absolute or host-readable artifact path. Prefer this when the file already exists locally.")
		String filePath,
		@JsonPropertyDescription("Inline artifact text. Use this only when a file path is unavailable or impractical.")
		String inlineText) {

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public static OfflineArtifactSource fromJson(JsonNode node) {
		if (node == null || node.isNull()) {
			return new OfflineArtifactSource(null, null);
		}
		if (node.isTextual()) {
			throw new IllegalArgumentException(
					"OfflineArtifactSource must be an object with filePath or inlineText; bare string is not allowed.");
		}
		if (!node.isObject()) {
			throw new IllegalArgumentException(
					"OfflineArtifactSource must be an object with filePath or inlineText.");
		}
		return new OfflineArtifactSource(readOptionalText(node, "filePath"), readOptionalText(node, "inlineText"));
	}

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

	private static String readOptionalText(JsonNode node, String fieldName) {
		JsonNode value = node.get(fieldName);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isTextual()) {
			throw new IllegalArgumentException("OfflineArtifactSource." + fieldName + " must be a string when present.");
		}
		return value.asText();
	}

}
