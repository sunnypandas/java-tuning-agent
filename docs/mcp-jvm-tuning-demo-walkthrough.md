# MCP JVM Tuning Demo Walkthrough

这份文档是一份可直接照着操作的分享脚本，目标是完成三段演示：

1. 先展示当前注册的 MCP，并重点介绍 `java-tuning-agent` 以及它暴露的 tool（**共 9 个**：4 个在线 JVM 链路 + 5 个离线导入；本脚本 **主线演示在线 4 步**）
2. 先手动完成一次在线 JVM tuning 流程
3. 补充一段离线模式导入材料测试，再用 `.cursor` 里的 skill 演示一次全流程

建议把整场分享控制在 10 到 15 分钟，保持“先认知，再操作，再自动化”的节奏。

## Demo Storyline

建议按下面 4 段来讲：

1. `MCP 是什么`
  先展示当前客户端里注册了哪些 MCP server，说明 `java-tuning-agent` 只是其中之一
2. `java-tuning-agent 能做什么`
  简单介绍在线排查链路的 **4** 个核心 tool（完整能力含离线共 **9** 个，见仓库 [README](../README.md)）
3. `在线流程怎么落地`
  先手动完成一次在线排查流程
4. `离线流程怎么落地`
  演示离线草稿校验、可选 heap dump 分块上传、离线结论生成，再让 Agent 通过 skill 自动跑在线全流程

## Demo Preparation

### 1. 确认 `java-tuning-agent` MCP 已注册

本仓库当前的 Inspector 配置在 [inspector-mcp-main.json](/c:/Users/panpa/Workspace/java-tuning-agent/inspector-mcp-main.json:1)。

里面注册的是：

```json
{
  "mcpServers": {
    "java-tuning-agent": {
      "type": "stdio",
      "command": "java",
      "args": [
        "-Dspring.ai.mcp.server.stdio=true",
        "-Dspring.main.web-application-type=none",
        "-Dspring.main.banner-mode=off",
        "-Dspring.main.keep-alive=true",
        "-jar",
        "C:/Users/panpa/Workspace/java-tuning-agent/target/java-tuning-agent-0.0.1-SNAPSHOT.jar"
      ]
    }
  }
}
```

如果你现场使用的是 MCP Inspector，可以直接加载这个配置。

### 2. 启动 `memory-leak-demo`

在仓库根目录执行：

```powershell
mvn -f compat/memory-leak-demo/pom.xml spring-boot:run "-Dspring-boot.run.jvmArguments=-Xms128m -Xmx256m -XX:+UseG1GC"
```

这个 demo 默认监听 `8091`，并且故意提供几类适合诊断的场景：保留 `byte[]`、高堆占用、可选死锁。

### 3. 预先制造一轮可观测现象

为了让后面的 tuning 演示更稳定，建议先打几次流量：

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/allocate -H "Content-Type: application/json" -d "{\"entries\":120,\"payloadKb\":512,\"tag\":\"round-1\"}"
```

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/raw/allocate -H "Content-Type: application/json" -d "{\"entries\":200,\"payloadKb\":256,\"tag\":\"raw-b\"}"
```

如果你准备在分享里展示 `thread dump` 和死锁识别，再执行一次：

```powershell
curl.exe -X POST http://localhost:8091/api/leak/deadlock/trigger
```

### 4. 确认 `.cursor` 里的 skill 和 rule

本次全流程演示会用到：

- [java-tuning-agent-workflow skill](/c:/Users/panpa/Workspace/java-tuning-agent/.cursor/skills/java-tuning-agent-workflow/SKILL.md:1)
- [tool 参数参考](/c:/Users/panpa/Workspace/java-tuning-agent/.cursor/skills/java-tuning-agent-workflow/reference.md:1)
- [项目规则](/c:/Users/panpa/Workspace/java-tuning-agent/.cursor/rules/java-tuning-agent-mcp.mdc:1)

你可以顺手提一句：这个 skill 约束了 Agent 必须按固定流程调用 **在线**这 4 个 tool（离线另有五条工具链），而不是随便跳步骤。

