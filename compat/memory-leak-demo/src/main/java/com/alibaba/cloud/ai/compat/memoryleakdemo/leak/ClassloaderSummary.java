package com.alibaba.cloud.ai.compat.memoryleakdemo.leak;

import java.util.List;

import com.alibaba.cloud.ai.compat.memoryleakdemo.web.RecentAllocationView;

public record ClassloaderSummary(long retainedLoaders, long generatedProxyClasses, long allocationRequests,
		List<RecentAllocationView> recentAllocations, long clearedLoaders) {
}
