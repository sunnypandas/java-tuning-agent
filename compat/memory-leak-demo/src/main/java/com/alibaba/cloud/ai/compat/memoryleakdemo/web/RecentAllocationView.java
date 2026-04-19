package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

public record RecentAllocationView(String tag, int entries, int payloadKb, long addedBytesEstimate) {
}