## Part 1: 展示当前注册的 MCP

### 操作

在你使用的 MCP 客户端里打开 server 列表。

如果你用的是 MCP Inspector，就加载 [inspector-mcp-main.json](/c:/Users/panpa/Workspace/java-tuning-agent/inspector-mcp-main.json:1)，然后展示当前 server。

### 讲解词

可以直接这样说：

> 这里先不急着看 JVM tuning，本质上我们先看 MCP 生态。  
> 当前客户端里可以注册多个 MCP server，`java-tuning-agent` 只是其中一个。  
> 它的定位不是改代码，而是把本机 JVM 的诊断能力包装成标准 MCP tool，让 Agent 可以按流程调用。

## Part 2: 介绍 `java-tuning-agent`（在线四条主线）

完整注册表里还有 **5 个离线 tool**（草稿校验、heap 分块上传与 finalize、离线 `generateOfflineTuningAdvice`、可选 `summarizeOfflineHeapDumpFile`），本段不展开，详见 [README](../README.md) 与 [offline-mode-spec.md](../offline-mode-spec.md)。

### 讲解重点

在线排查的 **4** 个 tool 建议按这个顺序讲：


| Tool                      | 作用               | 现场怎么讲                                      |
| ------------------------- | ---------------- | ------------------------------------------ |
| `listJavaApps`            | 列出当前用户可见的 JVM 进程 | 先找到“要分析谁”                                  |
| `inspectJvmRuntime`       | 做一次轻量只读快照        | 先看基础运行状态                                   |
| `collectMemoryGcEvidence` | 采集中等成本证据         | 需要时再拿 histogram、thread dump、heap dump      |
| `generateTuningAdvice`    | 产出结构化分析结论        | 最后把现象归纳成 findings、recommendations、hotspots |

若现场有人问 **heap dump**：补充说明采集到 `.hprof` 后，服务端在默认配置下会用 **Shark** 做**浅层**按类摘要并写进报告（非 MAT dominator）；详见 README。

### 讲解词

> 这 4 个 tool 对应的是一个很自然的排查链路：  
> 先找进程，再看快照，再按需补证据，最后生成分析结论。  
> 所以它不是一个“单点工具”，而是一条可组合、可编排的 tuning workflow。

## Part 3: 手动完成一次 JVM tuning 流程

这一段建议你在 MCP Inspector 或任何支持手动交互 MCP 的客户端里演示。

这里尽量不要站在“我现在去调用哪个 tool”的角度来表达，而是站在“用户现在想解决什么问题”的角度来表达。  
你自己知道背后对应的是哪一个 tool 就够了，但现场更自然的说法应该是：

- 我先找到我要分析的 JVM
- 我先看一下当前运行状态
- 我觉得还不够，再补一些更强的证据
- 最后请系统帮我总结结论

### Step 1. 先定位我要分析的那个 JVM

#### 你可以这样触发

在支持手动交互 MCP 的界面里，先表达一个非常自然的诉求：

> 帮我看看当前机器上有哪些 Java 进程，我要找 `memory-leak-demo`

如果你用的是 Inspector 一类偏底层的界面，也可以理解成“先做进程发现”。

#### 预期

你应该能看到多个 JVM，其中重点关注：

- `com.alibaba.cloud.ai.compat.memoryleakdemo.MemoryLeakDemoApplication`
- 可能还会看到 `java-tuning-agent` 自己
- 也可能看到 IDE 的 Java language server

#### 讲解词

> 第一步很朴素，就是先定位目标 JVM。  
> 真实用户并不会先想“我要调哪个 tool”，而是先想“我要分析哪一个 Java 进程”。

### Step 2. 先看一眼这个 JVM 当前的运行状态

#### 你可以这样触发

当你已经知道目标 PID 之后，继续表达下一个意图：

> 先给我一份这个 JVM 的轻量快照（PID：**24980**），我想先看看当前堆使用和 GC 情况

如果你在 Inspector 里操作，本质上就是对刚才选中的 PID 做一次轻量只读检查。

#### 预期

