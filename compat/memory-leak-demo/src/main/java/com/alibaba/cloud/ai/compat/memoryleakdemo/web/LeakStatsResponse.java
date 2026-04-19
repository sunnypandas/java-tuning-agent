package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

import java.util.List;

public record LeakStatsResponse(long retainedEntries, long retainedBytesEstimate, long allocationRequests,
		List<RecentAllocationView> recentAllocations) {
}
