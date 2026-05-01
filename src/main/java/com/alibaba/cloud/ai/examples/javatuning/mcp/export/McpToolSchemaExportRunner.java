package com.alibaba.cloud.ai.examples.javatuning.mcp.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs only under profile {@code mcp-schema-export} (see {@code exec-maven-plugin} in {@code pom.xml}).
 * Writes tool names, descriptions, and parsed {@code inputSchema} JSON for integrators without source access.
 */
@Component
@Profile("mcp-schema-export")
public class McpToolSchemaExportRunner implements ApplicationRunner {

	private final ApplicationContext applicationContext;

	private final ToolCallbackProvider toolCallbackProvider;

	private final ObjectMapper objectMapper;

	private final String outputPath;

	private final String mcpServerName;

	private final String mcpServerVersion;

	public McpToolSchemaExportRunner(ApplicationContext applicationContext, ToolCallbackProvider toolCallbackProvider,
			ObjectMapper objectMapper,
			@Value("${java-tuning-agent.schema-export.output-path:target/mcp-tool-schemas.json}") String outputPath,
			@Value("${spring.ai.mcp.server.name:java-tuning-agent}") String mcpServerName,
			@Value("${spring.ai.mcp.server.version:unknown}") String mcpServerVersion) {
		this.applicationContext = applicationContext;
		this.toolCallbackProvider = toolCallbackProvider;
		this.objectMapper = objectMapper;
		this.outputPath = outputPath;
		this.mcpServerName = mcpServerName;
		this.mcpServerVersion = mcpServerVersion;
	}

