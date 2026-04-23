# Phase 2 Retention Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a shared deep-retention orchestration path that can try a heavier retained/dominator-style analysis for explicit `deep` requests while preserving phase-1 shallow defaults and reusing the same retention result contract across the standalone MCP tool and offline advice generation.

**Architecture:** This slice keeps `SharkHeapRetentionAnalyzer` as the lightweight engine, introduces a new orchestrator plus a heavyweight analyzer path, and wires both into the existing offline flow without changing the public result model. `analysisDepth=fast|balanced` stays phase-1-compatible and Shark-only; `analysisDepth=deep` becomes the explicit opt-in that first attempts the heavyweight engine and then falls back to Shark with clear warnings and confidence limits. Offline advice continues to auto-append shallow heap evidence by default, and only explicit deep requests pull retention analysis into the shared evidence pack and final report.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring AI MCP tools, LeakCanary Shark, JUnit 5, AssertJ, Mockito

**Implementation status:** Completed in branch `retention-phase-2`; task slices were committed as orchestration, dominator-style analyzer, offline advice reuse, report integration, and doc sync.

---

## File structure

### New files

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalysisRequest.java`
  - Shared normalized request for orchestration across MCP and offline advice flows.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalysisOrchestrator.java`
  - Routes `fast` / `balanced` / `deep` requests and owns fallback semantics.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/DominatorStyleHeapRetentionAnalyzer.java`
  - Heavy retained-style analyzer used only for explicit deep requests.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalysisOrchestratorTest.java`
  - Verifies routing, fallback, and wording behavior.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/DominatorStyleHeapRetentionAnalyzerTest.java`
  - Verifies the heavier engine yields stronger holder/retention evidence on a real heap fixture.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/HeapRetentionInsightsRuleTest.java`
  - Verifies advice findings generated from shared retention evidence.

### Modified files

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java`
  - Register Shark analyzer, heavy analyzer, and orchestrator beans.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpTools.java`
  - Reuse the orchestrated analyzer and add `analysisDepth` to offline advice generation.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/SharkHeapRetentionAnalyzer.java`
  - Narrow semantics to Shark-only behavior and preserve conservative wording on fallback.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineAnalysisService.java`
  - Trigger deep retention only for explicit deep requests and attach the result into shared evidence.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineEvidenceAssembler.java`
  - Add bounded assembly support for optional retention analysis.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`
  - Carry optional `HeapRetentionAnalysisResult` alongside existing shallow heap evidence.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowService.java`
  - Append retention-backed sections when evidence is present.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java`
  - Add a retention-aware diagnosis rule into the first-version rule set.
- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/DiagnosisConfidenceEvaluator.java`
  - Adjust confidence reasons and limitations for deep success vs fallback.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/TestHeapDumpSupport.java`
  - Extend fixtures so heavy analysis can show stronger retained-style evidence than Shark.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpToolsRetentionTest.java`
  - Cover deep routing through the MCP tool.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`
  - Cover the added `analysisDepth` advice parameter semantics.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/JavaTuningAgentApplicationTests.java`
  - Keep tool registration assertions in sync.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowServiceTest.java`
  - Cover retention-aware report formatting.
- `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngineTest.java`
  - Cover the new retention insights rule in the diagnosis engine.
- `docs/offline-mode-spec.md`
  - Document explicit deep behavior and advice-side retention reuse.
- `docs/superpowers/specs/2026-04-19-offline-mode-design.md`
  - Keep the offline design consistent with phase-2 integration.
- `docs/superpowers/specs/2026-04-22-phase-2-retention-analysis-design.md`
  - Record implementation-status decisions after the work lands.

### Files intentionally not modified in this slice

- `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/SharkHeapDumpSummarizer.java`

Reason: the shallow summary tool remains a separate shallow-by-class capability in phase 2. Deep retention should augment explicit deep flows, not redefine the shallow tool.

---

