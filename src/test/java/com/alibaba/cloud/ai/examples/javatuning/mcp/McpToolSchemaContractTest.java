package com.alibaba.cloud.ai.examples.javatuning.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ensures MCP clients can call tools using only {@link org.springframework.ai.tool.definition.ToolDefinition}
 * metadata (description + inputSchema), without reading this repository's source.
 */
class McpToolSchemaContractTest {

	private final ToolCallbackProvider toolCallbackProvider = McpToolCallbackProviderTestSupport.create();

	private final ObjectMapper mapper = new ObjectMapper();

	private static final Path PUBLISHED_SCHEMA_DIR = Path.of("mcps/user-java-tuning-agent/tools");

	@Test
	void everyRegisteredToolExposesParsableInputSchemaWithExpectedShape() throws Exception {
		for (var callback : toolCallbackProvider.getToolCallbacks()) {
			var def = callback.getToolDefinition();
			String raw = def.inputSchema();
			assertThat(raw).as("inputSchema for " + def.name()).isNotBlank();
			JsonNode schema = mapper.readTree(raw);
			assertThat(schema.path("type").asText()).as("schema type for " + def.name()).isEqualTo("object");

			switch (def.name()) {
				case "listJavaApps" -> assertThat(schema.path("properties").fieldNames()).toIterable().isEmpty();
				case "inspectJvmRuntime" -> assertThat(schema.path("properties").path("pid").path("type").asText())
					.isIn("integer", "number");
				case "inspectJvmRuntimeRepeated" -> {
					JsonNode request = schema.path("properties").path("request");
					assertThat(request.path("type").asText()).isEqualTo("object");
					assertThat(request.path("properties").path("pid").path("type").asText()).isIn("integer", "number");
					assertThat(request.path("properties").path("sampleCount").path("type").asText())
						.isIn("integer", "number");
					assertThat(request.path("properties").path("intervalMillis").path("type").asText())
						.isIn("integer", "number");
					assertThat(request.path("properties").path("includeThreadCount").path("type").asText())
						.isEqualTo("boolean");
					assertThat(request.path("properties").path("includeClassCount").path("type").asText())
						.isEqualTo("boolean");
				}
				case "recordJvmFlightRecording" -> {
					JsonNode request = schema.path("properties").path("request");
					assertThat(request.path("type").asText()).isEqualTo("object");
					assertThat(request.path("properties").path("pid").path("type").asText()).isIn("integer", "number");
					assertThat(request.path("properties").path("durationSeconds").path("type").asText())
						.isIn("integer", "number");
					assertThat(request.path("properties").path("settings").path("type").asText()).isEqualTo("string");
					assertThat(request.path("properties").path("jfrOutputPath").path("type").asText())
						.isEqualTo("string");
					assertThat(request.path("properties").path("maxSummaryEvents").path("type").asText())
						.isIn("integer", "number");
					assertThat(request.path("properties").path("confirmationToken").path("type").asText())
						.isEqualTo("string");
					assertThat(def.description()).contains("Java Flight Recorder")
						.contains("confirmationToken")
						.contains(".jfr");
				}
				case "collectMemoryGcEvidence" -> {
					JsonNode req = schema.path("properties").path("request");
					assertThat(req.path("type").asText()).isEqualTo("object");
					assertThat(req.path("properties").path("pid").path("type").asText()).isIn("integer", "number");
					for (String flag : new String[] { "includeClassHistogram", "includeThreadDump", "includeHeapDump" }) {
						assertThat(req.path("properties").path(flag).path("type").asText()).isEqualTo("boolean");
					}
					assertThat(req.path("properties").path("heapDumpOutputPath").path("type").asText())
						.isEqualTo("string");
					assertThat(req.path("properties").path("confirmationToken").path("type").asText())
						.isEqualTo("string");
				}
				case "generateTuningAdvice" -> {
					JsonNode ctx = schema.path("properties").path("codeContextSummary");
					assertThat(ctx.path("type").asText()).isEqualTo("object");
					JsonNode roots = ctx.path("properties").path("sourceRoots");
					assertThat(roots.path("type").asText()).isEqualTo("array");
					assertThat(schema.path("properties").path("pid").path("type").asText()).isIn("integer", "number");
					assertThat(schema.path("properties").path("environment").path("type").asText()).isEqualTo("string");
					assertThat(schema.path("properties").path("optimizationGoal").path("type").asText())
						.isEqualTo("string");
					for (String flag : new String[] { "collectClassHistogram", "collectThreadDump", "includeHeapDump" }) {
						assertThat(schema.path("properties").path(flag).path("type").asText()).isEqualTo("boolean");
					}
					assertThat(schema.path("properties").path("heapDumpOutputPath").path("type").asText())
						.isEqualTo("string");
					assertThat(schema.path("properties").path("confirmationToken").path("type").asText())
						.isEqualTo("string");
					assertThat(schema.path("properties").path("baselineEvidence").path("type").asText())
						.isEqualTo("object");
					assertThat(schema.path("properties").path("jfrSummary").path("type").asText()).isEqualTo("object");
					assertThat(schema.path("properties").path("repeatedSamplingResult").path("type").asText())
						.isEqualTo("object");
					assertThat(schema.path("properties").path("resourceBudgetEvidence").path("type").asText())
						.isEqualTo("object");
				}
				case "generateTuningAdviceFromEvidence" -> {
					JsonNode properties = schema.path("properties");
					JsonNode evidence = schema.path("properties").path("evidence");
					assertThat(evidence.path("type").asText()).isEqualTo("object");
					assertThat(evidence.path("properties").path("snapshot").path("type").asText()).isEqualTo("object");
					assertThat(evidence.path("properties").path("classHistogram").path("type").asText())
						.isEqualTo("object");
					assertThat(evidence.path("properties").path("threadDump").path("type").asText())
						.isEqualTo("object");
					assertThat(evidence.path("properties").path("heapDumpPath").path("type").asText())
						.isEqualTo("string");
					JsonNode ctx = schema.path("properties").path("codeContextSummary");
					assertThat(ctx.path("type").asText()).isEqualTo("object");
					assertThat(ctx.path("properties").path("sourceRoots").path("type").asText()).isEqualTo("array");
					assertThat(schema.path("properties").path("environment").path("type").asText()).isEqualTo("string");
					assertThat(schema.path("properties").path("optimizationGoal").path("type").asText())
						.isEqualTo("string");
					assertThat(requiredFields(schema)).doesNotContain("repeatedSamplingResult", "jfrSummary",
							"resourceBudgetEvidence", "baselineEvidence");
					assertThat(properties.has("pid")).isFalse();
					assertThat(properties.has("collectClassHistogram")).isFalse();
					assertThat(properties.has("collectThreadDump")).isFalse();
					assertThat(properties.has("includeHeapDump")).isFalse();
					assertThat(properties.has("heapDumpOutputPath")).isFalse();
					assertThat(properties.has("confirmationToken")).isFalse();
					assertThat(def.description()).contains("existing MemoryGcEvidencePack")
						.contains("does not collect")
						.contains("formattedSummary");
				}
				case "validateOfflineAnalysisDraft" -> {
					JsonNode draft = schema.path("properties").path("draft");
					assertThat(draft.path("type").asText()).isEqualTo("object");
					JsonNode classHistogramProperty = draft.path("properties").path("classHistogram");
					JsonNode threadDumpProperty = draft.path("properties").path("threadDump");
					JsonNode classHistogram = resolveSchemaNode(draft, classHistogramProperty);
					JsonNode threadDump = resolveSchemaNode(draft, threadDumpProperty);
					assertThat(classHistogram.path("type").asText()).isEqualTo("object");
					assertThat(threadDump.path("type").asText()).isEqualTo("object");
					assertThat(draft.path("properties").path("heapDumpAbsolutePath").path("type").asText())
						.isEqualTo("string");
					assertThat(draft.path("properties").path("repeatedSamplesPathOrText").path("type").asText())
						.isEqualTo("string");
					assertThat(descriptionOf(classHistogramProperty, classHistogram))
						.contains("filePath", "inlineText");
					assertThat(descriptionOf(threadDumpProperty, threadDump))
						.contains("filePath", "inlineText");
					assertThat(schema.path("properties").path("proceedWithMissingRequired").path("type").asText())
						.isEqualTo("boolean");
				}
				case "submitOfflineHeapDumpChunk" -> {
					assertThat(schema.path("properties").path("uploadId").path("type").asText()).isEqualTo("string");
					assertThat(schema.path("properties").path("chunkIndex").path("type").asText()).isIn("integer",
							"number");
					assertThat(schema.path("properties").path("chunkTotal").path("type").asText()).isIn("integer",
							"number");
					assertThat(schema.path("properties").path("chunkBase64").path("type").asText()).isEqualTo("string");
				}
				case "finalizeOfflineHeapDump" -> {
					assertThat(schema.path("properties").path("uploadId").path("type").asText()).isEqualTo("string");
					assertThat(schema.path("properties").path("expectedSha256Hex").path("type").asText())
						.isEqualTo("string");
					assertThat(schema.path("properties").path("expectedSizeBytes").path("type").asText()).isIn(
							"integer", "number");
				}
				case "generateOfflineTuningAdvice" -> {
					JsonNode ctxOff = schema.path("properties").path("codeContextSummary");
					assertThat(ctxOff.path("type").asText()).isEqualTo("object");
					JsonNode draft = schema.path("properties").path("draft");
					assertThat(draft.path("type").asText()).isEqualTo("object");
					JsonNode classHistogramProperty = draft.path("properties").path("classHistogram");
					JsonNode threadDumpProperty = draft.path("properties").path("threadDump");
					JsonNode classHistogram = resolveSchemaNode(draft, classHistogramProperty);
					JsonNode threadDump = resolveSchemaNode(draft, threadDumpProperty);
					assertThat(classHistogram.path("type").asText()).isEqualTo("object");
					assertThat(threadDump.path("type").asText()).isEqualTo("object");
					assertThat(draft.path("properties").path("heapDumpAbsolutePath").path("type").asText())
						.isEqualTo("string");
					assertThat(draft.path("properties").path("repeatedSamplesPathOrText").path("type").asText())
						.isEqualTo("string");
					assertThat(descriptionOf(classHistogramProperty, classHistogram))
						.contains("filePath", "inlineText");
					assertThat(descriptionOf(threadDumpProperty, threadDump))
						.contains("filePath", "inlineText");
					assertThat(schema.path("properties").path("environment").path("type").asText()).isEqualTo("string");
					assertThat(schema.path("properties").path("optimizationGoal").path("type").asText())
						.isEqualTo("string");
					assertThat(schema.path("properties").path("analysisDepth").path("type").asText())
						.isEqualTo("string");
					assertThat(schema.path("properties").path("confirmationToken").path("type").asText())
						.isEqualTo("string");
					assertThat(schema.path("properties").path("proceedWithMissingRequired").path("type").asText())
						.isEqualTo("boolean");
				}
				case "summarizeOfflineHeapDumpFile" -> {
					assertThat(schema.path("properties").path("heapDumpAbsolutePath").path("type").asText())
						.isEqualTo("string");
					assertThat(schema.path("properties").path("topClassLimit").path("type").asText()).isIn("integer",
							"number");
					assertThat(schema.path("properties").path("maxOutputChars").path("type").asText()).isIn("integer",
							"number");
				}
				case "analyzeOfflineHeapRetention" -> {
					assertThat(schema.path("properties").path("heapDumpAbsolutePath").path("type").asText())
						.isEqualTo("string");
					assertThat(schema.path("properties").path("topObjectLimit").path("type").asText()).isIn("integer",
							"number");
					assertThat(schema.path("properties").path("maxOutputChars").path("type").asText()).isIn("integer",
							"number");
					assertThat(schema.path("properties").path("analysisDepth").path("type").asText())
						.isEqualTo("string");
					assertThat(schema.path("properties").path("focusTypes").path("type").asText()).isEqualTo("array");
					assertThat(schema.path("properties").path("focusPackages").path("type").asText())
						.isEqualTo("array");
					assertThat(def.description()).containsIgnoringCase("retention")
						.contains("reachableSubgraphBytesApprox")
						.containsIgnoringCase("shallow");
				}
				default -> throw new AssertionError("Unexpected tool: " + def.name());
			}
		}
	}

