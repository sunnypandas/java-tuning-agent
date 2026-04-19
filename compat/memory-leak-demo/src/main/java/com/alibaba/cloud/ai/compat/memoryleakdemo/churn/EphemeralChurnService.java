package com.alibaba.cloud.ai.compat.memoryleakdemo.churn;

import org.springframework.stereotype.Service;

@Service
public class EphemeralChurnService {

	/**
	 * Allocates short-lived arrays to increase young GC without retaining them.
	 */
	public ChurnResult run(int iterations, int payloadBytes) {
		long t0 = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			byte[] chunk = new byte[payloadBytes];
			chunk[payloadBytes - 1] = (byte) i;
		}
		long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
		long approxAllocatedMb = (long) iterations * (long) payloadBytes / (1024L * 1024L);
		return new ChurnResult(iterations, payloadBytes, elapsedMs, approxAllocatedMb);
	}

	public record ChurnResult(int iterations, int payloadBytes, long elapsedMs, long approxAllocatedMb) {
	}
}