你会看到一份轻量只读快照，典型字段包括：

- `heapUsedBytes`
- `heapMaxBytes`
- `youngGcCount`
- `fullGcCount`
- `oldUsagePercent`
- `vmFlags`

#### 讲解词

> 这一步先不做重型采集，只先看 baseline。  
> 用户真正想表达的是“这台 JVM 现在健康吗”，而不是“请帮我执行某个叫 inspect 的接口”。

### Step 3. 当快照显示有压力时，再补更多证据

#### 你可以这样触发

如果前一步已经看出堆压力偏高、Full GC 偏多，或者你已经知道这个 demo 有疑似泄漏和死锁风险，就继续表达：

> 现在我想补更多证据，看看对象分布、线程状态，以及必要时导出 heap dump

如果现场你想把范围讲得更明确，也可以补一句：

> 这次我希望把 histogram、thread dump 和 heap dump 都拿到

如果需要指定 heap dump 输出路径，Windows 下可以用：

```text
C:/Users/panpa/AppData/Local/Temp/java-tuning-agent-heap-<pid>.hprof
```

#### 预期

你可以重点指出这几块输出：

- `classHistogram.entries`
- `threadDump.deadlockHints`
- `heapDumpPath`
- `missingData`
- `warnings`

如果你前面已经触发了 `deadlock/trigger`，这里通常能看到死锁提示。  
如果你前面已经打过 `allocate` 和 `raw/allocate`，这里通常能看到 `byte[]` 或相关对象占用较高。

#### 讲解词

> 这一步的用户意图不是“我要调一个证据采集 tool”，而是“当前快照还不够，我需要更强的证据来判断问题在哪里”。  
> 这也正好体现了这个 agent 的边界感：先轻量观察，再按需升级证据。

### Step 4. 最后让系统给出结构化分析结论

#### 你可以这样触发

当前面已经拿到了快照和补充证据之后，最后表达一个最贴近用户语言的诉求：

> 结合刚才的运行时信息和项目源码上下文，帮我总结这次 JVM tuning 的分析结论，源码在 `compat/memory-leak-demo`，请把可能的热点类也一起标出来

#### 预期

重点展示这些字段：

- `findings`
- `recommendations`
- `suspectedCodeHotspots`
- `confidence`
- `formattedSummary`

#### 讲解词

> 到这一步，用户想要的已经不是原始指标，而是一份能直接阅读、直接解释的分析结论。  
> 所以我们从“找进程、看快照、补证据”一路走到这里，最后落成的是一份结构化 report。

### 手动流程里建议强调的观察点

结合 `memory-leak-demo`，你大概率可以讲到这些现象：

- 堆压力较高
- `old gen` 占比偏高
- class histogram 里 `byte[]` 很突出
- 如果提前触发过死锁，thread dump 会明确给出 deadlock hints
- `sourceRoots` 提供后，报告会尝试把热点类映射回源码文件

### 可选补充说明

如果现场有人问“为什么前面已经做过证据采集，后面系统仍然还能继续生成分析结论”，可以这样回答：

> 当前设计里，前面几步负责逐步拿到运行时事实，最后一步负责把这些事实组织成结构化结论。  
> 今天为了把整条 workflow 讲清楚，我们显式把过程拆开演示了一遍。  
> 但对真实用户来说，他看到的是一条诊断流程，不需要先知道每一步背后叫什么名字。

## Part 4: 离线模式测试步骤（无本机目标 PID）

这段用于演示“生产导出的诊断材料”如何离线导入并生成结论，不依赖当前机器存在目标 Java 进程。

### 适用场景

- 线上 JVM 不能直接连，只有导出的文本与 `.hprof`
- 想复盘历史问题（incident postmortem）
- 希望在分享中对比“在线链路 vs 离线链路”

### 离线链路（建议讲解顺序）

建议按下面顺序演示（对应 5 个离线 tool）：

1. `validateOfflineAnalysisDraft`
2. `submitOfflineHeapDumpChunk`（可选，heap dump 很大时）
3. `finalizeOfflineHeapDump`（仅分块上传时）
4. `summarizeOfflineHeapDumpFile`（可选，仅看浅层摘要）
5. `generateOfflineTuningAdvice`

