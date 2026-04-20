# 离线分析模式 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在无 live JVM 的前提下，通过 MCP 新增工具完成「离线草稿校验 / heap 分块上传与哈希校验 / 组装证据并生成与在线一致的 `TuningAdviceReport`」，且不改变现有四个在线工具的语义与默认行为。

**Architecture:** 采用设计稿 **方案 C**：服务端无会话；客户端（Agent）每轮可重发完整 `OfflineBundleDraft` JSON；heap 通过专用分块 API 落到服务端临时路径并在 `finalize` 时 SHA-256 校验；证据经 `OfflineEvidenceAssembler` 转为 `MemoryGcEvidencePack` 后仅调用既有 `JavaTuningWorkflowService#generateAdviceFromEvidence`。草稿校验返回结构化字段 + **中文** `nextPrompt`，供宿主逐步实现向导。

**Tech Stack:** Java 21（以 `pom.xml` 为准）、Spring Boot、Spring AI MCP (`MethodToolCallbackProvider`)、JUnit 5、AssertJ；哈希：`java.security.MessageDigest`（SHA-256）。

**真理源文档：** [`docs/offline-mode-spec.md`](../../offline-mode-spec.md)、[`docs/superpowers/specs/2026-04-19-offline-mode-design.md`](../specs/2026-04-19-offline-mode-design.md)。

---

## File map（实现前锁定职责）

| 路径 | 职责 |
|------|------|
| `src/main/java/.../offline/OfflineBundleDraft.java`（包名待定，建议 `...javatuning.offline`） | 草稿 DTO：`@JsonPropertyDescription` 供 MCP schema 生成；含 B1–B6、推荐项显式缺席、降级继续标志。 |
| `src/main/java/.../offline/OfflineArtifactSource.java` | 小记录：文件路径 **或** 内联文本二选一（校验互斥）。 |
| `src/main/java/.../offline/OfflineDraftValidationResult.java` | 校验输出：`missingRequired`、`degradationWarnings`、`nextPromptZh`、`allowedToProceed`、`completedStepIndex` 建议等。 |
| `src/main/java/.../offline/OfflineDraftValidator.java` | 纯逻辑：根据草稿内容计算缺失集、中文提示、是否允许继续。 |
| `src/main/java/.../offline/OfflineTextLoader.java` | 从路径或内联读取 UTF-8 文本；路径用 `Path.of` + `Files.readString`。 |
| `src/main/java/.../offline/OfflineJvmSnapshotAssembler.java` | 将「runtime 导出文本 + 元数据」组装为 **`JvmRuntimeSnapshot`**：优先复用 `GcHeapInfoParser`、`JstatGcUtilParser`、`VmVersionParser` 等对**子串**解析；解析不足时用占位 pid/空 gc + **warnings**（与设计稿 §4 一致）。 |
| `src/main/java/.../offline/OfflineEvidenceAssembler.java` | 合并 snapshot、histogram、`ThreadDumpParser`、heap 路径、`missingData`/`warnings` → `MemoryGcEvidencePack`。 |
| `src/main/java/.../offline/HeapDumpChunkRepository.java` | 内存持有 uploadId→临时目录与已收 chunk 元数据；`appendChunk`、`finalize`（拼文件 + SHA-256 与期望长度/哈希比对）；**TTL/单上传最大字节**常量可首版固定（如 512MB 上限，可测）。 |
| `src/main/java/.../offline/OfflineAnalysisService.java` | 门面：`validateDraft`、`generateAdvice(OfflineBundleDraft, CodeContextSummary, env, goal, token, proceedWithMissingRequired)`。 |
| `src/main/java/.../mcp/OfflineMcpTools.java` | `@Tool`：`validateOfflineAnalysisDraft`、`submitOfflineHeapDumpChunk`、`finalizeOfflineHeapDump`、`generateOfflineTuningAdvice`（名称实现时可微调，须与契约测试一致）。 |
| `src/main/java/.../config/JavaTuningAgentConfig.java` | 注册新 `@Bean`，`MethodToolCallbackProvider.builder().toolObjects(javaTuningMcpTools, offlineMcpTools).build()`（若 Spring AI 1.1.2 的 builder **仅支持单对象**，则将 `OfflineMcpTools` 合并进同一 `@Tool` 类或查阅 API 使用 `ToolCallbackProvider` 组合——**实现首步验证编译**）。 |
| `src/test/java/.../offline/*Test.java` | 校验器、分块仓库、组装器单元测试。 |
| `src/test/java/.../mcp/McpToolSchemaContractTest.java` | 扩展新 tool 的 schema 断言。 |
| `src/test/java/.../JavaTuningAgentApplicationTests.java` | 断言新 tool 名称已注册。 |
| `.cursor/skills/java-tuning-agent-workflow/SKILL.md`（或新 skill） | 增加离线模式段落：调用顺序、`confirmationToken` 与线上一致、中文引导。 |
| `mcps/user-java-tuning-agent/tools/*.json`（若仓库内维护） | 运行 `mcp-schema-export` 后复制新 schema 到 Cursor 工程中的 MCP 描述目录（与现有流程一致）。 |

