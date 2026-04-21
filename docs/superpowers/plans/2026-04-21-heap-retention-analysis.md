# Heap Retention Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a new offline MCP tool that returns structured retention-oriented heap evidence from `.hprof` files without changing the semantics of the existing shallow summary tool.

**Architecture:** This slice implements the phase-1 contract from the approved spec: a new independent retention-analysis tool, a shared JSON result model, and a Shark-backed analyzer that emits holder/path hints with explicit approximation semantics. The existing shallow pipeline remains unchanged, and the new analyzer is introduced behind a narrow interface so a heavier dominator-style engine can replace or augment it later.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring AI MCP tools, LeakCanary Shark (`shark-graph`), JUnit 5, AssertJ, Mockito

---

## File structure

### New files

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/HeapRetentionAnalysisResult.java`
  - Top-level MCP-facing result for the new retention tool.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/HeapRetentionSummary.java`
  - Structured retention payload with dominant types, holders, chains, root hints, confidence, and Markdown.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RetainedTypeSummary.java`
  - One retained-type row.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SuspectedHolderSummary.java`
  - One holder row with role, path hint, and approximate byte counts.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RetentionChainSummary.java`
  - One representative retention chain template.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RetentionChainSegment.java`
  - One chain segment.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcRootHint.java`
  - One GC-root hint row.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/HeapRetentionConfidence.java`
  - Confidence, limitations, and engine notes for retention results.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalyzer.java`
  - Analyzer interface so Shark is not coupled directly to the MCP surface.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/SharkHeapRetentionAnalyzer.java`
  - First implementation using Shark object graph traversal and explicit approximations.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionMarkdownRenderer.java`
  - Converts structured retention summary to bounded Markdown.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/TestHeapDumpSupport.java`
  - Test helper that creates a temporary retained-object graph and dumps a real `.hprof`.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/SharkHeapRetentionAnalyzerTest.java`
  - Unit/integration tests for the new analyzer.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpToolsRetentionTest.java`
  - MCP tool-level tests for the new method.

### Modified files

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpTools.java`
  - Add the new MCP tool method and tool description.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java`
  - Register the analyzer bean and pass it into `OfflineMcpTools`.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`
  - Assert schema/description for the new tool.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/JavaTuningAgentApplicationTests.java`
  - Assert the new tool is registered.
- `docs/offline-mode-spec.md`
  - Document the new tool as retention-oriented and separate from shallow summary.
- `docs/superpowers/specs/2026-04-19-offline-mode-design.md`
  - Link the new tool to the existing offline design and preserve the shallow/retention distinction.

### Files intentionally not modified in this slice

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowService.java`
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineEvidenceAssembler.java`

Reason: phase 1 ships the new retention tool independently. Shared evidence-pack integration is a follow-up plan after the result contract stabilizes.

---

