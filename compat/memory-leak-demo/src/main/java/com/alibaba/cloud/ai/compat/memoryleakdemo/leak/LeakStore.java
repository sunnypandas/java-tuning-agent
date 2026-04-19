package com.alibaba.cloud.ai.compat.memoryleakdemo.leak;

public interface LeakStore {

	AllocationSummary allocate(int entries, int payloadKb, String tag);

	AllocationSummary currentSummary();

	AllocationSummary clear();

}