### Task 1: Add the deep-retention request contract and orchestrator

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalysisRequest.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalysisOrchestrator.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalysisOrchestratorTest.java`

- [ ] **Step 1: Write the failing orchestrator routing test before adding production code**

```java
@Test
void deepRequestsTryHeavyAnalyzerBeforeFallingBackToShark() {
    HeapRetentionAnalyzer heavy = mock(HeapRetentionAnalyzer.class);
    HeapRetentionAnalyzer shark = mock(HeapRetentionAnalyzer.class);
    given(heavy.analyze(any(), any(), any(), any(), any(), any()))
            .willReturn(new HeapRetentionAnalysisResult(false, "dominator-style", List.of("heavy failed"),
                    "out of memory", HeapRetentionSummary.emptyFailure(), ""));
    given(shark.analyze(any(), any(), any(), eq("deep"), any(), any()))
            .willReturn(sampleSharkFallbackResult());

    var orchestrator = new HeapRetentionAnalysisOrchestrator(shark, heavy);

    var result = orchestrator.analyze(Path.of("C:/tmp/demo.hprof"), 10, 12000, "deep", List.of(), List.of());

    verify(heavy).analyze(any(), any(), any(), eq("deep"), any(), any());
    verify(shark).analyze(any(), any(), any(), eq("deep"), any(), any());
    assertThat(result.engine()).contains("shark");
    assertThat(result.warnings()).anyMatch(it -> it.contains("fallback"));
}
```

- [ ] **Step 2: Run the routing test and confirm it fails because the orchestrator classes do not exist yet**

Run: `mvn "-Dtest=HeapRetentionAnalysisOrchestratorTest test"`.

Expected: FAIL with compilation errors for `HeapRetentionAnalysisRequest` or `HeapRetentionAnalysisOrchestrator`.

- [ ] **Step 3: Create the normalized request object so both entry points share one depth contract**

```java
public record HeapRetentionAnalysisRequest(
        Path heapDumpPath,
        Integer topObjectLimit,
        Integer maxOutputChars,
        String analysisDepth,
        List<String> focusTypes,
        List<String> focusPackages) {

    public HeapRetentionAnalysisRequest {
        analysisDepth = normalizeDepth(analysisDepth);
        focusTypes = focusTypes == null ? List.of() : List.copyOf(focusTypes);
        focusPackages = focusPackages == null ? List.of() : List.copyOf(focusPackages);
    }
}
```

- [ ] **Step 4: Implement the orchestrator with explicit depth routing**

```java
public final class HeapRetentionAnalysisOrchestrator implements HeapRetentionAnalyzer {

    @Override
    public HeapRetentionAnalysisResult analyze(Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars,
            String analysisDepth, List<String> focusTypes, List<String> focusPackages) {
        var request = new HeapRetentionAnalysisRequest(heapDumpPath, topObjectLimit, maxOutputChars,
                analysisDepth, focusTypes, focusPackages);
        return switch (request.analysisDepth()) {
            case "fast", "balanced" -> sharkAnalyzer.analyze(request.heapDumpPath(), request.topObjectLimit(),
                    request.maxOutputChars(), request.analysisDepth(), request.focusTypes(), request.focusPackages());
            case "deep" -> analyzeDeep(request);
            default -> sharkAnalyzer.analyze(request.heapDumpPath(), request.topObjectLimit(),
                    request.maxOutputChars(), "balanced", request.focusTypes(), request.focusPackages());
        };
    }
}
```

- [ ] **Step 5: Make deep fallback honest and stable**

Apply these rules in the orchestrator:

```java
private HeapRetentionAnalysisResult analyzeDeep(HeapRetentionAnalysisRequest request) {
    HeapRetentionAnalysisResult heavyResult = heavyAnalyzer.analyze(
            request.heapDumpPath(), request.topObjectLimit(), request.maxOutputChars(),
            "deep", request.focusTypes(), request.focusPackages());
    if (heavyResult.analysisSucceeded()) {
        return heavyResult;
    }
    HeapRetentionAnalysisResult sharkResult = sharkAnalyzer.analyze(
            request.heapDumpPath(), request.topObjectLimit(), request.maxOutputChars(),
            "deep", request.focusTypes(), request.focusPackages());
    return appendFallbackWarning(sharkResult, heavyResult);
}
```

```java
private HeapRetentionAnalysisResult appendFallbackWarning(
        HeapRetentionAnalysisResult sharkResult, HeapRetentionAnalysisResult heavyResult) {
    List<String> warnings = new ArrayList<>(sharkResult.warnings());
    warnings.add("Deep retained-style analysis fell back to Shark: " + heavyResult.errorMessage());
    return new HeapRetentionAnalysisResult(
            sharkResult.analysisSucceeded(),
            sharkResult.engine() + "+fallback",
            warnings,
            sharkResult.errorMessage(),
            sharkResult.retentionSummary(),
            sharkResult.summaryMarkdown());
}
```

- [ ] **Step 6: Wire the public bean to the orchestrator while keeping both concrete engines injectable**

```java
@Bean
SharkHeapRetentionAnalyzer sharkHeapRetentionAnalyzer(...) { ... }

