package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

@JsonClassDescription("""
		Requests one short Java Flight Recorder recording for a target JVM. \
		Requires a non-blank confirmationToken and an absolute jfrOutputPath ending in .jfr.""")
public record JfrRecordingRequest(
		@JsonPropertyDescription("Target JVM process id (decimal); should match a PID from listJavaApps.") long pid,
		@JsonPropertyDescription("Recording duration in seconds; null uses the server default, usually 30.") Integer durationSeconds,
		@JsonPropertyDescription("JFR settings template: default or profile. Blank uses profile.") String settings,
		@JsonPropertyDescription("Absolute output path for the recording file; must end in .jfr and must not already exist.") String jfrOutputPath,
		@JsonPropertyDescription("Maximum number of JFR events to parse for the summary; null uses the server default.") Integer maxSummaryEvents,
		@JsonPropertyDescription("Non-blank caller-provided approval token required for JFR recording.") String confirmationToken) {

	public JfrRecordingRequest normalized(JfrRecordingProperties properties) {
		if (properties == null) {
			properties = JfrRecordingProperties.defaults();
		}
		if (pid <= 0L) {
			throw new IllegalArgumentException("pid must be positive");
		}
		String token = confirmationToken == null ? "" : confirmationToken.trim();
		if (token.isBlank()) {
			throw new IllegalArgumentException("confirmationToken is required for JFR recording");
		}
		int duration = durationSeconds == null ? properties.defaultDurationSeconds() : durationSeconds;
		if (duration < properties.minDurationSeconds() || duration > properties.maxDurationSeconds()) {
			throw new IllegalArgumentException("durationSeconds must be between " + properties.minDurationSeconds()
					+ " and " + properties.maxDurationSeconds());
		}
		String normalizedSettings = settings == null || settings.isBlank() ? "profile" : settings.trim();
		if (!"default".equals(normalizedSettings) && !"profile".equals(normalizedSettings)) {
			throw new IllegalArgumentException("settings must be default or profile");
		}
		if (jfrOutputPath == null || jfrOutputPath.isBlank()) {
			throw new IllegalArgumentException("jfrOutputPath is required");
		}
		Path output = Path.of(jfrOutputPath.trim()).toAbsolutePath().normalize();
		if (!Path.of(jfrOutputPath.trim()).isAbsolute()) {
			throw new IllegalArgumentException("jfrOutputPath must be absolute");
		}
		if (!output.toString().toLowerCase().endsWith(".jfr")) {
			throw new IllegalArgumentException("jfrOutputPath must end in .jfr");
		}
		Path parent = output.getParent();
		if (parent == null || !Files.isDirectory(parent)) {
			throw new IllegalArgumentException("jfrOutputPath parent directory must already exist");
		}
		if (Files.exists(output)) {
			throw new IllegalArgumentException("jfrOutputPath already exists: " + output);
		}
		int maxEvents = maxSummaryEvents == null ? properties.defaultMaxSummaryEvents() : maxSummaryEvents;
		if (maxEvents <= 0) {
			throw new IllegalArgumentException("maxSummaryEvents must be positive");
		}
		return new JfrRecordingRequest(pid, duration, normalizedSettings, output.toString(), maxEvents, token);
	}
}