### Step 1. 先构建离线草稿并做一次校验

先准备一个 `draft`（最小建议先填 B1-B6）：

- `jvmIdentityText`（B1）
- `jdkInfoText`（B2）
- `runtimeSnapshotText`（B3）
- `classHistogram`（B4，`filePath` 或 `inlineText`）
- `threadDump`（B5，`filePath` 或 `inlineText`）
- `heapDumpAbsolutePath`（B6，可先为空字符串，后续再填）

推荐项 R1-R3 也要二选一填写：

- 提供 `gcLogPathOrText`，或 `explicitlyNoGcLog=true`
- 提供 `appLogPathOrText`，或 `explicitlyNoAppLog=true`
- 提供 `repeatedSamplesPathOrText`，或 `explicitlyNoRepeatedSamples=true`

首次校验建议 `proceedWithMissingRequired=false`，先看缺失项：

```json
{
  "draft": {
    "jvmIdentityText": "...",
    "jdkInfoText": "...",
    "runtimeSnapshotText": "...",
    "classHistogram": { "filePath": "C:/demo/offline/class-histogram.txt", "inlineText": "" },
    "threadDump": { "filePath": "C:/demo/offline/thread-dump.txt", "inlineText": "" },
    "heapDumpAbsolutePath": "",
    "gcLogPathOrText": "",
    "appLogPathOrText": "",
    "repeatedSamplesPathOrText": "",
    "explicitlyNoGcLog": true,
    "explicitlyNoAppLog": true,
    "explicitlyNoRepeatedSamples": true,
    "backgroundNotes": {}
  },
  "proceedWithMissingRequired": false
}
```

#### 预期

- 返回 `missingRequired`（若不为空，先补齐或确认降级）
- 返回 `allowedToProceed` 与 `nextPromptZh`

### Step 2. （可选）大文件 heap dump 走分块上传

如果 `.hprof` 无法直接放到 MCP 服务端可访问路径，就走分块：

1. 首块调用 `submitOfflineHeapDumpChunk`：`uploadId=""`，并传总分块数 `chunkTotal`
2. 后续分块复用返回的 `uploadId`，按 `chunkIndex=0..chunkTotal-1` 提交
3. 全部提交后调用 `finalizeOfflineHeapDump`

`finalizeOfflineHeapDump` 成功后会返回 `finalizeHeapDumpPath`，把它写回 `draft.heapDumpAbsolutePath`。

### Step 3. （可选）先预览 heap dump 浅层摘要

如果现场想先展示“堆里是什么类型占用大”，可以先调用：

- `summarizeOfflineHeapDumpFile(heapDumpAbsolutePath, topClassLimit, maxOutputChars)`

这一步返回的是 Shark 的**浅层按类统计**（非 MAT dominator/retained 分析），适合作为分享中的“快速预读”。

### Step 4. 生成离线 tuning 建议

准备 `codeContextSummary` 后调用 `generateOfflineTuningAdvice`：

- 若 `draft` 中包含 class histogram / thread dump / heap dump 路径，`confirmationToken` 需要非空
- 若你决定“缺项也继续”，把 `proceedWithMissingRequired=true`

示例（含源码上下文）：

```json
{
  "codeContextSummary": {
    "dependencies": [],
    "configuration": {},
    "applicationNames": ["MemoryLeakDemoApplication"],
    "sourceRoots": ["C:/Users/panpa/Workspace/java-tuning-agent/compat/memory-leak-demo/src/main/java"],
    "candidatePackages": ["com.alibaba.cloud.ai.compat.memoryleakdemo"]
  },
  "draft": { "...": "使用上一步确认后的完整 draft" },
  "environment": "prod",
  "optimizationGoal": "diagnose memory leak and reduce full gc",
  "confirmationToken": "offline-approved-by-user",
  "proceedWithMissingRequired": false
}
```

#### 预期

重点展示输出：