---

### Task 1: 草稿与校验结果类型 + `OfflineDraftValidator` 单元测试（TDD 入口）

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineArtifactSource.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineBundleDraft.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineDraftValidationResult.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineDraftValidator.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineDraftValidatorTest.java`

- [ ] **Step 1: 写失败测试** — 断言「空草稿」返回缺失 B1–B6 列表、`allowedToProceed=false`（在未勾选降级时）、中文 `nextPrompt` 非空。

```java
package com.alibaba.cloud.ai.examples.javatuning.offline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineDraftValidatorTest {

	private final OfflineDraftValidator validator = new OfflineDraftValidator();

	@Test
	void emptyDraft_lists_required_ids_and_blocks_until_degradation_allowed() {
		OfflineBundleDraft draft = OfflineBundleDraft.empty();
		OfflineDraftValidationResult r = validator.validate(draft, false);
		assertThat(r.missingRequired()).isNotEmpty();
		assertThat(r.allowedToProceed()).isFalse();
		assertThat(r.nextPromptZh()).contains("必选");
	}

	@Test
	void proceedWithDegradation_true_allows_continue_despite_gaps() {
		OfflineBundleDraft draft = OfflineBundleDraft.empty();
		OfflineDraftValidationResult r = validator.validate(draft, true);
		assertThat(r.allowedToProceed()).isTrue();
		assertThat(r.degradationWarnings()).isNotEmpty();
	}
}
```

- [ ] **Step 2: 运行测试确认 RED**

Run: `mvn -q -Dtest=OfflineDraftValidatorTest test`

Expected: 编译失败或测试失败（类不存在）。

- [ ] **Step 3: 最小实现（完整类型）**

`OfflineArtifactSource.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.offline;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record OfflineArtifactSource(String filePath, String inlineText) {

	public OfflineArtifactSource {
		boolean hasPath = filePath != null && !filePath.isBlank();
		boolean hasInline = inlineText != null && !inlineText.isBlank();
		if (hasPath && hasInline) {
			throw new IllegalArgumentException("Specify only one of filePath or inlineText");
		}
	}

	public boolean isPresent() {
		return filePath != null && !filePath.isBlank() || inlineText != null && !inlineText.isBlank();
	}
}
```

`OfflineBundleDraft.java`（字段与需求 ID 对齐；Jackson 默认构造用于 MCP）：

```java
package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record OfflineBundleDraft(
		String jvmIdentityText,
		String jdkInfoText,
		String runtimeSnapshotText,
		OfflineArtifactSource classHistogram,
		OfflineArtifactSource threadDump,
		String heapDumpAbsolutePath,
		boolean explicitlyNoGcLog,
		boolean explicitlyNoAppLog,
		boolean explicitlyNoRepeatedSamples,
		String gcLogPathOrText,
		String appLogPathOrText,
		String repeatedSamplesPathOrText,
		Map<String, String> backgroundNotes) {

	public static OfflineBundleDraft empty() {
		return new OfflineBundleDraft(null, null, null, null, null, null, false, false, false, null, null, null,
				Map.of());
	}

	public OfflineBundleDraft {
		classHistogram = classHistogram == null ? new OfflineArtifactSource(null, null) : classHistogram;
		threadDump = threadDump == null ? new OfflineArtifactSource(null, null) : threadDump;
		backgroundNotes = backgroundNotes == null ? Map.of() : Map.copyOf(backgroundNotes);
	}
}
```

`OfflineDraftValidationResult.java`:

```java
package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.List;