### Task 1: Add the shared retention result model

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/HeapRetentionAnalysisResult.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/HeapRetentionSummary.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RetainedTypeSummary.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SuspectedHolderSummary.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RetentionChainSummary.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RetentionChainSegment.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcRootHint.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/HeapRetentionConfidence.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`

- [ ] **Step 1: Write the failing schema contract assertion for the new retention tool**

```java
case "analyzeOfflineHeapRetention" -> {
    assertThat(schema.path("properties").path("heapDumpAbsolutePath").path("type").asText())
        .isEqualTo("string");
    assertThat(schema.path("properties").path("analysisDepth").path("type").asText())
        .isEqualTo("string");
    assertThat(schema.path("properties").path("focusTypes").path("type").asText())
        .isEqualTo("array");
    assertThat(schema.path("properties").path("focusPackages").path("type").asText())
        .isEqualTo("array");
}
```

- [ ] **Step 2: Run the schema contract test and confirm it fails because the tool does not exist yet**

Run: `mvn "-Dtest=McpToolSchemaContractTest test"`.

Expected: FAIL with an assertion similar to `Unexpected tool` or missing `analyzeOfflineHeapRetention`.

- [ ] **Step 3: Create the result records with explicit approximation naming and null-safe constructors**

```java
public record HeapRetentionAnalysisResult(
        boolean analysisSucceeded,
        String engine,
        List<String> warnings,
        String errorMessage,
        HeapRetentionSummary retentionSummary,
        String summaryMarkdown) {

    public HeapRetentionAnalysisResult {
        engine = engine == null ? "" : engine;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errorMessage = errorMessage == null ? "" : errorMessage;
        summaryMarkdown = summaryMarkdown == null ? "" : summaryMarkdown;
    }
}
```

```java
public record SuspectedHolderSummary(
        String holderType,
        String holderRole,
        Long retainedBytesApprox,
        long reachableSubgraphBytesApprox,
        long retainedObjectCountApprox,
        String exampleFieldPath,
        String exampleTargetType,
        String notes) {
}
```

```java
public record HeapRetentionConfidence(
        String confidence,
        List<String> limitations,
        List<String> engineNotes) {

    public HeapRetentionConfidence {
        confidence = confidence == null ? "low" : confidence;
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        engineNotes = engineNotes == null ? List.of() : List.copyOf(engineNotes);
    }
}
```

- [ ] **Step 4: Make the model self-describing for MCP clients**

Add `@JsonPropertyDescription` to fields that drive contract semantics:

```java
@JsonPropertyDescription("Approximate retained bytes when a retained-style engine can justify the value; null otherwise.")
Long retainedBytesApprox
```

```java
@JsonPropertyDescription("Approximate subgraph size reachable from the holder; not equivalent to retained size.")
long reachableSubgraphBytesApprox
```

- [ ] **Step 5: Re-run the schema contract test and confirm it still fails only because the MCP tool is not wired yet**

Run: `mvn "-Dtest=McpToolSchemaContractTest test"`.

Expected: FAIL, but no Java compilation errors in the new result-model classes.

- [ ] **Step 6: Commit the result-model slice**

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/HeapRetentionAnalysisResult.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/HeapRetentionSummary.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RetainedTypeSummary.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SuspectedHolderSummary.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RetentionChainSummary.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RetentionChainSegment.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcRootHint.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/HeapRetentionConfidence.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java
git commit -m "Add heap retention result model"
```

---

### Task 2: Implement a Shark-backed retention analyzer with real `.hprof` tests

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalyzer.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/SharkHeapRetentionAnalyzer.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionMarkdownRenderer.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/TestHeapDumpSupport.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/SharkHeapRetentionAnalyzerTest.java`

- [ ] **Step 1: Write the failing analyzer test against a real temporary heap dump**

```java
@Test
void analyzesStaticHolderChainForRetainedByteArrays(@TempDir Path dir) throws Exception {
    Path heap = TestHeapDumpSupport.dumpStaticRetainedBytesHeap(dir);
    var analyzer = new SharkHeapRetentionAnalyzer(20, 12000);

    var result = analyzer.analyze(heap, 10, 12000, "balanced", List.of("byte[]"), List.of());

    assertThat(result.analysisSucceeded()).isTrue();
    assertThat(result.retentionSummary().suspectedHolders())
        .extracting(SuspectedHolderSummary::holderRole)
        .contains("static-field-owner");
    assertThat(result.retentionSummary().retentionChains())
        .anySatisfy(chain -> assertThat(chain.terminalType()).isEqualTo("byte[]"));
}
```

- [ ] **Step 2: Run the analyzer test and confirm it fails because the analyzer classes do not exist yet**

Run: `mvn "-Dtest=SharkHeapRetentionAnalyzerTest test"`.

Expected: FAIL with compilation errors for `SharkHeapRetentionAnalyzer`, `HeapRetentionAnalyzer`, or `TestHeapDumpSupport`.

- [ ] **Step 3: Add the test helper that creates a stable retained-object graph and dumps a heap**

```java
final class TestHeapDumpSupport {

    private static final List<byte[]> RETAINED = new ArrayList<>();

