package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

public record DeadlockTriggerResponse(boolean startedFresh, boolean alreadyTriggered, String message) {
}
