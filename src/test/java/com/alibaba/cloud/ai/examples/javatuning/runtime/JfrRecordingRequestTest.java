package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JfrRecordingRequestTest {

	private static final JfrRecordingProperties PROPS = JfrRecordingProperties.defaults();

	@TempDir
	Path tempDir;

	@Test
	void shouldNormalizeBlankOptionalFieldsToDefaults() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();
		JfrRecordingRequest normalized = new JfrRecordingRequest(123L, null, " ", output.toString(), null,
				"confirmed").normalized(PROPS);

		assertThat(normalized.pid()).isEqualTo(123L);
		assertThat(normalized.durationSeconds()).isEqualTo(PROPS.defaultDurationSeconds());
		assertThat(normalized.settings()).isEqualTo("profile");
		assertThat(normalized.jfrOutputPath()).isEqualTo(output.toString());
		assertThat(normalized.maxSummaryEvents()).isEqualTo(PROPS.defaultMaxSummaryEvents());
		assertThat(normalized.confirmationToken()).isEqualTo("confirmed");
	}

	@Test
	void shouldAcceptDefaultSettings() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();
		JfrRecordingRequest normalized = new JfrRecordingRequest(123L, 10, "default", output.toString(), 20,
				"confirmed").normalized(PROPS);

		assertThat(normalized.settings()).isEqualTo("default");
		assertThat(normalized.durationSeconds()).isEqualTo(10);
		assertThat(normalized.maxSummaryEvents()).isEqualTo(20);
	}

	@Test
	void shouldRejectMissingConfirmationToken() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "profile", output.toString(), 20, " ")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confirmationToken");
	}

	@Test
	void shouldRejectInvalidPid() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(0L, 10, "profile", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("pid");
	}

	@Test
	void shouldRejectDurationOutsideBounds() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 4, "profile", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("durationSeconds");
		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 301, "profile", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("durationSeconds");
	}

	@Test
	void shouldRejectUnsupportedSettings() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "custom", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("settings");
	}

	@Test
	void shouldRejectNonAbsolutePath() {
		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "profile", "relative.jfr", 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("absolute");
	}

	@Test
	void shouldRejectNonJfrPath() {
		Path output = tempDir.resolve("recording.txt").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "profile", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(".jfr");
	}

	@Test
	void shouldRejectMissingParentDirectory() {
		Path output = tempDir.resolve("missing").resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "profile", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("parent");
	}

	@Test
	void shouldRejectExistingOutputFile() throws Exception {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();
		Files.writeString(output, "existing");

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "profile", output.toString(), 20, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("already exists");
	}

	@Test
	void shouldRejectInvalidMaxSummaryEvents() {
		Path output = tempDir.resolve("recording.jfr").toAbsolutePath().normalize();

		assertThatThrownBy(() -> new JfrRecordingRequest(123L, 10, "profile", output.toString(), 0, "confirmed")
			.normalized(PROPS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxSummaryEvents");
	}

	@Test
	void shouldRejectInvalidJfrRecordingProperties() {
		assertThatThrownBy(() -> new JfrRecordingProperties(0, 5, 300, 10_000L, 200_000, 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("defaultDurationSeconds");
		assertThatThrownBy(() -> new JfrRecordingProperties(30, 0, 300, 10_000L, 200_000, 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("minDurationSeconds");
		assertThatThrownBy(() -> new JfrRecordingProperties(30, 5, 4, 10_000L, 200_000, 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("maxDurationSeconds");
		assertThatThrownBy(() -> new JfrRecordingProperties(4, 5, 300, 10_000L, 200_000, 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("defaultDurationSeconds");
		assertThat(new JfrRecordingProperties(30, 5, 300, 0L, 200_000, 10).completionGraceMs()).isZero();
		assertThatThrownBy(() -> new JfrRecordingProperties(30, 5, 300, -1L, 200_000, 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("completionGraceMs");
		assertThatThrownBy(() -> new JfrRecordingProperties(30, 5, 300, 10_000L, 0, 10))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("defaultMaxSummaryEvents");
		assertThatThrownBy(() -> new JfrRecordingProperties(30, 5, 300, 10_000L, 200_000, 0))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("topLimit");
	}
}