    static Path dumpStaticRetainedBytesHeap(Path dir) throws Exception {
        RETAINED.clear();
        RETAINED.add(new byte[256 * 1024]);
        RETAINED.add(new byte[256 * 1024]);
        Path heap = dir.resolve("retained-bytes.hprof");
        HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        bean.dumpHeap(heap.toString(), true);
        return heap;
    }
}
```

- [ ] **Step 4: Implement the analyzer interface and the Shark-backed implementation**

```java
public interface HeapRetentionAnalyzer {

    HeapRetentionAnalysisResult analyze(Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars,
            String analysisDepth, List<String> focusTypes, List<String> focusPackages);
}
```

```java
public final class SharkHeapRetentionAnalyzer implements HeapRetentionAnalyzer {

    @Override
    public HeapRetentionAnalysisResult analyze(Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars,
            String analysisDepth, List<String> focusTypes, List<String> focusPackages) {
        if (heapDumpPath == null || !Files.isRegularFile(heapDumpPath)) {
            return new HeapRetentionAnalysisResult(false, "shark", List.of(),
                    "Not a regular file: " + heapDumpPath, HeapRetentionSummary.emptyFailure(), "");
        }
        // Open heap with Shark, find candidate byte[] / large arrays, walk referrers toward holder classes,
        // classify holder roles, compute reachableSubgraphBytesApprox, and render markdown.
    }
}
```

- [ ] **Step 5: Keep the first implementation honest about approximations**

Use these rules in code and tests:

```java
Long retainedBytesApprox = null;
long reachableSubgraphBytesApprox = terminalShallowBytes;
String notes = "Shark path-based retention hint; retainedBytesApprox unavailable in phase 1.";
```

```java
new HeapRetentionConfidence(
        "medium",
        List.of("Retained bytes are approximate or unavailable in phase 1."),
        List.of("Engine=shark", "Holder chains are representative, not exhaustive."));
```

- [ ] **Step 6: Add a bounded Markdown renderer that mirrors the JSON without overstating certainty**

```java
sb.append("### Heap retention analysis (local, holder-oriented)\n\n");
sb.append("| Holder | Role | Reachable subgraph | Example path |\n");
sb.append("| --- | --- | --- | --- |\n");
for (SuspectedHolderSummary holder : summary.suspectedHolders()) {
    sb.append("| `").append(holder.holderType()).append("` | ")
      .append(holder.holderRole()).append(" | ")
      .append(formatBytes(holder.reachableSubgraphBytesApprox())).append(" | `")
      .append(holder.exampleFieldPath()).append("` |\n");
}
```

- [ ] **Step 7: Re-run the analyzer test and add a failure-path test**

Add:

```java
@Test
void missingFileReturnsFailureResult() {
    var analyzer = new SharkHeapRetentionAnalyzer(20, 12000);
    var result = analyzer.analyze(Path.of("missing.hprof"), 10, 12000, "balanced", List.of(), List.of());

    assertThat(result.analysisSucceeded()).isFalse();
    assertThat(result.errorMessage()).contains("Not a regular file");
}
```

Run: `mvn "-Dtest=SharkHeapRetentionAnalyzerTest test"`.

Expected: PASS.

- [ ] **Step 8: Commit the analyzer slice**

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalyzer.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/SharkHeapRetentionAnalyzer.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionMarkdownRenderer.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/TestHeapDumpSupport.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/SharkHeapRetentionAnalyzerTest.java
git commit -m "Add Shark-backed heap retention analyzer"
```

---

### Task 3: Expose the analyzer through a new MCP tool and wire it into Spring

