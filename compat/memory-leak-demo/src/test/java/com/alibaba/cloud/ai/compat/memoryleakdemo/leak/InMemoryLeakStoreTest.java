package com.alibaba.cloud.ai.compat.memoryleakdemo.leak;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class InMemoryLeakStoreTest {

	@Test
	void allocateShouldIncreaseRetainedCountsAndBytes() {
		InMemoryLeakStore store = new InMemoryLeakStore(10, true);

		AllocationSummary summary = store.allocate(3, 4, "batch-a");

		assertThat(summary.retainedEntries()).isEqualTo(3);
		assertThat(summary.retainedBytesEstimate()).isEqualTo(3L * 4L * 1024L);
		assertThat(summary.allocationRequests()).isEqualTo(1);
		assertThat(summary.recentAllocations()).hasSize(1);
	}

	@Test
	void clearShouldResetRetainedEntriesButKeepRequestHistoryCount() {
		InMemoryLeakStore store = new InMemoryLeakStore(10, true);
		store.allocate(2, 8, "batch-a");

		AllocationSummary cleared = store.clear();
		AllocationSummary afterClear = store.currentSummary();

		assertThat(cleared.clearedEntries()).isEqualTo(2);
		assertThat(afterClear.retainedEntries()).isZero();
		assertThat(afterClear.retainedBytesEstimate()).isZero();
		assertThat(afterClear.allocationRequests()).isEqualTo(1);
	}

	@Test
	void allocateShouldRejectNonPositiveEntries() {
		InMemoryLeakStore store = new InMemoryLeakStore(10, true);

		assertThatIllegalArgumentException()
				.isThrownBy(() -> store.allocate(0, 4, "batch-a"))
				.withMessage("entries must be greater than 0");
	}

	@Test
	void allocateShouldRejectNonPositivePayloadKb() {
		InMemoryLeakStore store = new InMemoryLeakStore(10, true);

		assertThatIllegalArgumentException()
				.isThrownBy(() -> store.allocate(1, 0, "batch-a"))
				.withMessage("payloadKb must be greater than 0");
	}

}
