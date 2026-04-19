package com.alibaba.cloud.ai.compat.memoryleakdemo.leak;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.cloud.ai.compat.memoryleakdemo.web.RecentAllocationView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InMemoryLeakStore implements LeakStore {

	private static final int DEFAULT_RECENT_HISTORY_LIMIT = 10;

	private final List<AllocationRecord> retainedRecords = new ArrayList<>();

	private final Deque<RecentAllocationView> recentAllocations;

	private final int recentHistoryLimit;

	private final AtomicLong sequence = new AtomicLong();

	private long allocationRequests;

	@Autowired
	public InMemoryLeakStore(@Value("${memory-leak-demo.recent-history-limit:" + DEFAULT_RECENT_HISTORY_LIMIT + "}")
	int recentHistoryLimit) {
		this(recentHistoryLimit, true);
	}

	InMemoryLeakStore(int recentHistoryLimit, boolean testOnly) {
		this.recentHistoryLimit = recentHistoryLimit;
		this.recentAllocations = new ArrayDeque<>(recentHistoryLimit);
	}

	@Override
	public synchronized AllocationSummary allocate(int entries, int payloadKb, String tag) {
		validateAllocateArguments(entries, payloadKb);
		long addedBytes = 0;
		for (int index = 0; index < entries; index++) {
			byte[] payload = new byte[Math.multiplyExact(payloadKb, 1024)];
			AllocationRecord record = new AllocationRecord(sequence.incrementAndGet(), tag, Instant.now(), payload);
			retainedRecords.add(record);
			addedBytes += record.sizeInBytes();
		}
		allocationRequests++;
		recentAllocations.addFirst(new RecentAllocationView(tag, entries, payloadKb, addedBytes));
		while (recentAllocations.size() > recentHistoryLimit) {
			recentAllocations.removeLast();
		}
		return currentSummary();
	}

	@Override
	public synchronized AllocationSummary currentSummary() {
		return new AllocationSummary(retainedRecords.size(), retainedBytes(), allocationRequests,
				List.copyOf(recentAllocations), 0, 0);
	}

	@Override
	public synchronized AllocationSummary clear() {
		long clearedEntries = retainedRecords.size();
		long clearedBytes = retainedBytes();
		retainedRecords.clear();
		return new AllocationSummary(0, 0, allocationRequests, List.copyOf(recentAllocations), clearedEntries,
				clearedBytes);
	}

	private long retainedBytes() {
		return retainedRecords.stream().mapToLong(AllocationRecord::sizeInBytes).sum();
	}

	private static void validateAllocateArguments(int entries, int payloadKb) {
		if (entries <= 0) {
			throw new IllegalArgumentException("entries must be greater than 0");
		}
		if (payloadKb <= 0) {
			throw new IllegalArgumentException("payloadKb must be greater than 0");
		}
	}

}
