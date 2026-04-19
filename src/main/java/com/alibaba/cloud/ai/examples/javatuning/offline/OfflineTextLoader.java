package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OfflineTextLoader {

	private OfflineTextLoader() {
	}

	/**
	 * Loads text from {@link OfflineArtifactSource}: file path (UTF-8) when set, otherwise inline
	 * text. When both are blank, returns an empty string.
	 */
	public static String load(OfflineArtifactSource src) throws IOException {
		if (src == null) {
			return "";
		}
		if (src.filePath() != null && !src.filePath().isBlank()) {
			return Files.readString(Path.of(src.filePath()), StandardCharsets.UTF_8);
		}
		if (src.inlineText() != null && !src.inlineText().isBlank()) {
			return src.inlineText();
		}
		return "";
	}

}
