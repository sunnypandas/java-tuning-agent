package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionConfidence;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineAnalysisJobServiceTest {

	@Test
	void heapRetentionJobRunsInBackgroundAndCanBePolledForResult() throws Exception {
		CountDownLatch analyzerEntered = new CountDownLatch(1);
		CountDownLatch releaseAnalyzer = new CountDownLatch(1);
		HeapRetentionAnalysisResult expected = successfulResult();
		RecordingRetentionAnalyzer analyzer = new RecordingRetentionAnalyzer(expected, analyzerEntered, releaseAnalyzer);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try (OfflineAnalysisJobService service = new OfflineAnalysisJobService(analyzer, executor, 250L)) {
			OfflineAnalysisJobHandle handle = service.startHeapRetentionAnalysis(Path.of("/tmp/demo.hprof"), 3, 4000,
					"deep", List.of("byte[]"), List.of("com.demo"));

			assertThat(handle.jobId()).isNotBlank();
			assertThat(handle.status()).isIn(OfflineAnalysisJobStatus.QUEUED, OfflineAnalysisJobStatus.RUNNING);
			assertThat(handle.pollIntervalMillis()).isEqualTo(250L);
			assertThat(analyzerEntered.await(2, TimeUnit.SECONDS)).isTrue();
			OfflineAnalysisJobSnapshot running = service.getJob(handle.jobId());
			assertThat(running.status()).isEqualTo(OfflineAnalysisJobStatus.RUNNING);
			assertThat(running.result()).isNull();

			releaseAnalyzer.countDown();
			OfflineAnalysisJobSnapshot completed = awaitStatus(service, handle.jobId(),
					OfflineAnalysisJobStatus.SUCCEEDED);

			assertThat(completed.result()).isEqualTo(expected);
			assertThat(completed.errorMessage()).isBlank();
			assertThat(analyzer.calls).containsExactly(new RetentionCall(Path.of("/tmp/demo.hprof"), 3, 4000, "deep",
					List.of("byte[]"), List.of("com.demo")));
		}
		finally {
			executor.shutdownNow();
		}
	}

	@Test
	void cancellingQueuedJobPreventsAnalyzerExecution() {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch blocker = new CountDownLatch(1);
		try (OfflineAnalysisJobService service = new OfflineAnalysisJobService(new RecordingRetentionAnalyzer(
				successfulResult(), new CountDownLatch(0), blocker), executor, 1000L)) {
			service.startHeapRetentionAnalysis(Path.of("/tmp/first.hprof"), null, null, "deep", List.of(), List.of());
			OfflineAnalysisJobHandle queued = service.startHeapRetentionAnalysis(Path.of("/tmp/second.hprof"), null,
					null, "deep", List.of(), List.of());

			OfflineAnalysisJobSnapshot cancelled = service.cancelJob(queued.jobId());

			assertThat(cancelled.status()).isEqualTo(OfflineAnalysisJobStatus.CANCELLED);
			assertThat(service.getJob(queued.jobId()).status()).isEqualTo(OfflineAnalysisJobStatus.CANCELLED);
		}
		finally {
			blocker.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void failedAnalyzerIsCapturedInJobSnapshot() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		HeapRetentionAnalyzer analyzer = (path, topObjectLimit, maxOutputChars, analysisDepth, focusTypes,
				focusPackages) -> {
			throw new IllegalStateException("boom");
		};
		try (OfflineAnalysisJobService service = new OfflineAnalysisJobService(analyzer, executor, 1000L)) {
			OfflineAnalysisJobHandle handle = service.startHeapRetentionAnalysis(Path.of("/tmp/demo.hprof"), null,
					null, "deep", List.of(), List.of());

			OfflineAnalysisJobSnapshot failed = awaitStatus(service, handle.jobId(), OfflineAnalysisJobStatus.FAILED);

			assertThat(failed.result()).isNull();
			assertThat(failed.errorMessage()).contains("boom");
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static OfflineAnalysisJobSnapshot awaitStatus(OfflineAnalysisJobService service, String jobId,
			OfflineAnalysisJobStatus expected) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		OfflineAnalysisJobSnapshot snapshot;
		do {
			snapshot = service.getJob(jobId);
			if (snapshot.status() == expected) {
				return snapshot;
			}
			Thread.sleep(25L);
		}
		while (System.nanoTime() < deadline);
		return snapshot;
	}

	private static HeapRetentionAnalysisResult successfulResult() {
		HeapRetentionSummary summary = new HeapRetentionSummary(List.of(), List.of(), List.of(), List.of(),
				new HeapRetentionConfidence("medium", List.of(), List.of()), "markdown", true, List.of(), "");
		return new HeapRetentionAnalysisResult(true, "dominator-style", List.of(), "", summary, "markdown");
	}

	private record RetentionCall(Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars, String analysisDepth,
			List<String> focusTypes, List<String> focusPackages) {
	}

	private static final class RecordingRetentionAnalyzer implements HeapRetentionAnalyzer {

		private final List<RetentionCall> calls = new java.util.ArrayList<>();

		private final HeapRetentionAnalysisResult result;

		private final CountDownLatch analyzerEntered;

		private final CountDownLatch releaseAnalyzer;

		private RecordingRetentionAnalyzer(HeapRetentionAnalysisResult result, CountDownLatch analyzerEntered,
				CountDownLatch releaseAnalyzer) {
			this.result = result;
			this.analyzerEntered = analyzerEntered;
			this.releaseAnalyzer = releaseAnalyzer;
		}

		@Override
		public HeapRetentionAnalysisResult analyze(Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars,
				String analysisDepth, List<String> focusTypes, List<String> focusPackages) {
			calls.add(new RetentionCall(heapDumpPath, topObjectLimit, maxOutputChars, analysisDepth,
					List.copyOf(focusTypes), List.copyOf(focusPackages)));
			analyzerEntered.countDown();
			try {
				releaseAnalyzer.await(2, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return result;
		}

	}

}
