package com.alibaba.cloud.ai.examples.javatuning.advice;

import com.alibaba.cloud.ai.examples.javatuning.runtime.MemoryGcEvidencePack;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ThreadCpuSample;
import com.alibaba.cloud.ai.examples.javatuning.runtime.ThreadDumpSummary;

public final class ThreadCpuHotspotRule implements DiagnosisRule {

	public static final String RUNNABLE_CPU_THREAD_TITLE = "Thread dump shows runnable CPU hotspot";

	private static final double MIN_CPU_TIME_MS = 1000.0d;

	@Override
	public void evaluate(MemoryGcEvidencePack evidence, CodeContextSummary context, DiagnosisScratch scratch) {
		ThreadDumpSummary threadDump = evidence.threadDump();
		if (threadDump == null || threadDump.topCpuThreads().isEmpty()) {
			return;
		}
		ThreadCpuSample top = threadDump.topCpuThreads().get(0);
		if (!"RUNNABLE".equals(top.state()) || top.cpuTimeMs() < MIN_CPU_TIME_MS) {
			return;
		}
		String detail = "thread=" + top.threadName() + " state=" + top.state() + " cpuTimeMs=" + top.cpuTimeMs()
				+ " nid=" + top.nid() + " topFrame=" + top.topFrame();
		scratch.addFinding(new TuningFinding(RUNNABLE_CPU_THREAD_TITLE, "medium", detail, "thread-dump-cpu",
				"A RUNNABLE thread with high accumulated CPU time is a Java-level high CPU suspect"));
		scratch.addRecommendation(new TuningRecommendation("Profile and optimize the hottest runnable thread path", "cpu",
				"Correlate the thread nid with OS CPU sampling and inspect the reported top Java frame",
				"Reduces CPU saturation when the reported frame is on the hot path",
				"Thread.print CPU time is cumulative; validate with repeated dumps or JFR execution samples",
				"Start with frame: " + top.topFrame()));
		scratch.addNextStep("Take a second Thread.print or short JFR profile under load and confirm "
				+ top.threadName() + " remains the hottest RUNNABLE thread");
	}
}