public record OfflineDraftValidationResult(List<String> missingRequired, List<String> degradationWarnings,
		String nextPromptZh, boolean allowedToProceed, int suggestedStepIndex) {

	public OfflineDraftValidationResult {
		missingRequired = List.copyOf(missingRequired);
		degradationWarnings = List.copyOf(degradationWarnings);
	}
}
```

`OfflineDraftValidator.java`（必选检测规则：B1 `jvmIdentityText` 非空；B2 `jdkInfoText`；B3 `runtimeSnapshotText`；B4–B5 `OfflineArtifactSource.isPresent()`；B6 `heapDumpAbsolutePath` 非空 **或** 由后续分块 finalize 填入——首版可在 draft 中用 **占位路径字符串**表示「已 finalize」，实现时用单独字段更清晰：见 Task 4 引入 `pendingHeapUploadId`。为减少返工，**Task 1 仅校验：若 `heapDumpAbsolutePath` 为空则缺失 B6**；Task 4 完成后扩展「uploadId 已 finalize 等价于 B6 满足」。）

首版校验器逻辑示例：

```java
package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.ArrayList;
import java.util.List;

public class OfflineDraftValidator {

	public OfflineDraftValidationResult validate(OfflineBundleDraft draft, boolean proceedWithMissingRequired) {
		List<String> missing = new ArrayList<>();
		if (isBlank(draft.jvmIdentityText())) {
			missing.add("B1");
		}
		if (isBlank(draft.jdkInfoText())) {
			missing.add("B2");
		}
		if (isBlank(draft.runtimeSnapshotText())) {
			missing.add("B3");
		}
		if (!draft.classHistogram().isPresent()) {
			missing.add("B4");
		}
		if (!draft.threadDump().isPresent()) {
			missing.add("B5");
		}
		if (isBlank(draft.heapDumpAbsolutePath())) {
			missing.add("B6");
		}
		List<String> degradation = new ArrayList<>();
		if (!missing.isEmpty()) {
			degradation.add("必选材料不完整，分析置信度将降低。");
		}
		boolean allowed = proceedWithMissingRequired || missing.isEmpty();
		String prompt = buildPromptZh(missing, draft);
		return new OfflineDraftValidationResult(missing, degradation, prompt, allowed, suggestedStep(missing));
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}

	private static int suggestedStep(List<String> missing) {
		if (missing.contains("B1")) {
			return 0;
		}
		if (missing.contains("B2")) {
			return 1;
		}
		if (missing.contains("B3")) {
			return 2;
		}
		if (missing.contains("B4")) {
			return 3;
		}
		if (missing.contains("B5")) {
			return 4;
		}
		if (missing.contains("B6")) {
			return 5;
		}
		return 6;
	}

	private static String buildPromptZh(List<String> missing, OfflineBundleDraft draft) {
		if (missing.isEmpty()) {
			return "必选材料已齐，可生成分析报告或补充推荐项。";
		}
		return "请补全必选项：" + String.join("、", missing) + "。";
	}
}
```

- [ ] **Step 4: 运行测试 GREEN**

Run: `mvn -q -Dtest=OfflineDraftValidatorTest test`

Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/*.java src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineDraftValidatorTest.java
git commit -m "feat(offline): draft DTO and validator with tests"
```

---