	@Test
	void publishedSchemaDirectoryShouldContainEveryRegisteredToolWithClientSafeSchemas() throws Exception {
		Set<String> registeredToolNames = registeredToolNames();
		Set<String> publishedToolNames = publishedToolNames();

		assertThat(publishedToolNames).containsExactlyInAnyOrderElementsOf(registeredToolNames);
		for (String toolName : registeredToolNames) {
			JsonNode tool = mapper.readTree(PUBLISHED_SCHEMA_DIR.resolve(toolName + ".json").toFile());
			assertThat(tool.path("name").asText()).isEqualTo(toolName);
			assertThat(tool.path("description").asText()).isNotBlank();
			JsonNode schema = tool.path("inputSchema");
			assertThat(schema.path("type").asText()).isEqualTo("object");
			assertThat(containsRootRecursiveRef(schema)).as("schema for " + toolName + " must not contain $ref: \"#\"")
				.isFalse();
			assertReferencesResolve(schema, schema, toolName);
			assertRequiredFieldsAreDeclared(schema, toolName);
		}

		JsonNode generateAdviceSchema = publishedInputSchema("generateTuningAdvice");
		assertThat(requiredFields(generateAdviceSchema)).doesNotContain("baselineEvidence", "jfrSummary",
				"repeatedSamplingResult", "resourceBudgetEvidence", "collectClassHistogram", "collectThreadDump",
				"includeHeapDump", "heapDumpOutputPath", "confirmationToken");
		assertThat(typeAllows(generateAdviceSchema.path("properties").path("baselineEvidence"), "null")).isTrue();
		assertThat(typeAllows(generateAdviceSchema.path("properties").path("jfrSummary"), "null")).isTrue();

		JsonNode fromEvidenceTool = mapper.readTree(PUBLISHED_SCHEMA_DIR.resolve("generateTuningAdviceFromEvidence.json")
			.toFile());
		JsonNode fromEvidenceSchema = fromEvidenceTool.path("inputSchema");
		JsonNode fromEvidenceProperties = fromEvidenceSchema.path("properties");
		assertThat(requiredFields(fromEvidenceSchema)).containsExactlyInAnyOrder("evidence", "codeContextSummary",
				"environment", "optimizationGoal");
		assertThat(fromEvidenceProperties.has("pid")).isFalse();
		assertThat(fromEvidenceProperties.has("collectClassHistogram")).isFalse();
		assertThat(fromEvidenceProperties.has("collectThreadDump")).isFalse();
		assertThat(fromEvidenceProperties.has("includeHeapDump")).isFalse();
		assertThat(fromEvidenceProperties.has("heapDumpOutputPath")).isFalse();
		assertThat(fromEvidenceProperties.has("confirmationToken")).isFalse();
		assertThat(fromEvidenceTool.path("description").asText()).contains("does not collect")
			.contains("does not require confirmationToken");
		assertThat(typeAllows(fromEvidenceProperties.path("baselineEvidence"), "null")).isTrue();
		assertThat(typeAllows(fromEvidenceProperties.path("jfrSummary"), "null")).isTrue();
		assertThat(typeAllows(fromEvidenceProperties.path("repeatedSamplingResult"), "null")).isTrue();
		assertThat(typeAllows(fromEvidenceProperties.path("resourceBudgetEvidence"), "null")).isTrue();
		JsonNode collectRequestSchema = publishedInputSchema("collectMemoryGcEvidence").path("properties")
			.path("request");
		assertThat(requiredFields(collectRequestSchema)).contains("pid")
			.doesNotContain("includeClassHistogram", "includeThreadDump", "includeHeapDump", "heapDumpOutputPath",
					"confirmationToken");

		JsonNode repeatedRequestSchema = publishedInputSchema("inspectJvmRuntimeRepeated").path("properties")
			.path("request");
		assertThat(requiredFields(repeatedRequestSchema)).contains("pid")
			.doesNotContain("sampleCount", "intervalMillis", "includeThreadCount", "includeClassCount",
					"confirmationToken");

		JsonNode jfrRequestSchema = publishedInputSchema("recordJvmFlightRecording").path("properties")
			.path("request");
		assertThat(requiredFields(jfrRequestSchema)).contains("pid", "jfrOutputPath", "confirmationToken")
			.doesNotContain("durationSeconds", "settings", "maxSummaryEvents");

		JsonNode validateDraftSchema = publishedInputSchema("validateOfflineAnalysisDraft").path("properties")
			.path("draft");
		assertThat(requiredFields(validateDraftSchema)).isEmpty();
		JsonNode artifactSource = resolveSchemaNode(validateDraftSchema,
				validateDraftSchema.path("properties").path("classHistogram"));
		assertThat(requiredFields(artifactSource)).isEmpty();

		JsonNode offlineAdviceSchema = publishedInputSchema("generateOfflineTuningAdvice");
		assertThat(requiredFields(offlineAdviceSchema)).doesNotContain("codeContextSummary", "analysisDepth",
				"confirmationToken");
		assertThat(requiredFields(offlineAdviceSchema.path("properties").path("draft"))).isEmpty();

		JsonNode summarizeHeapSchema = publishedInputSchema("summarizeOfflineHeapDumpFile");
		assertThat(requiredFields(summarizeHeapSchema)).contains("heapDumpAbsolutePath")
			.doesNotContain("topClassLimit", "maxOutputChars");

		JsonNode retentionSchema = publishedInputSchema("analyzeOfflineHeapRetention");
		assertThat(requiredFields(retentionSchema)).contains("heapDumpAbsolutePath")
			.doesNotContain("topObjectLimit", "maxOutputChars", "analysisDepth", "focusTypes", "focusPackages");
	}

