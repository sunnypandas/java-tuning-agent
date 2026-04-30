package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

import java.util.List;

public record ClassloaderStatsResponse(long retainedLoaders, long generatedProxyClasses, long allocationRequests,
		List<RecentAllocationView> recentAllocations) {
}
