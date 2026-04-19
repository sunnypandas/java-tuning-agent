package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

public record ClearResponse(long clearedEntries, long clearedBytesEstimate, long remainingEntries) {
}