	@Test
	void publicToolDescriptionsShouldBeBilingualForClients() throws Exception {
		for (var callback : toolCallbackProvider.getToolCallbacks()) {
			var def = callback.getToolDefinition();
			assertThat(containsAsciiLetter(def.description()))
				.as("registered description for " + def.name() + " should include English")
				.isTrue();
			assertThat(containsCjk(def.description()))
				.as("registered description for " + def.name() + " should include Chinese")
				.isTrue();

			JsonNode publishedTool = mapper.readTree(PUBLISHED_SCHEMA_DIR.resolve(def.name() + ".json").toFile());
			String publishedDescription = publishedTool.path("description").asText();
			assertThat(containsAsciiLetter(publishedDescription))
				.as("published description for " + def.name() + " should include English")
				.isTrue();
			assertThat(containsCjk(publishedDescription))
				.as("published description for " + def.name() + " should include Chinese")
				.isTrue();
		}
	}

	@Test
	void privilegedToolsShouldDocumentKeyFieldsInSchema() throws Exception {
		for (var callback : toolCallbackProvider.getToolCallbacks()) {
			var def = callback.getToolDefinition();
			if (!"collectMemoryGcEvidence".equals(def.name()) && !"generateTuningAdvice".equals(def.name())
					&& !"generateOfflineTuningAdvice".equals(def.name())
					&& !"recordJvmFlightRecording".equals(def.name())) {
				continue;
			}
			JsonNode schema = mapper.readTree(def.inputSchema());
			assertThat(def.description()).containsIgnoringCase("confirmation");
			// Nested or top-level: at least one property description mentioning token or heap
			assertThat(schemaContainsDescription(schema, "token") || schemaContainsDescription(schema, "hprof")
					|| schemaContainsDescription(schema, "heap"))
				.as("schema text for " + def.name() + " should mention token, hprof, or heap somewhere")
				.isTrue();
		}
	}