	@Override
	public void run(ApplicationArguments args) throws IOException {
		Path path = Path.of(outputPath);
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		ArrayNode toolsNode = objectMapper.createArrayNode();
		for (var callback : toolCallbackProvider.getToolCallbacks()) {
			var def = callback.getToolDefinition();
			ObjectNode tool = objectMapper.createObjectNode();
			tool.put("name", def.name());
			tool.put("description", def.description());
			tool.set("inputSchema", clientSafeInputSchema(def.name(), def.inputSchema()));
			toolsNode.add(tool);
		}

		ObjectNode root = objectMapper.createObjectNode();
		root.put("mcpServerName", mcpServerName);
		root.put("mcpServerVersion", mcpServerVersion);
		root.set("tools", toolsNode);

		objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), root);
		// Forked JVM (exec:exec): shut down the context, then exit — agent/MCP may leave non-daemon threads.
		int code = SpringApplication.exit(applicationContext, () -> 0);
		System.exit(code);
	}

	private JsonNode clientSafeInputSchema(String toolName, String rawInputSchema) throws IOException {
		JsonNode schema = objectMapper.readTree(rawInputSchema);
		replaceRootRecursiveRefs(schema);
		if (schema instanceof ObjectNode objectSchema) {
			applyKnownOptionalFieldContracts(toolName, objectSchema);
		}
		return schema;
	}

	private void applyKnownOptionalFieldContracts(String toolName, ObjectNode schema) {
		switch (toolName) {
			case "collectMemoryGcEvidence" -> markOptional(property(schema, "request"), "includeClassHistogram",
					"includeThreadDump", "includeHeapDump", "heapDumpOutputPath", "confirmationToken");
			case "inspectJvmRuntimeRepeated" -> markOptional(property(schema, "request"), "sampleCount",
					"intervalMillis", "includeThreadCount", "includeClassCount", "confirmationToken");
			case "recordJvmFlightRecording" -> markOptional(property(schema, "request"), "durationSeconds", "settings",
					"maxSummaryEvents");
			case "generateTuningAdvice" -> {
				markOptional(schema, "collectClassHistogram", "collectThreadDump", "includeHeapDump",
						"heapDumpOutputPath", "confirmationToken", "baselineEvidence", "jfrSummary",
						"repeatedSamplingResult", "resourceBudgetEvidence");
				ObjectNode properties = schema.withObject("/properties");
				properties.set("baselineEvidence",
						nullableMemoryGcEvidencePackSchema("Optional prior MemoryGcEvidencePack for Key Deltas.", false));
				properties.set("jfrSummary", nullableObjectProperty(
						"Optional JfrSummary merged into diagnosis; use recordJvmFlightRecording summary shape."));
				properties.set("repeatedSamplingResult",
						nullableObjectProperty("Optional inspectJvmRuntimeRepeated output for trend-aware findings."));
				properties.set("resourceBudgetEvidence",
						nullableObjectProperty("Optional cgroup/RSS/CPU and heap/native/thread-stack budget evidence."));
			}
			case "generateTuningAdviceFromEvidence" -> {
				ObjectNode propertiesNode = schema.withObject("/properties");
				propertiesNode.set("evidence", memoryGcEvidencePackSchema(
						"Existing MemoryGcEvidencePack JSON, typically the exact collectMemoryGcEvidence response to reuse without recollection.",
						true));
				markOptional(schema, "repeatedSamplingResult", "jfrSummary", "resourceBudgetEvidence", "baselineEvidence");
				propertiesNode.set("baselineEvidence", nullableMemoryGcEvidencePackSchema(
						"Optional baseline MemoryGcEvidencePack for Key Deltas when omitted from merged evidence.pack fields.",
						false));
				propertiesNode.set("jfrSummary", nullableObjectProperty(
						"Optional JfrSummary to merge when evidence.jfrSummary is null (omit or null)."));
				propertiesNode.set("repeatedSamplingResult",
						nullableObjectProperty(
								"Optional inspectJvmRuntimeRepeated result to merge when evidence.repeatedSamplingResult is null (omit or null)."));
				propertiesNode.set("resourceBudgetEvidence", nullableObjectProperty(
						"Optional cgroup/RSS/CPU budget evidence to merge when evidence.resourceBudgetEvidence is null (omit or null)."));
			}
			case "validateOfflineAnalysisDraft" -> relaxOfflineDraft(property(schema, "draft"));
			case "submitOfflineHeapDumpChunk" -> markOptional(schema, "uploadId");
			case "generateOfflineTuningAdvice" -> {
				markOptional(schema, "codeContextSummary", "analysisDepth", "confirmationToken");
				relaxOfflineDraft(property(schema, "draft"));
			}
			case "summarizeOfflineHeapDumpFile" -> markOptional(schema, "topClassLimit", "maxOutputChars");
			case "analyzeOfflineHeapRetention" -> markOptional(schema, "topObjectLimit", "maxOutputChars",
					"analysisDepth", "focusTypes", "focusPackages");
			default -> {
			}
		}
	}

	private ObjectNode memoryGcEvidencePackSchema(String description, boolean requireSnapshot) {
		ObjectNode schema = objectMapper.createObjectNode();
		schema.put("type", "object");
		schema.put("description", description);
		ObjectNode properties = schema.putObject("properties");
		properties.set("snapshot", objectProperty("JvmRuntimeSnapshot captured in the evidence pack."));
		properties.set("classHistogram", nullableObjectProperty("Optional ClassHistogramSummary already collected."));
		properties.set("threadDump", nullableObjectProperty("Optional ThreadDumpSummary already collected."));
		properties.set("missingData", stringArrayProperty("Explicit evidence gaps."));
		properties.set("warnings", stringArrayProperty("Collection or import warnings."));
		properties.set("heapDumpPath",
				nullableStringProperty("Existing heap dump path from the evidence pack, if one was already produced."));
		properties.set("heapShallowSummary",
				nullableObjectProperty("Optional shallow heap summary already attached to the evidence pack."));
		properties.set("nativeMemorySummary",
				nullableObjectProperty("Optional native memory summary already attached to the evidence pack."));
		properties.set("heapRetentionAnalysis",
				nullableObjectProperty("Optional heap retention analysis already attached to the evidence pack."));
		properties.set("repeatedSamplingResult",
				nullableObjectProperty("Optional repeated sampling result already attached to the evidence pack."));
		properties.set("gcLogSummary",
				nullableObjectProperty("Optional GC log summary already attached to the evidence pack."));
		properties.set("jfrSummary", nullableObjectProperty("Optional JFR summary already attached to the evidence pack."));
		properties.set("baselineEvidence", nullableObjectProperty("Optional baseline MemoryGcEvidencePack for Key Deltas."));
		properties.set("diagnosisWindow", nullableObjectProperty("Optional diagnosis window metadata."));
		properties.set("resourceBudgetEvidence",
				nullableObjectProperty("Optional resource budget evidence already attached to the evidence pack."));
		if (requireSnapshot) {
			ArrayNode required = schema.putArray("required");
			required.add("snapshot");
		}
		schema.put("additionalProperties", true);
		return schema;
	}

	private ObjectNode nullableMemoryGcEvidencePackSchema(String description, boolean requireSnapshot) {
		ObjectNode schema = memoryGcEvidencePackSchema(description, requireSnapshot);
		allowNull(schema);
		return schema;
	}

	private ObjectNode objectProperty(String description) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "object");
		node.put("description", description);
		return node;
	}

	private ObjectNode nullableObjectProperty(String description) {
		ObjectNode node = objectProperty(description);
		allowNull(node);
		return node;
	}

	private ObjectNode nullableStringProperty(String description) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "string");
		node.put("description", description);
		allowNull(node);
		return node;
	}

	private ObjectNode stringArrayProperty(String description) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "array");
		node.put("description", description);
		ObjectNode items = node.putObject("items");
		items.put("type", "string");
		return node;
	}

	private void relaxOfflineDraft(JsonNode draft) {
		if (!(draft instanceof ObjectNode objectDraft)) {
			return;
		}
		objectDraft.remove("required");
		markOptional(objectDraft, "appLogPathOrText", "backgroundNotes", "classHistogram", "directBufferEvidence",
				"explicitlyNoAppLog", "explicitlyNoGcLog", "explicitlyNoRepeatedSamples", "gcLogPathOrText",
				"heapDumpAbsolutePath", "jdkInfoText", "jvmIdentityText", "metaspaceEvidence",
				"nativeMemorySummary", "repeatedSamplesPathOrText", "runtimeSnapshotText", "threadDump");
		ObjectNode properties = (ObjectNode) objectDraft.path("properties");
		inlineOfflineArtifactSource(properties, "classHistogram");
		inlineOfflineArtifactSource(properties, "threadDump");
		inlineOfflineArtifactSource(properties, "nativeMemorySummary");
		inlineOfflineArtifactSource(properties, "directBufferEvidence");
		inlineOfflineArtifactSource(properties, "metaspaceEvidence");
		objectDraft.remove("$defs");
	}

	private void inlineOfflineArtifactSource(ObjectNode properties, String fieldName) {
		JsonNode current = properties.path(fieldName);
		if (!(current instanceof ObjectNode currentObject)) {
			return;
		}
		String description = currentObject.path("description").asText();
		properties.set(fieldName, offlineArtifactSourceProperty(description));
	}

	private ObjectNode offlineArtifactSourceProperty(String description) {
		ObjectNode source = objectMapper.createObjectNode();
		source.put("type", "object");
		allowNull(source);
		if (description != null && !description.isBlank()) {
			source.put("description", description);
		}
		ObjectNode properties = source.putObject("properties");
		properties.set("filePath", nullableStringProperty(
				"Absolute or host-readable artifact path. Prefer this when the file already exists locally."));
		properties.set("inlineText",
				nullableStringProperty("Inline artifact text. Use this only when a file path is unavailable or impractical."));
		source.put("additionalProperties", false);
		return source;
	}

	private static ObjectNode property(ObjectNode schema, String name) {
		JsonNode property = schema.path("properties").path(name);
		return property instanceof ObjectNode objectProperty ? objectProperty : null;
	}

	private void markOptional(ObjectNode schema, String... propertyNames) {
		if (schema == null) {
			return;
		}
		removeRequired(schema, propertyNames);
		JsonNode properties = schema.path("properties");
		for (String name : propertyNames) {
			JsonNode property = properties.path(name);
			if (property instanceof ObjectNode objectProperty) {
				allowNull(objectProperty);
			}
		}
	}

	private void removeRequired(ObjectNode schema, String... propertyNames) {
		JsonNode required = schema.path("required");
		if (!required.isArray()) {
			return;
		}
		Set<String> optional = Set.copyOf(Arrays.asList(propertyNames));
		ArrayNode filtered = objectMapper.createArrayNode();
		for (JsonNode field : required) {
			if (!optional.contains(field.asText())) {
				filtered.add(field.asText());
			}
		}
		schema.set("required", filtered);
	}

	private void allowNull(ObjectNode node) {
		JsonNode type = node.get("type");
		if (type == null) {
			return;
		}
		if (type.isTextual()) {
			ArrayNode nullable = objectMapper.createArrayNode();
			nullable.add(type.asText());
			nullable.add("null");
			node.set("type", nullable);
		}
		else if (type.isArray() && !containsText(type, "null")) {
			((ArrayNode) type).add("null");
		}
	}

	private static boolean containsText(JsonNode array, String value) {
		for (JsonNode item : array) {
			if (value.equals(item.asText())) {
				return true;
			}
		}
		return false;
	}

	private void replaceRootRecursiveRefs(JsonNode node) {
		if (node == null || node.isMissingNode()) {
			return;
		}
		if (node instanceof ObjectNode objectNode) {
			JsonNode ref = objectNode.get("$ref");
			if (ref != null && ref.isTextual() && "#".equals(ref.asText())) {
				objectNode.remove("$ref");
				objectNode.put("type", "object");
				objectNode.put("additionalProperties", true);
				if (!objectNode.has("description")) {
					objectNode.put("description", "Recursive object; provide the same JSON shape as the surrounding evidence object.");
				}
				return;
			}
			var fields = objectNode.fields();
			while (fields.hasNext()) {
				replaceRootRecursiveRefs(fields.next().getValue());
			}
		}
		else if (node.isArray()) {
			for (JsonNode child : node) {
				replaceRootRecursiveRefs(child);
			}
		}
	}

}
