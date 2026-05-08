package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record ClassloaderMetaspaceEntry(String classLoaderName, String parentClassLoaderName, Long classCount,
		Long bytes, Boolean alive, String rawLine) {
}
