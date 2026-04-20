package com.alibaba.cloud.ai.examples.javatuning.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.advice.MemoryGcDiagnosisEngine;
import com.alibaba.cloud.ai.examples.javatuning.agent.JavaTuningWorkflowService;
import com.alibaba.cloud.ai.examples.javatuning.source.LocalSourceHotspotFinder;
import com.alibaba.cloud.ai.examples.javatuning.discovery.CommandJavaProcessDiscoveryService;
import com.alibaba.cloud.ai.examples.javatuning.discovery.JavaProcessDiscoveryService;
import com.alibaba.cloud.ai.examples.javatuning.discovery.ProcessDisplayNameResolver;
import com.alibaba.cloud.ai.examples.javatuning.mcp.JavaTuningMcpTools;
import com.alibaba.cloud.ai.examples.javatuning.mcp.OfflineMcpTools;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapDumpChunkRepository;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineAnalysisService;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineDraftValidator;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineEvidenceAssembler;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineJvmSnapshotAssembler;
import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapDumpSummarizer;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.CommandExecutor;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RuntimeCollectionPolicy;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SafeJvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SystemCommandExecutor;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ThreadDumpParser;
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
	JvmRuntimeCollector jvmRuntimeCollector(CommandExecutor commandExecutor, RuntimeCollectionPolicy policy,
			SharkHeapDumpSummarizer sharkHeapDumpSummarizer,
			@Value("${java-tuning-agent.heap-summary.auto-enabled:true}") boolean autoHeapSummary) {
		return new SafeJvmRuntimeCollector(commandExecutor, policy, sharkHeapDumpSummarizer, autoHeapSummary);
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
	OfflineDraftValidator offlineDraftValidator() {
		return new OfflineDraftValidator();
	}

	@Bean
	OfflineJvmSnapshotAssembler offlineJvmSnapshotAssembler() {
		return new OfflineJvmSnapshotAssembler();
	}

	@Bean
	OfflineEvidenceAssembler offlineEvidenceAssembler(OfflineJvmSnapshotAssembler snapshotAssembler,
			SharkHeapDumpSummarizer sharkHeapDumpSummarizer,
			@Value("${java-tuning-agent.heap-summary.auto-enabled:true}") boolean autoHeapSummary) {
		return new OfflineEvidenceAssembler(snapshotAssembler, new ClassHistogramParser(), new ThreadDumpParser(),
				sharkHeapDumpSummarizer, autoHeapSummary);
	}

	@Bean
	HeapDumpChunkRepository heapDumpChunkRepository(
			@Value("${java-tuning-agent.offline.chunk-base-dir:}") String configuredDir) {
		Path base = configuredDir.isBlank()
				? Path.of(System.getProperty("java.io.tmpdir"), "java-tuning-agent-offline-chunks")
				: Path.of(configuredDir);
		try {
			Files.createDirectories(base);
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot create offline chunk base dir: " + base, e);
		}
		return new HeapDumpChunkRepository(base);
	}

	@Bean
	SharkHeapDumpSummarizer sharkHeapDumpSummarizer(
			@Value("${java-tuning-agent.offline.heap-summary.default-top-classes:40}") int defaultTopClasses,
			@Value("${java-tuning-agent.offline.heap-summary.default-max-output-chars:32000}") int defaultMaxOutputChars) {
		return new SharkHeapDumpSummarizer(defaultTopClasses, defaultMaxOutputChars);
	}

	@Bean
	OfflineAnalysisService offlineAnalysisService(OfflineDraftValidator validator,
			OfflineEvidenceAssembler evidenceAssembler, JavaTuningWorkflowService workflowService) {
		return new OfflineAnalysisService(validator, evidenceAssembler, workflowService);
	}

	@Bean
	OfflineMcpTools offlineMcpTools(OfflineAnalysisService offlineAnalysisService,
			HeapDumpChunkRepository heapDumpChunkRepository, SharkHeapDumpSummarizer sharkHeapDumpSummarizer) {
		return new OfflineMcpTools(offlineAnalysisService, heapDumpChunkRepository, sharkHeapDumpSummarizer);
	}

	@Bean
	ToolCallbackProvider toolCallbackProvider(JavaTuningMcpTools javaTuningMcpTools,
			OfflineMcpTools offlineMcpTools) {
		return MethodToolCallbackProvider.builder().toolObjects(javaTuningMcpTools, offlineMcpTools).build();
	}

}
