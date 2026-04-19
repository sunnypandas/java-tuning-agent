package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

public record DeadlockStatusResponse(boolean deadlockFeatureEnabled, boolean deadlockAlreadyTriggered) {
}
