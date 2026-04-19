package com.alibaba.cloud.ai.compat.memoryleakdemo.leak;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import com.alibaba.cloud.ai.compat.memoryleakdemo.web.RecentAllocationView;
import org.springframework.stereotype.Component;

/**
 * Retains naked {@code byte[]} instances so {@code GC.class_histogram} often shows {@code [B} near the top.
 */
@Component
public class RetainedByteArrayStore {

	private final List<byte[]> retained = new ArrayList<>();

	private final Deque<RecentAllocationView> recentAllocations = new ArrayDeque<>(10);

	private long allocationRequests;

	public synchronized void allocate(int entries, int payloadKb, String tag) {
		if (entries <= 0 || entries > 2000) {
			throw new IllegalArgumentException("entries must be in 1..2000");
		}
		if (payloadKb <= 0 || payloadKb > 1024) {
			throw new IllegalArgumentException("payloadKb must be in 1..1024");
		}
		long addedBytes = 0L;
		for (int i = 0; i < entries; i++) {
			byte[] chunk = new byte[Math.multiplyExact(payloadKb, 1024)];
			retained.add(chunk);
			addedBytes += chunk.length;
		}
		allocationRequests++;
		recentAllocations.addFirst(new RecentAllocationView(tag + "-raw-bytes", entries, payloadKb, addedBytes));
		while (recentAllocations.size() > 10) {
			recentAllocations.removeLast();
		}
	}

	public synchronized AllocationSummary currentSummary() {
		return new AllocationSummary(retained.size(), retainedBytes(), allocationRequests, List.copyOf(recentAllocations),
				0, 0);
	}

	public synchronized AllocationSummary clear() {
		long clearedEntries = retained.size();
		long clearedBytes = retainedBytes();
		retained.clear();
		return new AllocationSummary(0, 0, allocationRequests, List.copyOf(recentAllocations), clearedEntries,
				clearedBytes);
	}

	private long retainedBytes() {
		long sum = 0L;
		for (byte[] b : retained) {
			sum += b.length;
		}
		return sum;
	}

}
