package com.alibaba.cloud.ai.examples.javatuning.offline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfflineArtifactSourceJsonTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void deserializesArtifactSourceObjectFromFilePath() throws Exception {
		OfflineArtifactSource source = mapper.readValue("""
				{
				  "filePath": "C:/diag/b4-class-histogram.txt"
				}
				""", OfflineArtifactSource.class);

		assertThat(source.filePath()).isEqualTo("C:/diag/b4-class-histogram.txt");
		assertThat(source.inlineText()).isNull();
	}

	@Test
	void rejectsBareStringWithActionableMessage() {
		assertThatThrownBy(() -> mapper.readValue("\"C:/diag/b4-class-histogram.txt\"", OfflineArtifactSource.class))
			.hasMessageContaining("OfflineArtifactSource must be an object with filePath or inlineText")
			.hasMessageContaining("bare string is not allowed");
	}

}
