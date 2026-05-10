package com.alibaba.cloud.ai.examples.javatuning.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.alibaba.cloud.ai.examples.javatuning.advice.MemoryGcDiagnosisEngine;
import com.alibaba.cloud.ai.examples.javatuning.agent.JavaTuningWorkflowService;
import com.alibaba.cloud.ai.examples.javatuning.source.LocalSourceHotspotFinder;
import com.alibaba.cloud.ai.examples.javatuning.source.SourceHotspotCorrelationService;
import com.alibaba.cloud.ai.examples.javatuning.discovery.CommandJavaProcessDiscoveryService;
import com.alibaba.cloud.ai.examples.javatuning.discovery.JavaProcessDiscoveryService;
import com.alibaba.cloud.ai.examples.javatuning.discovery.ProcessDisplayNameResolver;
import com.alibaba.cloud.ai.examples.javatuning.mcp.JavaTuningMcpTools;
import com.alibaba.cloud.ai.examples.javatuning.mcp.OfflineMcpTools;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapDumpChunkRepository;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapRetentionAnalysisOrchestrator;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapRetentionAnalyzer;
import com.alibaba.cloud.ai.examples.javatuning.offline.DominatorStyleHeapRetentionAnalyzer;
import com.alibaba.cloud.ai.examples.javatuning.offline.DominatorStyleHeapRetentionOptions;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineAnalysisService;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineAnalysisJobService;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineDraftValidator;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineEvidenceAssembler;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineJvmSnapshotAssembler;
import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapRetentionAnalyzer;
import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapDumpSummarizer;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ClassHistogramParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.CommandExecutor;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JfrRecordingProperties;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RepeatedSamplingProperties;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RuntimeCollectionPolicy;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SafeJvmRuntimeCollector;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SystemCommandExecutor;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ThreadDumpParser;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class JavaTuningAgentConfig {

	@Bean
	ProcessDisplayNameResolver processDisplayNameResolver() {
		return new ProcessDisplayNameResolver();
	}

	@Bean
	CommandExecutor commandExecutor(
			@Value("${java-tuning-agent.command-whitelist:jps,jcmd,jstat}") List<String> commandWhitelist,
			@Value("${java-tuning-agent.command.default-timeout-ms:15000}") long defaultTimeoutMs,
			@Value("${java-tuning-agent.command.default-max-output-bytes:8388608}") int defaultMaxOutputBytes) {
		return new SystemCommandExecutor(commandWhitelist, defaultTimeoutMs, defaultMaxOutputBytes);
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
			@Value("${java-tuning-agent.heap-summary.auto-enabled:true}") boolean autoHeapSummary,
			@Value("${java-tuning-agent.sampling.default-sample-count:3}") int defaultSampleCount,
			@Value("${java-tuning-agent.sampling.default-interval-ms:10000}") long defaultIntervalMs,
			@Value("${java-tuning-agent.sampling.max-sample-count:20}") int maxSampleCount,
			@Value("${java-tuning-agent.sampling.max-total-duration-ms:300000}") long maxTotalDurationMs,
			@Value("${java-tuning-agent.jfr.default-duration-seconds:30}") int defaultJfrDurationSeconds,
			@Value("${java-tuning-agent.jfr.min-duration-seconds:5}") int minJfrDurationSeconds,
			@Value("${java-tuning-agent.jfr.max-duration-seconds:300}") int maxJfrDurationSeconds,
			@Value("${java-tuning-agent.jfr.completion-grace-ms:10000}") long jfrCompletionGraceMs,
			@Value("${java-tuning-agent.jfr.default-max-summary-events:200000}") int defaultJfrMaxSummaryEvents,
			@Value("${java-tuning-agent.jfr.top-limit:10}") int jfrTopLimit) {
		JfrRecordingProperties jfrProperties = new JfrRecordingProperties(defaultJfrDurationSeconds,
				minJfrDurationSeconds, maxJfrDurationSeconds, jfrCompletionGraceMs, defaultJfrMaxSummaryEvents,
				jfrTopLimit);
		return new SafeJvmRuntimeCollector(commandExecutor, policy, sharkHeapDumpSummarizer, autoHeapSummary,
				new RepeatedSamplingProperties(defaultSampleCount, defaultIntervalMs, maxSampleCount, maxTotalDurationMs),
				jfrProperties);
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
	SourceHotspotCorrelationService sourceHotspotCorrelationService(LocalSourceHotspotFinder localSourceHotspotFinder) {
		return new SourceHotspotCorrelationService(localSourceHotspotFinder);
	}

	@Bean
	JavaTuningWorkflowService javaTuningWorkflowService(JvmRuntimeCollector collector,
			MemoryGcDiagnosisEngine diagnosisEngine, SourceHotspotCorrelationService sourceHotspotCorrelationService) {
		return new JavaTuningWorkflowService(collector, diagnosisEngine, sourceHotspotCorrelationService);
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
			@Value("${java-tuning-agent.offline.chunk-base-dir:}") String configuredDir,
			@Value("${java-tuning-agent.offline.heap-dump-upload.ttl-seconds:86400}") long ttlSeconds,
			@Value("${java-tuning-agent.offline.heap-dump-upload.cleanup-finalized:false}") boolean cleanupFinalized) {
		Path base = configuredDir.isBlank()
				? Path.of(System.getProperty("java.io.tmpdir"), "java-tuning-agent-offline-chunks")
				: Path.of(configuredDir);
		try {
			Files.createDirectories(base);
		}
		catch (IOException e) {
			throw new IllegalStateException("Cannot create offline chunk base dir: " + base, e);
		}
		return new HeapDumpChunkRepository(base, Duration.ofSeconds(Math.max(0L, ttlSeconds)), cleanupFinalized);
	}

	@Bean
	SharkHeapDumpSummarizer sharkHeapDumpSummarizer(
			@Value("${java-tuning-agent.offline.heap-summary.default-top-classes:40}") int defaultTopClasses,
			@Value("${java-tuning-agent.offline.heap-summary.default-max-output-chars:32000}") int defaultMaxOutputChars) {
		return new SharkHeapDumpSummarizer(defaultTopClasses, defaultMaxOutputChars);
	}

	@Bean
	SharkHeapRetentionAnalyzer sharkHeapRetentionAnalyzer(
			@Value("${java-tuning-agent.offline.heap-retention.default-top-objects:20}") int defaultTopObjects,
			@Value("${java-tuning-agent.offline.heap-retention.default-max-output-chars:16000}") int defaultMaxOutputChars) {
		return new SharkHeapRetentionAnalyzer(defaultTopObjects, defaultMaxOutputChars);
	}

	@Bean
	DominatorStyleHeapRetentionAnalyzer dominatorStyleHeapRetentionAnalyzer(
			@Value("${java-tuning-agent.offline.heap-retention.default-top-objects:20}") int defaultTopObjects,
			@Value("${java-tuning-agent.offline.heap-retention.default-max-output-chars:16000}") int defaultMaxOutputChars,
			@Value("${java-tuning-agent.offline.heap-retention.deep.candidate-multiplier:32}") int candidateMultiplier,
			@Value("${java-tuning-agent.offline.heap-retention.deep.reverse-depth-limit:24}") int reverseDepthLimit,
			@Value("${java-tuning-agent.offline.heap-retention.deep.forward-depth-limit:8}") int forwardDepthLimit,
			@Value("${java-tuning-agent.offline.heap-retention.deep.min-reverse-node-limit:128000}") int minReverseNodeLimit,
			@Value("${java-tuning-agent.offline.heap-retention.deep.min-forward-node-limit:256000}") int minForwardNodeLimit,
			@Value("${java-tuning-agent.offline.heap-retention.deep.max-reverse-node-limit:2000000}") int maxReverseNodeLimit,
			@Value("${java-tuning-agent.offline.heap-retention.deep.max-forward-node-limit:4000000}") int maxForwardNodeLimit,
			@Value("${java-tuning-agent.offline.heap-retention.deep.path-search-node-limit:16384}") int pathSearchNodeLimit) {
		var options = new DominatorStyleHeapRetentionOptions(candidateMultiplier, reverseDepthLimit, forwardDepthLimit,
				minReverseNodeLimit, minForwardNodeLimit, maxReverseNodeLimit, maxForwardNodeLimit, pathSearchNodeLimit);
		return new DominatorStyleHeapRetentionAnalyzer(defaultTopObjects, defaultMaxOutputChars, options);
	}

	@Bean
	@Primary
	HeapRetentionAnalyzer heapRetentionAnalyzer(
			HeapRetentionAnalyzer sharkHeapRetentionAnalyzer,
			@Qualifier("dominatorStyleHeapRetentionAnalyzer") HeapRetentionAnalyzer dominatorStyleHeapRetentionAnalyzer) {
		return new HeapRetentionAnalysisOrchestrator(sharkHeapRetentionAnalyzer, dominatorStyleHeapRetentionAnalyzer);
	}

	@Bean
	OfflineAnalysisService offlineAnalysisService(OfflineDraftValidator validator,
			OfflineEvidenceAssembler evidenceAssembler, HeapRetentionAnalyzer heapRetentionAnalyzer,
			JavaTuningWorkflowService workflowService) {
		return new OfflineAnalysisService(validator, evidenceAssembler, heapRetentionAnalyzer, workflowService);
	}

	@Bean(destroyMethod = "shutdownNow")
	ExecutorService offlineAnalysisJobExecutor(
			@Value("${java-tuning-agent.offline.analysis-jobs.max-threads:2}") int maxThreads) {
		int threads = Math.max(1, maxThreads);
		AtomicInteger sequence = new AtomicInteger();
		ThreadFactory threadFactory = runnable -> {
			Thread thread = new Thread(runnable, "java-tuning-offline-analysis-" + sequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
		return Executors.newFixedThreadPool(threads, threadFactory);
	}

	@Bean
	OfflineAnalysisJobService offlineAnalysisJobService(HeapRetentionAnalyzer heapRetentionAnalyzer,
			ExecutorService offlineAnalysisJobExecutor,
			@Value("${java-tuning-agent.offline.analysis-jobs.poll-interval-ms:1000}") long pollIntervalMillis) {
		return new OfflineAnalysisJobService(heapRetentionAnalyzer, offlineAnalysisJobExecutor, pollIntervalMillis);
	}

	@Bean
	OfflineMcpTools offlineMcpTools(OfflineAnalysisService offlineAnalysisService,
			HeapDumpChunkRepository heapDumpChunkRepository, SharkHeapDumpSummarizer sharkHeapDumpSummarizer,
			HeapRetentionAnalyzer heapRetentionAnalyzer, OfflineAnalysisJobService offlineAnalysisJobService) {
		return new OfflineMcpTools(offlineAnalysisService, heapDumpChunkRepository, sharkHeapDumpSummarizer,
				heapRetentionAnalyzer, offlineAnalysisJobService);
	}

	@Bean
	ToolCallbackProvider toolCallbackProvider(JavaTuningMcpTools javaTuningMcpTools,
			OfflineMcpTools offlineMcpTools) {
		return MethodToolCallbackProvider.builder().toolObjects(javaTuningMcpTools, offlineMcpTools).build();
	}

}