@Bean
DominatorStyleHeapRetentionAnalyzer dominatorStyleHeapRetentionAnalyzer(...) { ... }

@Bean
HeapRetentionAnalyzer heapRetentionAnalyzer(
        SharkHeapRetentionAnalyzer sharkHeapRetentionAnalyzer,
        DominatorStyleHeapRetentionAnalyzer dominatorStyleHeapRetentionAnalyzer) {
    return new HeapRetentionAnalysisOrchestrator(sharkHeapRetentionAnalyzer, dominatorStyleHeapRetentionAnalyzer);
}
```

- [ ] **Step 7: Re-run the orchestrator routing test**

Run: `mvn "-Dtest=HeapRetentionAnalysisOrchestratorTest test"`.

Expected: PASS.

- [ ] **Step 8: Commit the orchestration slice**

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalysisRequest.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalysisOrchestrator.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapRetentionAnalysisOrchestratorTest.java
git commit -m "Add deep heap retention orchestrator"
```

---

### Task 2: Implement the heavyweight retained-style analyzer

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/DominatorStyleHeapRetentionAnalyzer.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/SharkHeapRetentionAnalyzer.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/TestHeapDumpSupport.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/DominatorStyleHeapRetentionAnalyzerTest.java`

- [ ] **Step 1: Write the failing heavy-engine test against a real heap fixture**

```java
@Test
void computesRetainedStyleBytesForDominatingStaticHolder(@TempDir Path dir) throws Exception {
    Path heap = TestHeapDumpSupport.dumpDominatingStaticOwnerHeap(dir);
    var analyzer = new DominatorStyleHeapRetentionAnalyzer(20, 12000);

    var result = analyzer.analyze(heap, 10, 12000, "deep", List.of("byte[]"), List.of());

    assertThat(result.analysisSucceeded()).isTrue();
    assertThat(result.engine()).isEqualTo("dominator-style");
    assertThat(result.retentionSummary().suspectedHolders())
            .anySatisfy(holder -> assertThat(holder.retainedBytesApprox()).isNotNull());
}
```

- [ ] **Step 2: Run the heavy-engine test and confirm it fails because the analyzer does not exist yet**

Run: `mvn "-Dtest=DominatorStyleHeapRetentionAnalyzerTest test"`.

Expected: FAIL with compilation errors for `DominatorStyleHeapRetentionAnalyzer`.

- [ ] **Step 3: Extend heap fixtures so the deep engine has a stable dominating-owner scenario**

Add a fixture that creates:
- one static owner retaining multiple large payload objects,
- a second branch with shared references to ensure the heavy path must distinguish reachable vs retained,
- deterministic cleanup so repeated tests stay stable.

Suggested helper shape:

```java
static Path dumpDominatingStaticOwnerHeap(Path dir) throws Exception {
    HeapFixtureHandle handle = HeapFixtureProcessMain.start("dominating-static-owner", dir);
    return handle.heapDumpPath();
}
```

- [ ] **Step 4: Implement the heavy analyzer with a retained-style graph pass**

The first implementation should:
- parse the `.hprof` with Shark,
- build an internal object/reference graph,
- compute an immediate-dominator style ownership approximation across the reachable subgraph,
- aggregate retained-style bytes by candidate holder,
- reuse the existing retention result records instead of inventing a second schema.

Skeleton:

```java
public final class DominatorStyleHeapRetentionAnalyzer implements HeapRetentionAnalyzer {

