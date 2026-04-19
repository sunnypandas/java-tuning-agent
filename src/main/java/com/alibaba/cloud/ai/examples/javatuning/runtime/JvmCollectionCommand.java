package com.alibaba.cloud.ai.examples.javatuning.runtime;

import java.util.List;

public record JvmCollectionCommand(List<String> command, boolean privileged, String description) {
}