### Task 2: `HeapDumpChunkRepository` — 分块拼接与 SHA-256 校验

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapDumpChunkRepository.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/HeapDumpChunkRepositoryTest.java`

- [ ] **Step 1: 写失败测试** — 两段 chunk 拼接后，`finalize(uploadId, expectedSha256)` 通过对；错误 `sha256` 抛 `IllegalArgumentException`。

```java
package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeapDumpChunkRepositoryTest {

	@Test
	void finalize_matches_sha256(@TempDir Path tmp) throws Exception {
		var repo = new HeapDumpChunkRepository(tmp);
		String id = repo.createUpload(2);
		repo.appendChunk(id, 0, "hello".getBytes(StandardCharsets.UTF_8));
		repo.appendChunk(id, 1, "world".getBytes(StandardCharsets.UTF_8));
		byte[] all = "helloworld".getBytes(StandardCharsets.UTF_8);
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		String expected = HexFormat.of().formatHex(md.digest(all));
		Path out = repo.finalize(id, expected, all.length);
		assertThat(out).exists();
	}
}
```

- [ ] **Step 2: RED** — `mvn -q -Dtest=HeapDumpChunkRepositoryTest test`（失败）。

- [ ] **Step 3: 实现 `HeapDumpChunkRepository`** — 要点：`createUpload(int totalChunks)`；每 chunk 写入 `uploadId/part-N.bin` 或单一 `RandomAccessFile`；`finalize` 拼接成 `heap-<uploadId>.hprof`，校验长度与 SHA-256；暴露 `Path getFinalPath(String uploadId)`。

- [ ] **Step 4: GREEN** — `mvn -q -Dtest=HeapDumpChunkRepositoryTest test`

- [ ] **Step 5: Commit** — `feat(offline): heap chunk repository with sha256 finalize`

---

### Task 3: `OfflineTextLoader` + `OfflineEvidenceAssembler`（_histogram/thread/snapshot）

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineTextLoader.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineEvidenceAssembler.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineEvidenceAssemblerTest.java`

- [ ] **Step 1: 测试** — fixture 字符串经 `ClassHistogramParser`（与 `JavaTuningWorkflowServiceTest` 相同用法）解析；线程 dump 用 `ThreadDumpParser`；`MemoryGcEvidencePack` 非 null。

参考现有测试：[`JavaTuningWorkflowServiceTest.java`](../../../src/test/java/com/alibaba/cloud/ai/examples/javatuning/agent/JavaTuningWorkflowServiceTest.java) 中 histogram 样例。

- [ ] **Step 2: 实现 `OfflineTextLoader.read(OfflineArtifactSource)`** — 路径则 `Files.readString(Path.of(filePath))`；内联则直接返回。

- [ ] **Step 3: 实现 `OfflineEvidenceAssembler`** — 构造函数注入 `OfflineJvmSnapshotAssembler`（Task 4）；方法 `MemoryGcEvidencePack build(OfflineBundleDraft draft, List<String> extraMissing, List<String> extraWarnings)`；合并 `Gc`/`missingData`；直方图/线程解析异常时写入 `warnings` + `missingData` 条目而非抛给 MCP（符合降级）。

- [ ] **Step 4: Commit** — `feat(offline): assemble evidence pack from draft`

---

### Task 4: `OfflineJvmSnapshotAssembler`

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineJvmSnapshotAssembler.java`
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineJvmSnapshotAssemblerTest.java`

- [ ] **Step 1: 从 `SafeJvmRuntimeCollector` 测试或真实 jcmd 样例拷贝一段 `heapInfo` / `gcUtil` 文本 fixture**（可放在 `src/test/resources/offline/sample-heap-info.txt`）。

- [ ] **Step 2: 实现组装器** — 使用现有 `GcHeapInfoParser`、`JstatGcUtilParser`（见 [`SafeJvmRuntimeCollector.java`](../../../src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java) 第 55–65 行用法）；`pid` 从 `jvmIdentityText` 用 `\b(\d+)\b` 尝试解析，失败则用 `0L` 并在 `warnings` 添加说明；`JvmCollectionMetadata` 可用静态工厂标明 **offlineImport**。