    @Override
    public HeapRetentionAnalysisResult analyze(Path heapDumpPath, Integer topObjectLimit, Integer maxOutputChars,
            String analysisDepth, List<String> focusTypes, List<String> focusPackages) {
        if (heapDumpPath == null || !Files.isRegularFile(heapDumpPath)) {
            return failure("Not a regular file: " + heapDumpPath);
        }
        // Parse heap, build predecessor graph, compute immediate dominators, aggregate retained-style bytes,
        // then render through the shared markdown renderer with stronger engine notes.
    }
}
```

- [ ] **Step 5: Make the heavy path stronger without overstating certainty**

Use wording rules like:

```java
new HeapRetentionConfidence(
        "medium",
        List.of("Retained-style bytes are computed from a local graph approximation, not MAT exact retained size."),
        List.of("Engine=dominator-style", "Deep analysis requested explicitly."));
```

Set:

```java
Long retainedBytesApprox = aggregatedRetainedBytes;
long reachableSubgraphBytesApprox = Math.max(aggregatedReachableBytes, aggregatedRetainedBytes);
```

- [ ] **Step 6: Keep Shark semantics conservative after heavy engine is introduced**

Update the Shark analyzer tests or implementation so Shark-only results still behave like:

```java
assertThat(holder.retainedBytesApprox()).isNull();
assertThat(result.engine()).isEqualTo("shark");
```

This prevents phase-2 work from silently changing phase-1 semantics.

- [ ] **Step 7: Re-run the heavy analyzer tests and the existing Shark retention tests**

Run: `mvn "-Dtest=DominatorStyleHeapRetentionAnalyzerTest,SharkHeapRetentionAnalyzerTest test"`.

Expected: PASS.

- [ ] **Step 8: Commit the heavy-engine slice**

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/DominatorStyleHeapRetentionAnalyzer.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/SharkHeapRetentionAnalyzer.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/TestHeapDumpSupport.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/DominatorStyleHeapRetentionAnalyzerTest.java
git commit -m "Add dominator-style heap retention analyzer"
```

---

### Task 3: Reuse deep retention in offline advice generation

**Files:**
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpTools.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineAnalysisService.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineEvidenceAssembler.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpToolsRetentionTest.java`

- [ ] **Step 1: Write the failing schema and service test for advice-side `analysisDepth`**

Schema assertion:

```java
case "generateOfflineTuningAdvice" -> {
    assertThat(schema.path("properties").path("analysisDepth").path("type").asText())
            .isEqualTo("string");
}
```

Service test assertion:

```java
verify(heapRetentionAnalyzer).analyze(any(), isNull(), isNull(), eq("deep"), any(), any());
```

- [ ] **Step 2: Run the focused tests and confirm they fail because advice does not accept `analysisDepth` yet**

Run: `mvn "-Dtest=McpToolSchemaContractTest,OfflineMcpToolsRetentionTest,OfflineEvidenceAssemblerTest test"`.

Expected: FAIL due to missing parameter or missing retention evidence plumbing.

- [ ] **Step 3: Add `analysisDepth` to the offline advice MCP surface**

Update `generateOfflineTuningAdvice(...)` so the new parameter is explicit and optional:

```java
@ToolParam(description = "Heap analysis depth: fast / balanced / deep. Only deep attempts heavier retention analysis.")
String analysisDepth
```

The default behavior should normalize blank input to `balanced`.

- [ ] **Step 4: Extend the shared evidence pack with optional retention evidence**

Preferred record shape:

```java
public record MemoryGcEvidencePack(
        JvmRuntimeSnapshot snapshot,
        ClassHistogramSummary classHistogram,
        ThreadDumpSummary threadDump,
        List<String> missingData,
        List<String> warnings,
        String heapDumpPath,
        HeapDumpShallowSummary heapShallowSummary,
        HeapRetentionAnalysisResult heapRetentionAnalysis) {

    public MemoryGcEvidencePack {
        heapRetentionAnalysis = heapRetentionAnalysis == null ? null : heapRetentionAnalysis;
    }
}
```

If needed, add an overload or static factory so existing call sites stay readable during the migration.

- [ ] **Step 5: Attach retention evidence only for explicit deep requests**

In `OfflineAnalysisService` or a bounded helper called from it:

```java
boolean deepRetentionRequested = "deep".equalsIgnoreCase(normalizedAnalysisDepth);
HeapRetentionAnalysisResult retention = deepRetentionRequested && hasHeapDumpPath(draft)
        ? heapRetentionAnalyzer.analyze(Path.of(draft.heapDumpAbsolutePath()), null, null, "deep", List.of(), candidatePackages)
        : null;
