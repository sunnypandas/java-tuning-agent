package com.alibaba.cloud.ai.examples.javatuning.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ensures MCP clients can call tools using only {@link org.springframework.ai.tool.definition.ToolDefinition}
 * metadata (description + inputSchema), without reading this repository's source.
 */
@SpringBootTest
class McpToolSchemaContractTest {

	@Autowired
	private ToolCallbackProvider toolCallbackProvider;

	private final ObjectMapper mapper = new ObjectMapper();

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
				}
				default -> throw new AssertionError("Unexpected tool: " + def.name());
			}
		}
	}

	@Test
	void privilegedToolsShouldDocumentKeyFieldsInSchema() throws Exception {
		for (var callback : toolCallbackProvider.getToolCallbacks()) {
			var def = callback.getToolDefinition();
			if (!"collectMemoryGcEvidence".equals(def.name()) && !"generateTuningAdvice".equals(def.name())) {
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

}
