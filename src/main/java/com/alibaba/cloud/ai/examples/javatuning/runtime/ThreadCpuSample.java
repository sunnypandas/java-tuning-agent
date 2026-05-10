package com.alibaba.cloud.ai.examples.javatuning.runtime;

public record ThreadCpuSample(String threadName, double cpuTimeMs, String nid, String state, String topFrame) {

	public ThreadCpuSample {
		threadName = threadName == null ? "" : threadName;
		nid = nid == null ? "" : nid;
		state = state == null ? "" : state;
		topFrame = topFrame == null ? "" : topFrame;
	}
}