**Files:**
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpTools.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpToolsRetentionTest.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/JavaTuningAgentApplicationTests.java`

- [ ] **Step 1: Write the failing MCP tool test**

```java
@Test
void analyzeOfflineHeapRetentionDelegatesToAnalyzer() {
    HeapRetentionAnalyzer analyzer = mock(HeapRetentionAnalyzer.class);
    given(analyzer.analyze(any(), eq(12), eq(8000), eq("balanced"), eq(List.of("byte[]")), eq(List.of("com.demo"))))
        .willReturn(new HeapRetentionAnalysisResult(true, "shark", List.of(), "", sampleSummary(), "markdown"));

    OfflineMcpTools tools = new OfflineMcpTools(mock(OfflineAnalysisService.class),
            mock(HeapDumpChunkRepository.class), mock(SharkHeapDumpSummarizer.class), analyzer);

    var result = tools.analyzeOfflineHeapRetention("C:/tmp/demo.hprof", 12, 8000, "balanced",
            List.of("byte[]"), List.of("com.demo"));

    assertThat(result.analysisSucceeded()).isTrue();
    verify(analyzer).analyze(Path.of("C:/tmp/demo.hprof"), 12, 8000, "balanced",
            List.of("byte[]"), List.of("com.demo"));
}
```

- [ ] **Step 2: Run the MCP retention tool test and confirm it fails because the tool method is missing**

Run: `mvn "-Dtest=OfflineMcpToolsRetentionTest test"`.

Expected: FAIL with compilation errors for the missing constructor overload or missing method.

- [ ] **Step 3: Add the new MCP tool with a description that preserves the shallow/retention boundary**

```java
@Tool(description = """
        离线模式：对本地 .hprof 执行 retention-oriented 分析，返回结构化 JSON 证据与易读 Markdown。
        该工具关注 holder、代表性引用链、GC root hints，与 summarizeOfflineHeapDumpFile 的 shallow-by-class 摘要分离。
        retainedBytesApprox 仅在分析器可以给出 defensible retained-style 含义时填充；否则为 null，并以 reachableSubgraphBytesApprox 作为近似排序依据。
        """)
public HeapRetentionAnalysisResult analyzeOfflineHeapRetention(
        @ToolParam(description = "已存在的 .hprof 绝对路径。") String heapDumpAbsolutePath,
        @ToolParam(description = "最多返回的 holder / chain 数量。") Integer topObjectLimit,
        @ToolParam(description = "Markdown 最大字符数。") Integer maxOutputChars,
        @ToolParam(description = "分析深度：fast / balanced / deep。") String analysisDepth,
        @ToolParam(description = "关注的终点类型列表，例如 byte[]。") List<String> focusTypes,
        @ToolParam(description = "关注的业务包前缀列表。") List<String> focusPackages) {
    Path path = heapDumpAbsolutePath == null || heapDumpAbsolutePath.isBlank() ? null : Path.of(heapDumpAbsolutePath.trim());
    return heapRetentionAnalyzer.analyze(path, topObjectLimit, maxOutputChars, analysisDepth, focusTypes, focusPackages);
}
```

- [ ] **Step 4: Wire the analyzer bean and constructor injection**

```java
@Bean
HeapRetentionAnalyzer heapRetentionAnalyzer(
        @Value("${java-tuning-agent.offline.heap-retention.default-top-objects:20}") int defaultTopObjects,
        @Value("${java-tuning-agent.offline.heap-retention.default-max-output-chars:16000}") int defaultMaxOutputChars) {
    return new SharkHeapRetentionAnalyzer(defaultTopObjects, defaultMaxOutputChars);
}
```

```java
@Bean
OfflineMcpTools offlineMcpTools(OfflineAnalysisService offlineAnalysisService,
        HeapDumpChunkRepository heapDumpChunkRepository,
        SharkHeapDumpSummarizer sharkHeapDumpSummarizer,
        HeapRetentionAnalyzer heapRetentionAnalyzer) {
    return new OfflineMcpTools(offlineAnalysisService, heapDumpChunkRepository, sharkHeapDumpSummarizer, heapRetentionAnalyzer);
}
```

- [ ] **Step 5: Extend registration/schema tests**

Add to the application test:

```java
.contains("validateOfflineAnalysisDraft", "submitOfflineHeapDumpChunk", "finalizeOfflineHeapDump",
        "generateOfflineTuningAdvice", "summarizeOfflineHeapDumpFile", "analyzeOfflineHeapRetention");
