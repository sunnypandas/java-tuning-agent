package com.alibaba.cloud.ai.compat.memoryleakdemo.leak;

import java.util.List;

import com.alibaba.cloud.ai.compat.memoryleakdemo.web.RecentAllocationView;

public record AllocationSummary(long retainedEntries, long retainedBytesEstimate, long allocationRequests,
		List<RecentAllocationView> recentAllocations, long clearedEntries, long clearedBytesEstimate) {
}
