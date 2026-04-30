package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

public record ClassloaderClearResponse(long clearedLoaders, long remainingLoaders) {
}