```

Then thread that result into `OfflineEvidenceAssembler`.

- [ ] **Step 6: Keep advice-side fallback visible**

If deep analysis falls back to Shark, make sure the shared pack keeps the warnings and the workflow later surfaces them in report text or confidence reasons. Do not silently discard `engine`, `warnings`, or `limitations`.

- [ ] **Step 7: Re-run the focused advice-plumbing tests**

Run: `mvn "-Dtest=OfflineMcpToolsRetentionTest,McpToolSchemaContractTest,OfflineEvidenceAssemblerTest test"`.

Expected: PASS.

- [ ] **Step 8: Commit the advice-plumbing slice**

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpTools.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineAnalysisService.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineEvidenceAssembler.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpToolsRetentionTest.java
git commit -m "Reuse deep heap retention in offline advice"
```

---

### Task 4: Turn retention evidence into report findings and readable Markdown

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/HeapRetentionInsightsRule.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/DiagnosisConfidenceEvaluator.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowService.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/HeapRetentionInsightsRuleTest.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngineTest.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowServiceTest.java`

- [ ] **Step 1: Write the failing rule and formatter tests first**

Rule test:

```java
@Test
void emitsFindingWhenRetentionEvidenceShowsLargeStaticHolder() {
    MemoryGcEvidencePack pack = samplePackWithRetention("dominator-style", 12 * 1024 * 1024L);

    var findings = new HeapRetentionInsightsRule().evaluate(pack);

    assertThat(findings).anySatisfy(finding -> assertThat(finding.title()).contains("retention"));
}
```

Workflow formatter test:

```java
assertThat(report.formattedSummary())
        .contains("Heap retention analysis")
        .contains("dominator-style");
```

- [ ] **Step 2: Run the focused report tests and confirm they fail because retention is not consumed yet**

Run: `mvn "-Dtest=HeapRetentionInsightsRuleTest,MemoryGcDiagnosisEngineTest,JavaTuningWorkflowServiceTest test"`.

Expected: FAIL because the rule, engine registration, or formatter section is missing.

- [ ] **Step 3: Add a dedicated rule for retention-backed findings**

The rule should:
- emit findings only when `heapRetentionAnalysis` exists and succeeded,
- distinguish heavy success from Shark fallback using `engine` and `warnings`,
- produce conservative wording when the result came from fallback.

Suggested shape:

```java
public final class HeapRetentionInsightsRule implements DiagnosisRule {

    @Override
    public List<DiagnosticFinding> evaluate(MemoryGcEvidencePack evidence) {
        HeapRetentionAnalysisResult retention = evidence.heapRetentionAnalysis();
        if (retention == null || !retention.analysisSucceeded()) {
            return List.of();
        }
        // Build one or more findings from suspected holders / root hints.
    }
}
```

- [ ] **Step 4: Register the rule and update confidence evaluation**

In `MemoryGcDiagnosisEngine.firstVersion()` add the new rule near the leak-oriented rules.

In `DiagnosisConfidenceEvaluator`, add logic like:

```java
if (pack.heapRetentionAnalysis() != null && pack.heapRetentionAnalysis().analysisSucceeded()) {
    reasons.add("Heap retention analysis provided holder-oriented evidence.");
}
if (pack.heapRetentionAnalysis() != null && pack.heapRetentionAnalysis().engine().contains("fallback")) {
    limitations.add("Deep retained-style analysis fell back to Shark, so retained-size evidence is limited.");
}
```

- [ ] **Step 5: Append a readable retention section to the final report**

In `JavaTuningWorkflowService`, append the retention markdown only when present:

```java
private String appendHeapRetentionSectionIfAny(String summary, MemoryGcEvidencePack evidence) {
    HeapRetentionAnalysisResult retention = evidence.heapRetentionAnalysis();
    if (retention == null || retention.summaryMarkdown().isBlank()) {
        return summary;
    }
    return summary + "\n\n" + retention.summaryMarkdown().trim();
}
```

- [ ] **Step 6: Re-run the focused reporting tests**

Run: `mvn "-Dtest=HeapRetentionInsightsRuleTest,MemoryGcDiagnosisEngineTest,JavaTuningWorkflowServiceTest test"`.

Expected: PASS.

- [ ] **Step 7: Commit the reporting slice**

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/HeapRetentionInsightsRule.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/DiagnosisConfidenceEvaluator.java \
        src/main/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowService.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/HeapRetentionInsightsRuleTest.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngineTest.java \
        src/test/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowServiceTest.java
git commit -m "Surface deep heap retention in advice reports"
```