- `findings`
- `recommendations`
- `suspectedCodeHotspots`
- `confidence`
- `formattedSummary`

如果 `heapDumpAbsolutePath` 指向有效 `.hprof`，报告末尾通常会追加 heap shallow summary 小节。

### 离线模式分享讲解词（可直接照读）

> 离线模式的核心不是“连在线 JVM”，而是“把现场导出的诊断证据重新组织成结构化结论”。  
> 我们先校验草稿完整性，再按需上传/合并 heap dump，最后统一生成 tuning report。  
> 这样线上环境不可直连时，团队仍然可以把分析流程标准化、可复用。

## Part 5: 用 skill 演示一次全流程

这一段建议在 Cursor Agent 里演示，因为仓库里已经配好了 `.cursor` skill 和 rule。

### 操作

在 Agent chat 里直接输入：

```text
对memory-leak-demo进行jvm tuning，源代码位于compat\memory-leak-demo
```

如果你想显式展示 skill，也可以这样输入：

```text
/java-tuning-agent-workflow 对memory-leak-demo进行jvm tuning，源代码位于compat\memory-leak-demo
```

### 预期流程

Agent 应该会按固定顺序走：

1. 先识别目标 JVM
2. 再拿轻量快照
3. 询问你是否要补更强的证据
4. 再做证据采集
5. 最后生成结构化分析结论

如果你希望补更完整的证据，直接回复：

```text
4
```

也就是选择：

- `class histogram`
- `thread dump`
- `heap dump`

### 讲解词

> 刚才我们是手动把流程拆开，一步一步演示。  
> 现在换成 skill 驱动，Agent 会自己按规则编排流程。  
> 这里的重点不是“模型会聊天”，而是“模型会按项目内置 workflow 安全地调用 MCP tools”。

### 这一段建议强调的价值

- 不是把 tool 藏起来，而是把（在线）调用顺序产品化
- 不是让 Agent 自由发挥，而是让它遵守 skill 和 rule
- 手动流程适合教学和调试
- skill 流程适合真实使用和复用

## Recommended Live Script

如果你想把全程压缩成一段比较自然的分享，可以照下面的节奏：

1. “先看 MCP 列表，我们的 `java-tuning-agent` 是其中一个 server。”
2. “在线排查链路是 4 个 core tool（整站还注册离线等共 9 个），分别负责找 JVM、看快照、补证据、出结论。”
3. “我先手动演示一遍，但会用用户语言来描述诉求，而不是直接念 tool 名字。”
4. “接着演示离线模式：校验草稿、可选上传 heap dump、最后生成离线结论。”
5. “现在再用 skill 跑一次在线流程，你会发现 Agent 已经会自己按这条流程去编排。”
6. “这就是把 JVM tuning 经验沉淀成 MCP tool 和 skill 之后的价值。”

## Troubleshooting During Demo

### 没看到 `memory-leak-demo`

检查：

- `memory-leak-demo` 是否真的启动了
- 当前用户是否能看到目标 JVM
- `jps`、`jcmd`、`jstat` 是否可用

### 没有明显的内存问题信号

重新打一轮流量：

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/allocate -H "Content-Type: application/json" -d "{\"entries\":120,\"payloadKb\":512,\"tag\":\"round-2\"}"
```

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/raw/allocate -H "Content-Type: application/json" -d "{\"entries\":200,\"payloadKb\":256,\"tag\":\"raw-b-2\"}"
```

### 看不到死锁

重新触发一次：

```powershell
curl.exe -X POST http://localhost:8091/api/leak/deadlock/trigger
```

如果它提示已经触发过，就重启 `memory-leak-demo` 再来一次。

## Cleanup

演示结束后，如果你想把 demo 进程清掉，可以停止运行 `memory-leak-demo` 的终端。  
如果生成了 heap dump，记得清理：

```powershell
Remove-Item -Force -LiteralPath "C:\Users\panpa\AppData\Local\Temp\java-tuning-agent-heap-20380.hprof"
```

分享前最好把这里的 PID 替换成现场实际值，或者直接把文件名改成你演示时真实生成的那个。