```

Add to schema contract:

```java
assertThat(def.description())
    .containsIgnoringCase("retention")
    .contains("reachableSubgraphBytesApprox")
    .containsIgnoringCase("shallow");
```

- [ ] **Step 6: Run the focused tests for tool wiring**

Run: `mvn "-Dtest=OfflineMcpToolsRetentionTest,McpToolSchemaContractTest,JavaTuningAgentApplicationTests test"`.

Expected: PASS.

- [ ] **Step 7: Commit the MCP slice**

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpTools.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpToolsRetentionTest.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/JavaTuningAgentApplicationTests.java
git commit -m "Expose heap retention analysis MCP tool"
```

---

### Task 4: Sync docs and verify the whole phase-1 slice

**Files:**
- Modify: `docs/offline-mode-spec.md`
- Modify: `docs/superpowers/specs/2026-04-19-offline-mode-design.md`
- Modify: `docs/superpowers/specs/2026-04-21-heap-retention-analysis-design.md`

- [ ] **Step 1: Update the user-facing offline spec**

Add a short section under offline heap analysis tools:

```md
### 5.x Heap retention analysis

- `summarizeOfflineHeapDumpFile` remains shallow-by-class only.
- `analyzeOfflineHeapRetention` is holder-oriented and may return approximate retained-style values.
- `reachableSubgraphBytesApprox` is not equivalent to exact retained size.
```

- [ ] **Step 2: Update the offline design doc so phase-1 scope is explicit**

Add a note near the `.hprof` analysis area:

```md
- Phase 1 adds an independent retention-analysis MCP tool and shared result model.
- Shared `MemoryGcEvidencePack` integration is deferred until the retention contract stabilizes.
```

- [ ] **Step 3: Update the new design spec with implementation-status notes**

Append:

```md
## Phase 1 implementation status

- Tool name finalized as `analyzeOfflineHeapRetention`
- Engine shipped as Shark-backed path analysis
- `retainedBytesApprox` may be null in phase 1
```

- [ ] **Step 4: Run the full focused regression suite for this feature**

Run: `mvn "-Dtest=SharkHeapRetentionAnalyzerTest,OfflineMcpToolsRetentionTest,McpToolSchemaContractTest,JavaTuningAgentApplicationTests,OfflineEvidenceAssemblerTest,JavaTuningWorkflowServiceTest test"`.

Expected: PASS.

- [ ] **Step 5: Run package if the workspace is clear of locked jars**

Run: `mvn -DskipTests package`.

Expected: `BUILD SUCCESS`.

If this fails due to a locked target jar on Windows, stop any lingering Java process using the packaged jar, rerun once, and record the cause in the final handoff.

- [ ] **Step 6: Commit the doc sync and verification slice**

```bash
git add docs/offline-mode-spec.md \
        docs/superpowers/specs/2026-04-19-offline-mode-design.md \
        docs/superpowers/specs/2026-04-21-heap-retention-analysis-design.md
git commit -m "Document heap retention analysis phase 1"
```

---

## Self-review

### Spec coverage

- New independent retention tool: Task 3
- Shared JSON evidence model: Task 1
- Shark-backed first implementation with explicit approximation semantics: Task 2
- Separation from shallow summary: Tasks 2, 3, and 4
- Future evidence-pack compatibility without immediate integration: file-structure note + Tasks 1 and 4
- Degradation/approximation wording: Tasks 2 and 4

### Placeholder scan

- No `TODO`, `TBD`, or "implement later" language remains in task steps.
- Every code-changing step includes a concrete Java or Markdown snippet.
- Every test step includes an exact Maven command and expected outcome.

### Type consistency

- MCP return type is `HeapRetentionAnalysisResult`.
- Nested payload is `HeapRetentionSummary`.
- Approximate byte fields use `retainedBytesApprox` and `reachableSubgraphBytesApprox` consistently.
- Tool name is `analyzeOfflineHeapRetention` everywhere in the plan.
