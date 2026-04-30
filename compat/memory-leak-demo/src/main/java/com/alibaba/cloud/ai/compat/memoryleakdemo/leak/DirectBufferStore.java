package com.alibaba.cloud.ai.compat.memoryleakdemo.leak;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.alibaba.cloud.ai.compat.memoryleakdemo.web.RecentAllocationView;
import org.springframework.stereotype.Component;

@Component
public class DirectBufferStore {

	private final List<ByteBuffer> retainedBuffers = new ArrayList<>();

	private final Deque<RecentAllocationView> recentAllocations = new ArrayDeque<>(10);

	private long allocationRequests;

	public synchronized AllocationSummary allocate(int entries, int payloadKb, String tag) {
		long addedBytes = 0;
		for (int index = 0; index < entries; index++) {
			ByteBuffer buffer = ByteBuffer.allocateDirect(Math.multiplyExact(payloadKb, 1024));
			retainedBuffers.add(buffer);
			addedBytes += buffer.capacity();
		}
		allocationRequests++;
		recentAllocations.addFirst(new RecentAllocationView(tag, entries, payloadKb, addedBytes));
		while (recentAllocations.size() > 10) {
			recentAllocations.removeLast();
		}
		return currentSummary();
	}

	public synchronized AllocationSummary currentSummary() {
		return new AllocationSummary(retainedBuffers.size(), retainedBytes(), allocationRequests,
				List.copyOf(recentAllocations), 0, 0);
	}

	public synchronized AllocationSummary clear() {
		long clearedEntries = retainedBuffers.size();
		long clearedBytes = retainedBytes();
		retainedBuffers.clear();
		return new AllocationSummary(0, 0, allocationRequests, List.copyOf(recentAllocations), clearedEntries,
				clearedBytes);
	}

	private long retainedBytes() {
		return retainedBuffers.stream().mapToLong(ByteBuffer::capacity).sum();
	}

}
