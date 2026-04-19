# 离线分析模式 — 设计文档

**日期**：2026-04-19  
**状态**：设计与需求对齐稿；实现前需与 [`docs/offline-mode-spec.md`](../../offline-mode-spec.md) 一并评审。  
**受众**：实现者与 MCP 宿主侧（Cursor Agent / 编排逻辑）。

---

## 1. 工程上下文摘要

### 1.1 现有在线流水线（必须保持兼容）

- MCP 入口：`JavaTuningMcpTools` — `listJavaApps` → `inspectJvmRuntime(pid)` → `collectMemoryGcEvidence(request)` → `generateTuningAdvice(...)`。
- 诊断核心：`JavaTuningWorkflowService#generateAdviceFromEvidence(MemoryGcEvidencePack, CodeContextSummary, environment, optimizationGoal)`。
- 证据载体：`MemoryGcEvidencePack` = `JvmRuntimeSnapshot` + `ClassHistogramSummary` + `ThreadDumpSummary` + `missingData` / `warnings` + `heapDumpPath`。
- 在线路径下，`generateTuningAdvice` 在特权 flag 为真时先 `collectEvidence` 再 `generateAdviceFromEvidence`；否则仅构造轻量 `MemoryGcEvidencePack`（仅 snapshot）。

### 1.2 离线模式要解决的问题

在生产导出的文件已落地到分析机（或粘贴文本）的前提下，将**手工产物**映射为上述 **`MemoryGcEvidencePack`（或可诊断的等价物）**，并复用同一套诊断与 `TuningAdviceReportFormatter`，避免两条报告逻辑分叉。

---

## 2. 方案对比（2～3 种）

### 方案 A — 宿主纯编排 + 极少 MCP 扩展

**做法**：仅靠 Cursor Agent 技能与对话收集路径；大文件由用户先放到本地固定路径；MCP 只增加「单工具一次性提交路径集合」。

| 优点 | 缺点 |
|------|------|
| MCP 表面最小 | **分块/校验**难以在服务端统一实现；宿主提示逻辑易与实现漂移 |
| 改动面小 | 与用户要求的「新增工具 + 分块校验」耦合弱 |

**结论**：不满足「分块/校验应在产品中落实」与「专用工具」的明确要求，**不作为主方案**。

---

### 方案 B — 有状态服务端会话（session id）

**做法**：服务端保存 `OfflineSession`，多轮调用只带 `sessionId` 与小增量。

| 优点 | 缺点 |
|------|------|
| 真·多轮状态 | MCP 进程常无共享会话存储；扩容/重启丢状态；需TTL与清理 |
| 客户端极简 | 与当前「一次对话内由 Agent 握草稿」重叠，复杂度高 |

**结论**：除非产品明确要求跨进程持久化会话，否则 **不推荐首版**。

---

### 方案 C（推荐）— 无状态草稿 + 分块上传 + 终局分析

**做法**：

1. **校验/步进工具（可合并或拆分）**  
   接受一份 **OfflineBundleDraft** JSON：已填字段 + 缺失标记 + 推荐项「本次没有」标志。服务端只做校验、给出**中文** `nextPrompt` / `missingRequired` / `degradationNotes`，**不写服务器会话**。  
   *草稿的权威副本在对话层（Agent 内存），每轮可把整份草稿重发——符合「一次对话完成」且 MCP 无状态。*

2. **分块工具**  
   专门处理 `.hprof`：`uploadId`、`chunkIndex`、`chunkTotal`、`chunkBase64` 或文件片段、`algorithm`（如 SHA-256）、`finalize`。服务端拼临时文件并通过哈希校验；失败则返回可重试的该步错误。

3. **分析工具**  
   在草稿完整度满足「用户选择继续」策略后，将解析结果组装为 `MemoryGcEvidencePack`（无法结构化部分放入 `missingData`/`warnings`），调用 **`generateAdviceFromEvidence`**；`confirmationToken` 政策与线上特权采集**同一套字段语义**。

| 优点 | 缺点 |
|------|------|
| 与现有 `MemoryGcEvidencePack` / 诊断引擎对齐 | 需实现文本→`JvmRuntimeSnapshot` 的**解析或降级填充**（见 §4） |
| 分块与校验集中在服务端 | 工具数量 >1，需维护 JSON schema 与契约测试 |
| MCP 无状态，易测试 | Agent 侧要维护草稿对象（可由技能模板化） |

**首版推荐：方案 C**；若后续强需求再抽「会话存储」作为方案 B 的可选插件。

---

## 3. 推荐架构与组件边界

