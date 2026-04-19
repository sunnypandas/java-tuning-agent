# Memory/GC Diagnosis Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the current java-tuning-agent into a memory/GC diagnosis agent that returns structured findings, confidence, evidence-upgrade guidance, and source-aware code hotspots.

**Architecture:** Keep the existing `runtime`, `advice`, `agent`, `mcp`, and `config` packages, but split the work into four layers: structured runtime parsing, evidence-pack collection, memory/GC diagnosis, and source correlation. The workflow stays safe-readonly by default and only upgrades to histogram-based evidence when explicitly requested.

**Tech Stack:** Java 17, Spring Boot 3.5.x, Spring AI MCP tools, JUnit 5, Mockito, AssertJ

**Status (2026-04-11):** Tasks 1–4 are **implemented** in this repository: structured runtime + parsers, evidence pack + histogram collection, `MemoryGcDiagnosisEngine` + rules, `LocalSourceHotspotFinder`, `JavaTuningWorkflowService`, MCP `collectMemoryGcEvidence`, and expanded `TuningAdviceReport` / `CodeContextSummary`. See [README.md](../../../README.md) and [design spec §16](../specs/2026-04-11-memory-gc-diagnosis-agent-design.md#16-implementation-status-repository-sync) for authoritative behavior vs this plan.

---

## File Structure

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmRuntimeSnapshot.java`
  - Change from loose maps to typed runtime sections plus collection metadata.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmMemorySnapshot.java`
  - Heap, old-gen, metaspace, Xms, and Xmx fields.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmGcSnapshot.java`
  - Collector, YGC/FGC counts, and time fields.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmCollectionMetadata.java`
  - Commands run, elapsed time, privilege level, warnings.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcHeapInfoParser.java`
  - Parse `jcmd GC.heap_info`.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JstatGcUtilParser.java`
  - Parse `jstat -gcutil`.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ClassHistogramParser.java`
  - Parse `jcmd GC.class_histogram`.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`
  - Bundle snapshot, histogram, warnings, and missing data.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java`
  - Run first-version memory/GC rules over evidence.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/SuspectedCodeHotspot.java`
  - Carry source-aware suspicion output.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/source/LocalSourceHotspotFinder.java`
  - Map histogram FQCNs to local files.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowService.java`
  - Orchestrate snapshot, evidence upgrade, diagnosis, and hotspot correlation.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/JavaTuningMcpTools.java`
  - Add `collectMemoryGcEvidence` and upgrade `generateTuningAdvice`.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/*`
  - Parser and collector tests.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/*`
  - Diagnosis engine tests.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/source/*`
  - Source correlation tests.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/*`
  - MCP workflow tests.
- `README.md`
  - Document lightweight evidence, histogram upgrade flow, confidence, and source-aware mode.

### Task 1: Add Structured Runtime Parsing

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmMemorySnapshot.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmGcSnapshot.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmCollectionMetadata.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcHeapInfoParser.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JstatGcUtilParser.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmRuntimeSnapshot.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcHeapInfoParserTest.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JstatGcUtilParserTest.java`

- [ ] **Step 1: Write the failing parser tests**

```java
class GcHeapInfoParserTest {
	@Test
	void shouldParseG1HeapInfo() {
		ParsedHeapInfo parsed = new GcHeapInfoParser().parse("""
				14628:
				garbage-first heap total 262144K, used 218758K
				""");
		assertThat(parsed.collector()).isEqualTo("G1");
		assertThat(parsed.heapUsedBytes()).isEqualTo(218758L * 1024L);
	}
}
```

```java
class JstatGcUtilParserTest {
	@Test
	void shouldParseGcutilCounters() {
		ParsedGcUtil parsed = new JstatGcUtilParser().parse("""
				  S0 S1 E O M CCS YGC YGCT FGC FGCT CGC CGCT GCT
				  0 100 12.34 78.90 92.21 88.12 145 1.234 2 0.456 - - 1.690
				""");
		assertThat(parsed.youngGcCount()).isEqualTo(145L);
		assertThat(parsed.fullGcTimeMs()).isEqualTo(456L);
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -Dtest=GcHeapInfoParserTest,JstatGcUtilParserTest test`
Expected: FAIL because parser and structured snapshot types do not exist.

- [ ] **Step 3: Implement the minimal structured runtime types**

```java
public record JvmMemorySnapshot(long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes,
		Long oldGenUsedBytes, Long metaspaceUsedBytes, Long xmsBytes, Long xmxBytes) {
}
```

```java
public record JvmGcSnapshot(String collector, long youngGcCount, long youngGcTimeMs,
		long fullGcCount, long fullGcTimeMs, Double oldUsagePercent) {
}
```

```java
public record JvmCollectionMetadata(List<String> commandsRun, long collectedAtEpochMs, long elapsedMs,
		boolean privilegedCollection) {
}
```

```java
public record JvmRuntimeSnapshot(long pid, JvmMemorySnapshot memory, JvmGcSnapshot gc,
		List<String> vmFlags, JvmCollectionMetadata collectionMetadata, List<String> warnings) {
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -Dtest=GcHeapInfoParserTest,JstatGcUtilParserTest test`
Expected: PASS with parsed collector, heap, and GC counter values.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime
git commit -m "feat: add structured memory gc runtime parsing"
```

### Task 2: Add Evidence Pack and Histogram Collection

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ClassHistogramEntry.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ClassHistogramSummary.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ClassHistogramParser.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidenceRequest.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RuntimeCollectionPolicy.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ClassHistogramParserTest.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorTest.java`

- [ ] **Step 1: Write the failing evidence tests**

```java
class ClassHistogramParserTest {
	@Test
	void shouldParseTopEntries() {
		ClassHistogramSummary summary = new ClassHistogramParser().parse("""
				 1: 600 157286400 com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord
				""");
		assertThat(summary.topEntries().get(0).className())
				.isEqualTo("com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord");
	}
}
```

```java
class SafeJvmRuntimeCollectorTest {
	@Test
	void shouldRejectHistogramWithoutConfirmationToken() {
		assertThatThrownBy(() -> collector.collectMemoryGcEvidence(
				new MemoryGcEvidenceRequest(14628L, true, false, null)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("confirmationToken");
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -Dtest=ClassHistogramParserTest,SafeJvmRuntimeCollectorTest test`
Expected: FAIL because histogram and evidence-pack support do not exist.

- [ ] **Step 3: Implement evidence-pack collection**

```java
public record MemoryGcEvidenceRequest(long pid, boolean includeClassHistogram,
		boolean includeThreadDump, String confirmationToken) {
}
```

```java
public record MemoryGcEvidencePack(JvmRuntimeSnapshot snapshot, ClassHistogramSummary classHistogram,
		List<String> missingData, List<String> warnings) {
}
```

```java
public MemoryGcEvidencePack collectMemoryGcEvidence(MemoryGcEvidenceRequest request) {
	RuntimeCollectionPolicy.CollectionRequest policyRequest =
			new RuntimeCollectionPolicy.CollectionRequest(request.includeThreadDump(),
					request.includeClassHistogram(), false, request.confirmationToken());
	JvmRuntimeSnapshot snapshot = collect(request.pid(), policyRequest);
	ClassHistogramSummary histogram = request.includeClassHistogram()
			? histogramParser.parse(executor.run(List.of("jcmd", Long.toString(request.pid()), "GC.class_histogram")))
			: null;
	return new MemoryGcEvidencePack(snapshot, histogram, List.of(), snapshot.warnings());
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -Dtest=ClassHistogramParserTest,SafeJvmRuntimeCollectorTest test`
Expected: PASS with confirmation-gated histogram support.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime
git commit -m "feat: add histogram-based memory gc evidence"
```

### Task 3: Build the Memory/GC Diagnosis Engine

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/SuspectedCodeHotspot.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/TuningAdviceReport.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/CodeContextSummary.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/DiagnosisRule.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/HighHeapPressureRule.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/SuspectedLeakRule.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/AllocationChurnRule.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/GcStrategyMismatchRule.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/DiagnosisConfidenceEvaluator.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngineTest.java`

- [ ] **Step 1: Write the failing diagnosis test**

```java
class MemoryGcDiagnosisEngineTest {
	@Test
	void shouldReportLeakSuspicionFromHistogramEvidence() {
		TuningAdviceReport report = MemoryGcDiagnosisEngine.firstVersion().diagnose(evidence, context, "local", "diagnose-memory");
		assertThat(report.findings()).extracting(TuningFinding::title)
				.contains("Suspected retained-object leak");
		assertThat(report.confidence()).isEqualTo("high");
		assertThat(report.confidenceReasons()).isNotEmpty();
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=MemoryGcDiagnosisEngineTest test`
Expected: FAIL because the diagnosis engine and expanded report contract do not exist.

- [ ] **Step 3: Implement the expanded report and first-version rules**

```java
public record SuspectedCodeHotspot(String className, String fileHint, String suspicionReason,
		String evidenceLink, String confidence) {
}
```

```java
public record TuningAdviceReport(List<TuningFinding> findings, List<TuningRecommendation> recommendations,
		List<SuspectedCodeHotspot> suspectedCodeHotspots, List<String> missingData,
		List<String> nextSteps, String confidence, List<String> confidenceReasons) {
}
```

```java
public record CodeContextSummary(List<String> dependencies, Map<String, String> configuration,
		List<String> applicationNames, List<String> sourceRoots, List<String> candidatePackages) {
}
```

```java
public class MemoryGcDiagnosisEngine {
	public static MemoryGcDiagnosisEngine firstVersion() {
		return new MemoryGcDiagnosisEngine(List.of(
				new HighHeapPressureRule(),
				new SuspectedLeakRule(),
				new AllocationChurnRule(),
				new GcStrategyMismatchRule()),
				new DiagnosisConfidenceEvaluator());
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=MemoryGcDiagnosisEngineTest test`
Expected: PASS with findings for high heap pressure, leak suspicion, or churn depending on input evidence.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice
git commit -m "feat: add memory gc diagnosis engine"
```

### Task 4: Add Source Correlation and Workflow Integration

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/source/LocalSourceHotspotFinder.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowService.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/JavaTuningMcpTools.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/source/LocalSourceHotspotFinderTest.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowServiceTest.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/JavaTuningMcpToolsTest.java`
- Modify: `README.md`

- [ ] **Step 1: Write the failing source-correlation and MCP tests**

```java
class LocalSourceHotspotFinderTest {
	@Test
	void shouldResolveAllocationRecordSource() {
		List<Path> hits = new LocalSourceHotspotFinder().findClassSources(
				List.of(Path.of("compat/memory-leak-demo")),
				"com.alibaba.cloud.ai.compat.memoryleakdemo.leak.AllocationRecord");
		assertThat(hits).isNotEmpty();
	}
}
```

```java
class JavaTuningMcpToolsTest {
	@Test
	void shouldExposeEvidenceCollectionAndExpandedAdvice() {
		assertThat(tools.collectMemoryGcEvidence(new MemoryGcEvidenceRequest(14628L, true, false, "approved")))
				.isNotNull();
		assertThat(tools.generateTuningAdvice(context, 14628L, "local", "diagnose-memory").confidenceReasons())
				.isNotEmpty();
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn -Dtest=LocalSourceHotspotFinderTest,JavaTuningWorkflowServiceTest,JavaTuningMcpToolsTest test`
Expected: FAIL because the source finder and new MCP workflow are not wired yet.

- [ ] **Step 3: Implement source matching and MCP upgrades**

```java
public class LocalSourceHotspotFinder {
	public List<Path> findClassSources(List<Path> sourceRoots, String fqcn) {
		String suffix = fqcn.replace('.', '/') + ".java";
		List<Path> hits = new ArrayList<>();
		for (Path root : sourceRoots) {
			try (Stream<Path> stream = Files.walk(root)) {
				stream.filter(Files::isRegularFile)
						.filter(path -> path.toString().replace('\\', '/').endsWith(suffix))
						.forEach(hits::add);
			}
			catch (IOException ignored) {
			}
		}
		return hits;
	}
}
```

```java
public class JavaTuningWorkflowService {
	public MemoryGcEvidencePack collectEvidence(MemoryGcEvidenceRequest request) {
		return collector.collectMemoryGcEvidence(request);
	}

	public TuningAdviceReport generateAdvice(TuningAdviceRequest request) {
		MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(request.runtimeSnapshot(), null, List.of(), List.of());
		TuningAdviceReport base = diagnosisEngine.diagnose(evidence, request.codeContextSummary(),
				request.environment(), request.optimizationGoal());
		List<SuspectedCodeHotspot> hotspots = sourceHotspotFinder.findHotspots(
				request.codeContextSummary().sourceRoots(), evidence.classHistogram());
		return new TuningAdviceReport(base.findings(), base.recommendations(), hotspots, base.missingData(),
				base.nextSteps(), base.confidence(), base.confidenceReasons());
	}
}
```

```java
@Tool(description = "Collect medium-cost memory/GC evidence such as class histogram when explicitly authorized")
public MemoryGcEvidencePack collectMemoryGcEvidence(MemoryGcEvidenceRequest request) {
	return workflowService.collectEvidence(request);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn -Dtest=LocalSourceHotspotFinderTest,JavaTuningWorkflowServiceTest,JavaTuningMcpToolsTest test`
Expected: PASS with source-aware file hits and expanded MCP contracts.

- [ ] **Step 5: Refresh docs and run the target suite**

Run: `mvn -Dtest=GcHeapInfoParserTest,JstatGcUtilParserTest,ClassHistogramParserTest,SafeJvmRuntimeCollectorTest,MemoryGcDiagnosisEngineTest,LocalSourceHotspotFinderTest,JavaTuningWorkflowServiceTest,JavaTuningMcpToolsTest test`
Expected: PASS with the full first-version memory/GC workflow green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning src/test/java/com/alibaba/cloud/ai/examples/javatuning README.md
git commit -m "feat: complete first memory gc diagnosis workflow"
```

## Self-Review

Spec coverage:
- Structured runtime fields are covered by Task 1.
- Controlled evidence upgrades are covered by Task 2.
- Memory/GC diagnosis, confidence, and next-step guidance are covered by Task 3.
- Source-visible and source-summary operation are covered by Task 4.
- MCP tool upgrades and docs are covered by Task 4.

Placeholder scan:
- No `TODO`, `TBD`, or placeholder steps remain.
- Every task includes exact files, tests, commands, and concrete code direction.

Type consistency:
- `JvmRuntimeSnapshot` is upgraded before evidence and diagnosis tasks use typed fields.
- `MemoryGcEvidencePack` is introduced before workflow and MCP tasks reference it.
- `SuspectedCodeHotspot` and expanded `TuningAdviceReport` are defined before source-correlation steps use them.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-11-memory-gc-diagnosis-agent.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