---

### Task 5: Sync docs and verify the whole phase-2 slice

**Files:**
- Modify: `docs/offline-mode-spec.md`
- Modify: `docs/superpowers/specs/2026-04-19-offline-mode-design.md`
- Modify: `docs/superpowers/specs/2026-04-22-phase-2-retention-analysis-design.md`

- [ ] **Step 1: Update the offline user-facing spec**

Document:
- `summarizeOfflineHeapDumpFile` remains shallow-only,
- `analyzeOfflineHeapRetention(..., analysisDepth=deep, ...)` attempts deep retained-style analysis,
- `generateOfflineTuningAdvice(..., analysisDepth=deep)` reuses the same retention orchestration,
- deep fallback to Shark is possible and disclosed in warnings / confidence text.

- [ ] **Step 2: Update the offline design doc to reflect shared evidence-pack integration**

Add a note that phase 2 introduces optional retention evidence in `MemoryGcEvidencePack`, but only for explicit deep requests so the default offline path remains lightweight.

- [ ] **Step 3: Mark the approved phase-2 spec as implemented**

Append a status section similar to:

```md
## Implementation status

- Orchestrated deep routing landed
- Heavy retained-style engine is deep-only
- Offline advice can reuse retention evidence on explicit deep requests
- Default shallow behavior remains unchanged
```

- [ ] **Step 4: Run the focused regression suite for phase 2**

Run: `mvn "-Dtest=HeapRetentionAnalysisOrchestratorTest,DominatorStyleHeapRetentionAnalyzerTest,SharkHeapRetentionAnalyzerTest,OfflineMcpToolsRetentionTest,McpToolSchemaContractTest,OfflineEvidenceAssemblerTest,HeapRetentionInsightsRuleTest,MemoryGcDiagnosisEngineTest,JavaTuningWorkflowServiceTest,JavaTuningAgentApplicationTests test"`.

Expected: PASS.

- [ ] **Step 5: Run the full project test suite**

Run: `mvn test`.

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Run package if the Windows workspace is clear of locked jars**

Run: `mvn -DskipTests package`.

Expected: `BUILD SUCCESS`.

If packaging fails because a previous Java process still holds `target/*.jar`, stop the process, rerun once, and record the cause in the final handoff.

- [ ] **Step 7: Commit the doc sync and final verification slice**

```bash
git add docs/offline-mode-spec.md \
        docs/superpowers/specs/2026-04-19-offline-mode-design.md \
        docs/superpowers/specs/2026-04-22-phase-2-retention-analysis-design.md
git commit -m "Document phase 2 deep heap retention analysis"
```

---

## Self-review

### Spec coverage

- Dual-engine orchestration: Task 1
- Heavy engine introduced only for explicit `deep`: Tasks 1 and 2
- Shared result contract preserved: Tasks 1, 2, and 3
- Advice-side reuse only on explicit deep requests: Tasks 3 and 4
- Fallback disclosure through warnings / confidence / markdown: Tasks 1, 3, and 4
- Default shallow behavior unchanged: Tasks 2, 3, and 5

### Placeholder scan

- No `TODO`, `TBD`, or "figure out later" wording remains in task steps.
- Every task includes a concrete verification command.
- Each slice ends with a commit checkpoint so partial progress stays reviewable.

### Risk notes

- The heavy analyzer intentionally targets retained-style evidence, not a claim of MAT-exact retained size.
- Deep fallback must preserve conservative Shark wording; otherwise the report could overstate certainty.
- Advice integration must remain opt-in via `analysisDepth=deep` to avoid silently increasing offline analysis cost.
