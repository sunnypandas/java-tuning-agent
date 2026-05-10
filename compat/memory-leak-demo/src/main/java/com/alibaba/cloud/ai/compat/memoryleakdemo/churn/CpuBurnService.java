package com.alibaba.cloud.ai.compat.memoryleakdemo.churn;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

@Service
public class CpuBurnService {

	public static final String THREAD_NAME_PREFIX = "memory-leak-demo-cpu-worker";

	public static final String HOT_METHOD = "CpuBurnService.burnCpuLoop";

	private final Object lifecycleMonitor = new Object();

	private final AtomicBoolean running = new AtomicBoolean(false);

	private final LongAdder loopCounter = new LongAdder();

	private List<Thread> workers = List.of();

	private long deadlineNanos;

	public CpuBurnStatus start(int durationSeconds, int workerThreads) {
		synchronized (lifecycleMonitor) {
			stopLocked();
			running.set(true);
			loopCounter.reset();
			deadlineNanos = System.nanoTime() + durationSeconds * 1_000_000_000L;
			List<Thread> newWorkers = new ArrayList<>();
			for (int index = 0; index < workerThreads; index++) {
				Thread worker = new Thread(() -> burnCpuLoop(running, deadlineNanos),
						THREAD_NAME_PREFIX + "-" + index);
				worker.setDaemon(true);
				newWorkers.add(worker);
				worker.start();
			}
			workers = List.copyOf(newWorkers);
			return statusLocked();
		}
	}

	public CpuBurnStatus stop() {
		synchronized (lifecycleMonitor) {
			stopLocked();
			return statusLocked();
		}
	}

	public CpuBurnStatus status() {
		synchronized (lifecycleMonitor) {
			if (running.get() && System.nanoTime() >= deadlineNanos) {
				stopLocked();
			}
			return statusLocked();
		}
	}

	void burnCpuLoop(AtomicBoolean keepRunning, long deadline) {
		long value = 0L;
		while (keepRunning.get() && System.nanoTime() < deadline) {
			for (int index = 1; index <= 8192; index++) {
				value += (index * 31L) ^ (value >>> 3);
			}
			loopCounter.increment();
		}
		if (value == Long.MIN_VALUE) {
			throw new IllegalStateException("unreachable guard to keep CPU work observable");
		}
	}

	private void stopLocked() {
		running.set(false);
		for (Thread worker : workers) {
			try {
				worker.join(200L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		workers = List.of();
	}

	private CpuBurnStatus statusLocked() {
		return new CpuBurnStatus(running.get(), workers.size(), THREAD_NAME_PREFIX, HOT_METHOD, loopCounter.sum());
	}

	public record CpuBurnStatus(boolean running, int workerThreads, String threadNamePrefix, String hotMethod,
			long loopCount) {
	}
}
