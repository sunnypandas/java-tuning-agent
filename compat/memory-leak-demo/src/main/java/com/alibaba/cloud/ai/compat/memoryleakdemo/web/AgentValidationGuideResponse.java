package com.alibaba.cloud.ai.compat.memoryleakdemo.web;

import java.util.List;

public record AgentValidationGuideResponse(List<String> repoRelativeSourceRoots, String springApplicationName,
		int serverPort, List<ValidationScenarioView> scenarios, List<String> mcpHints, List<String> agentNotes) {

	public static AgentValidationGuideResponse buildDefault() {
		return new AgentValidationGuideResponse(
				List.of("compat/memory-leak-demo"),
				"memory-leak-demo",
				8091,
				List.of(
						new ValidationScenarioView("retained-records-heap-pressure",
								"Retained AllocationRecord + high heap",
								"Grows the global list of AllocationRecord with byte[] payloads until heap pressure is visible.",
								List.of("POST /api/leak/allocate with large entries x payloadKb (e.g. 400 x 256KB) in several rounds",
										"inspectJvmRuntime before and after",
										"collectMemoryGcEvidence with includeClassHistogram=true, then generateTuningAdviceFromEvidence with sourceRoots"),
								List.of("High heap pressure", "Suspected retained", "classHistogram"),
								"-Xms128m -Xmx256m -XX:+UseG1GC",
								List.of(
										"curl.exe -X POST http://localhost:8091/api/leak/allocate -H \"Content-Type: application/json\" -d \"{\\\"entries\\\":120,\\\"payloadKb\\\":512,\\\"tag\\\":\\\"round-1\\\"}\"")),
						new ValidationScenarioView("retained-raw-bytes-histogram",
								"Dominant [B in class histogram",
								"Retains plain byte[] without AllocationRecord wrapper to exercise [B-heavy histogram logic.",
								List.of("POST /api/leak/raw/allocate with large total bytes",
										"collectMemoryGcEvidence with histogram + token, then generateTuningAdviceFromEvidence"),
								List.of("Suspected retained", "[B", "byte"),
								"-Xms128m -Xmx256m -XX:+UseG1GC",
								List.of(
										"curl.exe -X POST http://localhost:8091/api/leak/raw/allocate -H \"Content-Type: application/json\" -d \"{\\\"entries\\\":200,\\\"payloadKb\\\":256,\\\"tag\\\":\\\"raw-b\\\"}\"")),
						new ValidationScenarioView("xms-xmx-spread",
								"GC / heap sizing heuristic",
								"Restart the JVM with a large Xms vs Xmx gap so the agent can flag spread.",
								List.of("Stop demo; restart with -Xms32m -Xmx256m (see README)",
										"collectMemoryGcEvidence snapshot-only, then generateTuningAdviceFromEvidence"),
								List.of("Heap min/max spread", "Xms", "Xmx"),
								"-Xms32m -Xmx256m -XX:+UseG1GC",
								List.of()),
						new ValidationScenarioView("deadlock-thread-dump",
								"Java-level deadlock in Thread.print",
								"Triggers two daemon threads in a lock cycle; collect thread dump with confirmation.",
								List.of("POST /api/leak/deadlock/trigger (once per JVM)",
										"collectMemoryGcEvidence with includeThreadDump=true + confirmationToken",
										"Expect deadlock-related finding from the agent"),
								List.of("deadlock", "Java-level deadlock"),
								"-Xms128m -Xmx256m -XX:+UseG1GC",
								List.of("curl.exe -X POST http://localhost:8091/api/leak/deadlock/trigger")),
						new ValidationScenarioView("young-gc-churn",
								"Young GC churn (longer soak)",
								"The agent only flags churn after very high YGC counts; use /api/leak/churn repeatedly or a long loop.",
								List.of("POST /api/leak/churn with high iterations several times or script a loop",
										"Keep heap below ~85% utilization while YGC climbs",
										"Re-inspect jstat counters via inspectJvmRuntime"),
								List.of("young-GC churn", "churn"),
								"-Xms128m -Xmx192m -XX:+UseG1GC",
								List.of(
										"curl.exe -X POST http://localhost:8091/api/leak/churn -H \"Content-Type: application/json\" -d \"{\\\"iterations\\\":2000000,\\\"payloadBytes\\\":4096}\""))),
				List.of("listJavaApps → pick PID for memory-leak-demo",
						"inspectJvmRuntime(pid) for lightweight snapshot (VM.version, PerfCounter threads, jstat -class included)",
						"collectMemoryGcEvidence(MemoryGcEvidenceRequest) for histogram and/or thread dump with confirmationToken",
						"generateTuningAdviceFromEvidence(evidence, codeContextSummary, environment, optimizationGoal) to avoid double collection",
						"generateTuningAdvice(..., collectClassHistogram, collectThreadDump, confirmationToken) remains a one-shot flow when evidence was not already collected",
						"CodeContextSummary.sourceRoots: [\"compat/memory-leak-demo\"] from repo root for AllocationRecord.java hotspots"),
				List.of("SafeJvmRuntimeCollector expects G1 GC.heap_info shape; use -XX:+UseG1GC.",
						"After deadlock demo, restart JVM before repeating trigger (single-shot per process).",
						"Clear retained memory with POST /api/leak/clear and POST /api/leak/raw/clear between runs."));
	}
}
