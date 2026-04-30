package com.alibaba.cloud.ai.compat.memoryleakdemo.churn;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

@Service
public class JfrWorkloadService {

	private final Object monitor = new Object();

	public JfrWorkloadResult run(int durationSeconds, int workerThreads, int payloadBytes) {
		long deadlineNanos = System.nanoTime() + durationSeconds * 1_000_000_000L;
		AtomicBoolean running = new AtomicBoolean(true);
		CountDownLatch startLatch = new CountDownLatch(1);
		LongAdder allocationLoops = new LongAdder();
		LongAdder contentionLoops = new LongAdder();
		List<Thread> threads = new ArrayList<>();
		for (int index = 0; index < workerThreads; index++) {
			Thread thread = new Thread(() -> runWorker(deadlineNanos, running, startLatch, payloadBytes, allocationLoops,
					contentionLoops), "memory-leak-demo-jfr-worker-" + index);
			thread.setDaemon(true);
			threads.add(thread);
			thread.start();
		}
		startLatch.countDown();
		for (Thread thread : threads) {
			try {
				thread.join(Math.max(1, durationSeconds) * 1500L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				running.set(false);
				break;
			}
		}
		running.set(false);
		return new JfrWorkloadResult(durationSeconds, workerThreads, payloadBytes, allocationLoops.sum(),
				contentionLoops.sum());
	}

	private void runWorker(long deadlineNanos, AtomicBoolean running, CountDownLatch startLatch, int payloadBytes,
			LongAdder allocationLoops, LongAdder contentionLoops) {
		try {
			startLatch.await();
			while (running.get() && System.nanoTime() < deadlineNanos) {
				byte[] payload = new byte[payloadBytes];
				payload[0] = 1;
				allocationLoops.increment();
				holdContendedMonitor(contentionLoops);
				burnCpu(payload);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private void holdContendedMonitor(LongAdder contentionLoops) throws InterruptedException {
		synchronized (monitor) {
			contentionLoops.increment();
			Thread.sleep(2L);
		}
	}

	private long burnCpu(byte[] payload) {
		long value = 0;
		for (int index = 0; index < 256; index++) {
			value += (payload[0] + index) * 31L;
		}
		return value;
	}

	public record JfrWorkloadResult(int durationSeconds, int workerThreads, int payloadBytes, long allocationLoops,
			long contentionLoops) {
	}

}