- [ ] **Step 3: Commit** — `feat(offline): build partial JvmRuntimeSnapshot from exported text`

---

### Task 5: 扩展 `OfflineBundleDraft` + 校验器 — 支持 `heapUploadId` / finalize 路径

**Files:**
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineBundleDraft.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineDraftValidator.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineDraftValidatorTest.java`

- [ ] **Step 1:** 为 draft 增加可选字段 `String finalizedHeapDumpPath`（由 MCP 在 finalize 后告知用户写回草稿）**或** `String heapUploadId` 表示已上传待引用 — 二选一成文；推荐 **finalize 返回绝对路径字符串**，用户/Agent 下一轮把该路径填入 `heapDumpAbsolutePath`。

- [ ] **Step 2:** 校验 B6：路径非空即可；文档在 `OfflineMcpTools` 描述中说明与分块流程配合。

- [ ] **Step 3: Commit** — `fix(offline): validator accepts heap path after chunked upload`

---

### Task 6: `OfflineAnalysisService` + `OfflineMcpTools` + Spring 注册

**Files:**
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineAnalysisService.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpTools.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/config/JavaTuningAgentConfig.java`

- [ ] **Step 1: `OfflineAnalysisService`** — 依赖 `OfflineDraftValidator`、`OfflineEvidenceAssembler`、`HeapDumpChunkRepository`、`JavaTuningWorkflowService`。方法：
  - `OfflineDraftValidationResult validate(OfflineBundleDraft draft, boolean proceedWithMissingRequired)`
  - `TuningAdviceReport generate(OfflineBundleDraft draft, CodeContextSummary ctx, String environment, String optimizationGoal, String confirmationToken, boolean proceedWithMissingRequired)`：先校验；若不 `allowed` 则抛 `IllegalArgumentException`；特权材料（histogram/thread/heap）存在时 **要求** `confirmationToken` 非空（镜像 `JavaTuningMcpTools.generateTuningAdvice` 第 80–84 行逻辑）。
  - 将推荐项「本次没有」注入 `MemoryGcEvidencePack.missingData`（例如 `"gcLog: explicitly absent"`）通过 assembler 附加。

- [ ] **Step 2: `OfflineMcpTools` 四个 `@Tool`**（描述用中文简述 + 英文技术名）：
  1. `validateOfflineAnalysisDraft(draft, proceedWithMissingRequired)`
  2. `submitOfflineHeapDumpChunk(uploadId, chunkIndex, chunkTotal, chunkBase64)` — `uploadId` 空则 `createUpload`
  3. `finalizeOfflineHeapDump(uploadId, expectedSha256Hex, expectedSizeBytes)`
  4. `generateOfflineTuningAdvice(codeContextSummary, draft, environment, optimizationGoal, confirmationToken, proceedWithMissingRequired)`

- [ ] **Step 3: `JavaTuningAgentConfig`** — `@Bean OfflineMcpTools`、`@Bean HeapDumpChunkRepository(Path tempDir)`（`@Value("${java-tuning-agent.offline.upload-dir:${java.io.tmpdir}}")` 拼接子目录）；`toolCallbackProvider` 注册双对象。

- [ ] **Step 4: 编译** — `mvn -q -DskipTests compile`

- [ ] **Step 5: Commit** — `feat(offline): MCP tools and OfflineAnalysisService`

---

### Task 7: 契约测试与 Spring 集成测试

