package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;

/**
 * In-memory asynchronous job manager for offline analysis work that can exceed MCP request timeouts.
 */
public class OfflineAnalysisJobService implements AutoCloseable {

	private static final String HEAP_RETENTION_JOB_TYPE = "offlineHeapRetentionAnalysis";

	private final HeapRetentionAnalyzer heapRetentionAnalyzer;

	private final ExecutorService executorService;

	private final long pollIntervalMillis;

	private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();

	public OfflineAnalysisJobService(HeapRetentionAnalyzer heapRetentionAnalyzer, ExecutorService executorService,
			long pollIntervalMillis) {
		this.heapRetentionAnalyzer = Objects.requireNonNull(heapRetentionAnalyzer, "heapRetentionAnalyzer");
		this.executorService = Objects.requireNonNull(executorService, "executorService");
		this.pollIntervalMillis = pollIntervalMillis > 0L ? pollIntervalMillis : 1000L;
	}

	public OfflineAnalysisJobHandle startHeapRetentionAnalysis(Path heapDumpPath, Integer topObjectLimit,
			Integer maxOutputChars, String analysisDepth, List<String> focusTypes, List<String> focusPackages) {
		if (heapDumpPath == null) {
			throw new IllegalArgumentException("heapDumpAbsolutePath is required");
		}
		String jobId = "offline-retention-" + UUID.randomUUID();
		JobState state = new JobState(jobId, HEAP_RETENTION_JOB_TYPE, pollIntervalMillis);
		jobs.put(jobId, state);
		Future<?> future = executorService.submit(() -> runHeapRetention(state, heapDumpPath, topObjectLimit,
				maxOutputChars, analysisDepth, safeList(focusTypes), safeList(focusPackages)));
		state.future = future;
		return new OfflineAnalysisJobHandle(jobId, state.status.get(), pollIntervalMillis,
				"Offline heap retention analysis accepted; poll getOfflineAnalysisJob with this jobId.");
	}

	public OfflineAnalysisJobSnapshot getJob(String jobId) {
		return state(jobId).snapshot();
	}

	public OfflineAnalysisJobSnapshot cancelJob(String jobId) {
		JobState state = state(jobId);
		OfflineAnalysisJobStatus current = state.status.get();
		if (current == OfflineAnalysisJobStatus.SUCCEEDED || current == OfflineAnalysisJobStatus.FAILED
				|| current == OfflineAnalysisJobStatus.CANCELLED) {
			return state.snapshot();
		}
		state.status.set(OfflineAnalysisJobStatus.CANCELLED);
		state.completedAtEpochMs = now();
		state.message = "Job cancellation requested.";
		Future<?> future = state.future;
		if (future != null) {
			future.cancel(true);
		}
		return state.snapshot();
	}

	@Override
	public void close() {
		executorService.shutdownNow();
	}

	private void runHeapRetention(JobState state, Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars,
			String analysisDepth, List<String> focusTypes, List<String> focusPackages) {
		if (!state.status.compareAndSet(OfflineAnalysisJobStatus.QUEUED, OfflineAnalysisJobStatus.RUNNING)) {
			return;
		}
		state.startedAtEpochMs = now();
		state.message = "Running offline heap retention analysis.";
		try {
			HeapRetentionAnalysisResult result = heapRetentionAnalyzer.analyze(heapDumpPath, topObjectLimit,
					maxOutputChars, analysisDepth, focusTypes, focusPackages);
			if (state.status.compareAndSet(OfflineAnalysisJobStatus.RUNNING, OfflineAnalysisJobStatus.SUCCEEDED)) {
				state.result = result;
				state.completedAtEpochMs = now();
				state.message = "Offline heap retention analysis completed.";
			}
		}
		catch (RuntimeException | Error ex) {
			if (state.status.get() != OfflineAnalysisJobStatus.CANCELLED) {
				state.status.set(OfflineAnalysisJobStatus.FAILED);
				state.completedAtEpochMs = now();
				state.errorMessage = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
				state.message = "Offline heap retention analysis failed.";
			}
		}
	}

	private JobState state(String jobId) {
		if (jobId == null || jobId.isBlank()) {
			throw new IllegalArgumentException("jobId is required");
		}
		JobState state = jobs.get(jobId.trim());
		if (state == null) {
			throw new IllegalArgumentException("Unknown offline analysis jobId: " + jobId);
		}
		return state;
	}

	private static List<String> safeList(List<String> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

	private static long now() {
		return Instant.now().toEpochMilli();
	}

	private static final class JobState {

		private final String jobId;

		private final String jobType;

		private final long pollIntervalMillis;

		private final long createdAtEpochMs = now();

		private final AtomicReference<OfflineAnalysisJobStatus> status = new AtomicReference<>(
				OfflineAnalysisJobStatus.QUEUED);

		private volatile Future<?> future;

		private volatile long startedAtEpochMs;

		private volatile long completedAtEpochMs;

		private volatile String message = "Queued offline analysis job.";

		private volatile HeapRetentionAnalysisResult result;

		private volatile String errorMessage = "";

		private JobState(String jobId, String jobType, long pollIntervalMillis) {
			this.jobId = jobId;
			this.jobType = jobType;
			this.pollIntervalMillis = pollIntervalMillis;
		}

		private OfflineAnalysisJobSnapshot snapshot() {
			OfflineAnalysisJobStatus current = status.get();
			return new OfflineAnalysisJobSnapshot(jobId, jobType, current, progress(current), pollIntervalMillis,
					createdAtEpochMs, startedAtEpochMs, completedAtEpochMs, message, result, errorMessage);
		}

		private static int progress(OfflineAnalysisJobStatus status) {
			return switch (status) {
				case QUEUED -> 0;
				case RUNNING -> 10;
				case SUCCEEDED -> 100;
				case FAILED, CANCELLED -> 100;
			};
		}

	}

}