```
┌─────────────────────────────────────────────────────────────┐
│ Host (Cursor Agent + java-tuning-agent-workflow skill)       │
│  - 顺序向导、中文提示、回退、维护 OfflineBundleDraft            │
└──────────────────────────┬──────────────────────────────────┘
                           │ MCP JSON（全量草稿重发 或 分块上传）
┌──────────────────────────▼──────────────────────────────────┐
│ MCP: 新增工具（名称实现时 finalized）                           │
│  - validateDraft / stepResult  （可二合一）                     │
│  - appendHeapChunk / finalizeHeap  （可二合一）                 │
│  - generateOfflineTuningAdvice （或等价终局工具）                │
└──────────────────────────┬──────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────┐
│ Java 核心（现有 + 扩展）                                       │
│  - OfflineArtifactParser → 部分填充 JvmRuntimeSnapshot 等       │
│  - MemoryGcEvidencePack 构造                                   │
│  - JavaTuningWorkflowService.generateAdviceFromEvidence        │
└─────────────────────────────────────────────────────────────┘
```

**边界原则**：

- **向导 UX**（一步一步问什么）：宿主 Agent + 技能优先；MCP 返回机器可读 `missing*` 列表辅助，避免两套文案冲突。
- **字节完整性**：heap 分块仅在 **MCP 服务端**拼接与校验。
- **诊断与 Markdown 报告**：仅在现有引擎 + Formatter 输出，避免离线专用副本。

---

## 4. 文件 → 领域模型映射（技术风险点）

| 输入 | 目标 | 策略 |
|------|------|------|
| jcmd/jstat 纯文本导出 | `JvmRuntimeSnapshot` | **优先**：复用/扩展现有 parser（如 `GcHeapInfoParser`、`SafeJvmRuntimeCollector` 输出的结构若可捕获则直填）。**否则**：最小化 snapshot（pid 来自元数据、version/flags 用正则或子集解析）+ `warnings` 标明「快照为部分解析」。 |
| 类直方图文本 | `ClassHistogramSummary` | 复用现有直方图解析路径（与 `HistogramClassNames` 等一致）。 |
| 线程 dump 文本 | `ThreadDumpSummary` | 复用 `ThreadDumpParser`。 |
| `.hprof` 路径 | `heapDumpPath` + 可选后续分析 | 与线上一致存路径字符串；引擎若仅保留路径不做 MAT，须在 `missingData`/文档中说明限制。 |

**降级**：任一解析失败不阻断「用户选择继续」；写入 `warnings` / `missingData`，并由 `DiagnosisConfidenceEvaluator` 反映置信度。

---

## 5. Consent 与线上语义对齐

- **沿用** `confirmationToken` 非空规则：当离线分析等价于「使用_heap dump / 直方图 / 线程栈等敏感材料」时，终局工具要求与线上一致的 token 格式（可继续支持 `java-tuning-agent:ui-approval:v1:...`  canonical 形式，scope 映射为离线 `offlineHistogram,offlineThread,offlineHeap` 等 **实现时固化**）。
- **目的**：宿主与审计侧只理解一种同意模型；避免另起 `offlineConsent` 平行字段。

---

## 6. 错误处理与回退

- **分块校验失败**：返回明确错误码/文案；**仅重试当前上传任务**，不重跑已成功的其它草稿字段。
- **草稿回退**：宿主打补丁后重发全量草稿；服务端无历史版本，行为简单。
- **降级继续**：校验工具返回 `allowedToProceed: true` + `degradationWarnings`；终局工具尊重用户确认。

---

## 7. 测试策略（草案）

- **契约**：扩展现有 `McpToolSchemaContractTest` 覆盖新工具 JSON schema。
- **单元**：Offline 文本 → `MemoryGcEvidencePack` 的解析器；分块拼接与哈希。
- **集成**：给定 fixture 文件目录，调用 `generateAdviceFromEvidence`，断言 `formattedSummary` 固定章节存在且含预期缺失提示。

---

## 8. 与需求文档的职责划分

| 文档 | 职责 |
|------|------|
| [`docs/offline-mode-spec.md`](../../offline-mode-spec.md) | **用户可见需求**：必选/推荐/背景、向导行为、降级、验收清单。 |
| 本文 | **实现设计**：方案取舍、组件边界、映射与风险、测试方向。 |

若二者冲突，以 **spec** 为用户真理源；设计稿通过 PR 修订与 spec 同步。

---

## 9. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-04-19 | 初稿：brainstorming  Retrofit — 方案 A/B/C、推荐无状态草稿 + 分块工具、映射表、与 spec 分工。 |
