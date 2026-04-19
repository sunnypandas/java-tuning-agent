package com.alibaba.cloud.ai.examples.javatuning.config;

import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.advice.MemoryGcDiagnosisEngine;
import com.alibaba.cloud.ai.examples.javatuning.agent.JavaTuningWorkflowService;
import com.alibaba.cloud.ai.examples.javatuning.source.LocalSourceHotspotFinder;
import com.alibaba.cloud.ai.examples.javatuning.discovery.CommandJavaProcessDiscoveryService;
import com.alibaba.cloud.ai.examples.javatuning.discovery.JavaProcessDiscoveryService;
import com.alibaba.cloud.ai.examples.javatuning.discovery.ProcessDisplayNameResolver;
import com.alibaba.cloud.ai.examples.javatuning.mcp.JavaTuningMcpTools;
import com.alibaba.cloud.ai.examples.javatuning.runtime.CommandExecutor;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RuntimeCollectionPolicy;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SafeJvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SystemCommandExecutor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JavaTuningAgentConfig {

	@Bean
	ProcessDisplayNameResolver processDisplayNameResolver() {
		return new ProcessDisplayNameResolver();
	}

	@Bean
	CommandExecutor commandExecutor(
			@Value("${java-tuning-agent.command-whitelist:jps,jcmd,jstat}") List<String> commandWhitelist) {
		return new SystemCommandExecutor(commandWhitelist);
	}

	@Bean
	JavaProcessDiscoveryService javaProcessDiscoveryService(CommandExecutor commandExecutor,
			ProcessDisplayNameResolver resolver) {
		return new CommandJavaProcessDiscoveryService(commandExecutor, resolver);
	}

	@Bean
	RuntimeCollectionPolicy runtimeCollectionPolicy() {
		return RuntimeCollectionPolicy.safeReadonly();
	}

	@Bean
	JvmRuntimeCollector jvmRuntimeCollector(CommandExecutor commandExecutor, RuntimeCollectionPolicy policy) {
		return new SafeJvmRuntimeCollector(commandExecutor, policy);
	}

	@Bean
	MemoryGcDiagnosisEngine memoryGcDiagnosisEngine() {
		return MemoryGcDiagnosisEngine.firstVersion();
	}

	@Bean
	LocalSourceHotspotFinder localSourceHotspotFinder() {
		return new LocalSourceHotspotFinder();
	}

	@Bean
	JavaTuningWorkflowService javaTuningWorkflowService(JvmRuntimeCollector collector,
			MemoryGcDiagnosisEngine diagnosisEngine, LocalSourceHotspotFinder localSourceHotspotFinder) {
		return new JavaTuningWorkflowService(collector, diagnosisEngine, localSourceHotspotFinder);
	}

	@Bean
	JavaTuningMcpTools javaTuningMcpTools(JavaProcessDiscoveryService discoveryService, JvmRuntimeCollector collector,
			JavaTuningWorkflowService workflowService) {
		return new JavaTuningMcpTools(discoveryService, collector, workflowService);
	}

	@Bean
	ToolCallbackProvider toolCallbackProvider(JavaTuningMcpTools javaTuningMcpTools) {
		return MethodToolCallbackProvider.builder().toolObjects(javaTuningMcpTools).build();
	}

}
