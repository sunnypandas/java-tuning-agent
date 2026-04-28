package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class RepeatedSamplingResultParser {

	private final ObjectMapper mapper = new ObjectMapper();

	public RepeatedSamplingResult parse(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		try {
			JsonNode root = mapper.readTree(text);
			JsonNode samplesNode = root.path("samples");
			if (!samplesNode.isArray()) {
				return null;
			}
			List<RepeatedRuntimeSample> samples = new ArrayList<>();
			for (JsonNode sampleNode : samplesNode) {
				RepeatedRuntimeSample sample = parseSample(sampleNode);
				if (sample != null) {
					samples.add(sample);
				}
			}
			List<String> warnings = readStringList(root.path("warnings"));
			List<String> missingData = readStringList(root.path("missingData"));
			return new RepeatedSamplingResult(root.path("pid").asLong(0L), samples, warnings, missingData,
					root.path("startedAtEpochMs").asLong(0L), root.path("elapsedMs").asLong(0L));
		}
		catch (RuntimeException ex) {
			return null;
		}
		catch (Exception ex) {
			return null;
		}
	}

	private static RepeatedRuntimeSample parseSample(JsonNode node) {
		JvmMemorySnapshot memory = parseMemory(node.path("memory"));
		JvmGcSnapshot gc = parseGc(node.path("gc"));
		if (memory == null || gc == null) {
			return null;
		}
		return new RepeatedRuntimeSample(node.path("sampledAtEpochMs").asLong(0L), memory, gc,
				nullableLong(node.path("threadCount")), nullableLong(node.path("loadedClassCount")),
				readStringList(node.path("warnings")));
	}

	private static JvmMemorySnapshot parseMemory(JsonNode node) {
		if (node == null || !node.isObject()) {
			return null;
		}
		return new JvmMemorySnapshot(node.path("heapUsedBytes").asLong(0L),
				node.path("heapCommittedBytes").asLong(0L), node.path("heapMaxBytes").asLong(0L),
				nullableLong(node.path("oldGenUsedBytes")), nullableLong(node.path("oldGenCommittedBytes")),
				nullableLong(node.path("metaspaceUsedBytes")), nullableLong(node.path("xmsBytes")),
				nullableLong(node.path("xmxBytes")));
	}

	private static JvmGcSnapshot parseGc(JsonNode node) {
		if (node == null || !node.isObject()) {
			return null;
		}
		return new JvmGcSnapshot(node.path("collector").asText("unknown"), node.path("youngGcCount").asLong(0L),
				node.path("youngGcTimeMs").asLong(0L), node.path("fullGcCount").asLong(0L),
				node.path("fullGcTimeMs").asLong(0L), nullableDouble(node.path("oldUsagePercent")));
	}

	private static Long nullableLong(JsonNode node) {
		return node == null || node.isMissingNode() || node.isNull() ? null : node.asLong();
	}

	private static Double nullableDouble(JsonNode node) {
		return node == null || node.isMissingNode() || node.isNull() ? null : node.asDouble();
	}

	private static List<String> readStringList(JsonNode node) {
		if (node == null || !node.isArray()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (JsonNode item : node) {
			if (item != null && item.isTextual()) {
				values.add(item.asText());
			}
		}
		return values;
	}
}