**Files:**
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/McpToolSchemaContractTest.java`
- Modify: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/JavaTuningAgentApplicationTests.java`
- Optional: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/mcp/OfflineMcpToolsTest.java`

- [ ] **Step 1:** `JavaTuningAgentApplicationTests` 的 `contains` 增加：`validateOfflineAnalysisDraft`、`submitOfflineHeapDumpChunk`、`finalizeOfflineHeapDump`、`generateOfflineTuningAdvice`。

- [ ] **Step 2:** `McpToolSchemaContractTest` 为新 tool 增加 `switch` 分支断言 `properties` 关键字段（参照现有 `generateTuningAdvice` 写法）。

- [ ] **Step 3:** `mvn -q test`

- [ ] **Step 4: Commit** — `test(offline): MCP schema contract and tool registration`

---

### Task 8: 宿主文档 — Cursor Skill

**Files:**
- Modify: `.cursor/skills/java-tuning-agent-workflow/SKILL.md`

- [ ] **Step 1:** 增加章节 **「离线模式（独立）」**：固定顺序（B1→…→B6→推荐项→背景）；每步可路径或粘贴；调用 `validateOfflineAnalysisDraft` 直至 `missingRequired` 空或用户确认降级；heap 走 `submit`+`finalize` 后把路径写入 draft；最后 `generateOfflineTuningAdvice`，`confirmationToken` 规则与 step-3 gate 一致（使用 canonical token，scopes 含离线材料）。

- [ ] **Step 2: Commit** — `docs(skill): offline analysis workflow`

---

### Task 9: MCP JSON schema 导出与 Cursor `mcps` 同步说明

**Files:**
- README 或 `docs/offline-mode-spec.md` §9 追加一句运行说明（若不愿改 spec，可在 `docs/mcp-jvm-tuning-demo-walkthrough.md` -append）

- [ ] **Step 1:** 运行 `mvn ... -Pmcp-schema-export`（以 `pom.xml` 中实际 profile 为准）生成 `target/mcp-tool-schemas.json`。

- [ ] **Step 2:** 将新工具 schema 同步到开发者 Cursor 工程的 `mcps/user-java-tuning-agent/tools/*.json`（与现有 demo 流程一致）。

- [ ] **Step 3: Commit** — `docs: sync MCP schema export instructions for offline tools`

---

## Spec coverage（自检）

| 需求（spec） | Task |
|----------------|------|
| 独立模式 + 在线兼容 | Task 6 仅新增 bean/tool，不修改在线四工具行为 |
| 必选/推荐/背景 | Task 1 draft 字段；推荐显式缺席；assembler 注入 missingData |
| 降级继续 | Task 1 `proceedWithMissingRequired` |
| 分块/校验 | Task 2 + Task 6 finalize |
| 中文提示 | Task 1 `nextPromptZh` |
| consent 一致 | Task 6 `confirmationToken` 校验 |
| 一次对话 | 文档 Task 8（Agent 维护草稿，无服务端 session） |

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-04-19-offline-mode.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — Dispatch a fresh subagent per task, review between tasks (see **subagent-driven-development** skill).

2. **Inline Execution** — Run tasks in this session using **executing-plans**, batch with checkpoints.

**Which approach?**

---

## 实现后补充：堆转储自动浅层摘要（Shark）

**目标**：当 `heapDumpAbsolutePath` / 在线 `GC.heap_dump` 产生有效 `.hprof` 文件时，**自动**用 LeakCanary **Shark** 做浅层按类统计，写入 `MemoryGcEvidencePack.heapShallowSummary`，供 `MemoryGcDiagnosisEngine`（如 `HeapDumpShallowDominanceRule`）与 `formattedSummary` 使用；原始 dump 不进入 LLM。

**相关实现要点**（与初版计划相比为增量）：

- 依赖：`com.squareup.leakcanary:shark-graph` + `kotlin-stdlib`（`pom.xml`）。
- 配置：`java-tuning-agent.heap-summary.auto-enabled`（默认 `true`）、`java-tuning-agent.offline.heap-summary.default-top-classes`、`default-max-output-chars`。
- 工具：保留 `summarizeOfflineHeapDumpFile` 用于**单独**摘要；终局 **`generateOfflineTuningAdvice` 不再需要** `attachHeapDumpSummary` 之类开关——自动摘要由组装器/采集器完成。
- 文档：`README.md`、`docs/offline-mode-spec.md` §5.1、`docs/superpowers/specs/2026-04-19-offline-mode-design.md`、本 skill/reference 已同步。
