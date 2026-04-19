package com.alibaba.cloud.ai.compat.memoryleakdemo.leak;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryLeakStoreHistoryLimitTest {

	@Test
	void recentAllocationHistoryShouldKeepOnlyTenEntries() {
		InMemoryLeakStore store = new InMemoryLeakStore(10, true);
		for (int index = 0; index < 12; index++) {
			store.allocate(1, 1, "batch-" + index);
		}

		AllocationSummary summary = store.currentSummary();

		assertThat(summary.recentAllocations()).hasSize(10);
		assertThat(summary.recentAllocations().get(0).tag()).isEqualTo("batch-11");
		assertThat(summary.recentAllocations().get(9).tag()).isEqualTo("batch-2");
	}

}