	@Test
	void offlineDraftToolsShouldDescribeArtifactSourceFieldsWithoutSourceAccess() {
		for (var callback : toolCallbackProvider.getToolCallbacks()) {
			var def = callback.getToolDefinition();
			if (!"validateOfflineAnalysisDraft".equals(def.name())
					&& !"generateOfflineTuningAdvice".equals(def.name())) {
				continue;
			}
			assertThat(def.description())
				.as("description for %s should explain artifact field shapes", def.name())
				.contains("filePath")
				.contains("inlineText")
				.containsIgnoringCase("bare string")
				.contains("heapDumpAbsolutePath");
		}
	}

	@Test
	void analyzeOfflineHeapRetentionShouldBeRegistered() {
		assertThat(Arrays.stream(toolCallbackProvider.getToolCallbacks()).map(callback -> callback.getToolDefinition())
			.map(def -> def.name())).contains("analyzeOfflineHeapRetention");
	}

	@Test
	void inspectJvmRuntimeRepeatedShouldBeRegistered() {
		assertThat(Arrays.stream(toolCallbackProvider.getToolCallbacks()).map(callback -> callback.getToolDefinition())
			.map(def -> def.name())).contains("inspectJvmRuntimeRepeated");
	}

	@Test
	void recordJvmFlightRecordingShouldBeRegistered() {
		assertThat(Arrays.stream(toolCallbackProvider.getToolCallbacks()).map(callback -> callback.getToolDefinition())
			.map(def -> def.name())).contains("recordJvmFlightRecording");
	}

