package com.alibaba.cloud.ai.examples.javatuning.mcp.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
			tool.set("inputSchema", objectMapper.readTree(def.inputSchema()));
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

}