	@Test
	void generateTuningAdviceFromEvidenceShouldBeRegistered() {
		assertThat(Arrays.stream(toolCallbackProvider.getToolCallbacks()).map(callback -> callback.getToolDefinition())
			.map(def -> def.name())).contains("generateTuningAdviceFromEvidence");
	}

	private static boolean schemaContainsDescription(JsonNode node, String substring) {
		if (node == null || node.isMissingNode()) {
			return false;
		}
		if (node.isObject()) {
			JsonNode desc = node.get("description");
			if (desc != null && desc.isTextual() && desc.asText().toLowerCase().contains(substring)) {
				return true;
			}
			var fields = node.fields();
			while (fields.hasNext()) {
				if (schemaContainsDescription(fields.next().getValue(), substring)) {
					return true;
				}
			}
		}
		else if (node.isArray()) {
			for (JsonNode child : node) {
				if (schemaContainsDescription(child, substring)) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean containsAsciiLetter(String text) {
		return text != null && text.chars().anyMatch(ch -> (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z'));
	}

	private static boolean containsCjk(String text) {
		return text != null && text.codePoints().anyMatch(ch -> ch >= 0x4E00 && ch <= 0x9FFF);
	}

	private Set<String> registeredToolNames() {
		return Arrays.stream(toolCallbackProvider.getToolCallbacks())
			.map(callback -> callback.getToolDefinition().name())
			.collect(Collectors.toCollection(java.util.TreeSet::new));
	}

	private static Set<String> publishedToolNames() throws IOException {
		try (var paths = Files.list(PUBLISHED_SCHEMA_DIR)) {
			return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
				.map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
				.collect(Collectors.toCollection(java.util.TreeSet::new));
		}
	}

	private JsonNode publishedInputSchema(String toolName) throws IOException {
		return mapper.readTree(PUBLISHED_SCHEMA_DIR.resolve(toolName + ".json").toFile()).path("inputSchema");
	}

	private static Set<String> requiredFields(JsonNode schema) {
		return streamArray(schema.path("required")).map(JsonNode::asText)
			.collect(Collectors.toCollection(java.util.TreeSet::new));
	}

	private static boolean typeAllows(JsonNode schema, String expectedType) {
		JsonNode type = schema.path("type");
		if (type.isTextual()) {
			return expectedType.equals(type.asText());
		}
		return streamArray(type).map(JsonNode::asText).anyMatch(expectedType::equals);
	}

	private static void assertRequiredFieldsAreDeclared(JsonNode schema, String toolName) {
		Set<String> properties = new java.util.TreeSet<>();
		schema.path("properties").fieldNames().forEachRemaining(properties::add);
		for (String required : requiredFields(schema)) {
			assertThat(properties).as("required field %s should be declared in properties for %s", required, toolName)
				.contains(required);
		}
	}

	private static java.util.stream.Stream<JsonNode> streamArray(JsonNode node) {
		return node == null || !node.isArray() ? java.util.stream.Stream.empty()
				: java.util.stream.StreamSupport.stream(node.spliterator(), false);
	}

	private static boolean containsRootRecursiveRef(JsonNode node) {
		if (node == null || node.isMissingNode()) {
			return false;
		}
		if (node.isObject()) {
			JsonNode ref = node.get("$ref");
			if (ref != null && ref.isTextual() && "#".equals(ref.asText())) {
				return true;
			}
			var fields = node.fields();
			while (fields.hasNext()) {
				if (containsRootRecursiveRef(fields.next().getValue())) {
					return true;
				}
			}
		}
		else if (node.isArray()) {
			for (JsonNode child : node) {
				if (containsRootRecursiveRef(child)) {
					return true;
				}
			}
		}
		return false;
	}

	private static void assertReferencesResolve(JsonNode root, JsonNode node, String toolName) {
		if (node == null || node.isMissingNode()) {
			return;
		}
		if (node.isObject()) {
			JsonNode ref = node.get("$ref");
			if (ref != null && ref.isTextual()) {
				String pointer = ref.asText();
				assertThat(pointer).as("$ref in " + toolName).startsWith("#/");
				assertThat(root.at(pointer.substring(1)).isMissingNode())
					.as("$ref %s in %s should resolve from the schema root", pointer, toolName)
					.isFalse();
			}
			var fields = node.fields();
			while (fields.hasNext()) {
				assertReferencesResolve(root, fields.next().getValue(), toolName);
			}
		}
		else if (node.isArray()) {
			for (JsonNode child : node) {
				assertReferencesResolve(root, child, toolName);
			}
		}
	}

	private static JsonNode resolveSchemaNode(JsonNode root, JsonNode node) {
		JsonNode current = node;
		while (current != null) {
			if (current.has("$ref")) {
				String ref = current.path("$ref").asText();
				assertThat(ref).startsWith("#/");
				current = followRef(root, ref.substring(2));
				continue;
			}
			if (current.has("allOf") && current.path("allOf").size() == 1) {
				current = current.path("allOf").get(0);
				continue;
			}
			break;
		}
		return current == null ? MissingNode.getInstance() : current;
	}

	private static JsonNode followRef(JsonNode root, String pointer) {
		JsonNode current = root;
		for (String segment : pointer.split("/")) {
			current = current.path(segment);
		}
		return current;
	}

	private static String descriptionOf(JsonNode originalNode, JsonNode resolvedNode) {
		String original = originalNode.path("description").asText();
		return original.isBlank() ? resolvedNode.path("description").asText() : original;
	}

}
