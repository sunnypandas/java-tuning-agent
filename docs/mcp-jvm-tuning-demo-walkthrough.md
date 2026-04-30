# MCP JVM Tuning Demo Walkthrough

这份文档是一份可直接照着操作的分享脚本，目标是完成三段演示：

1. 先展示当前注册的 MCP，并重点介绍 `java-tuning-agent` 以及它暴露的 tool（**共 13 个**：7 个在线 JVM 链路 + 6 个离线导入；本脚本主线演示在线 evidence 复用链路，并补充 repeated sampling / JFR / native memory 场景）
2. 先手动完成一次在线 JVM tuning 流程
3. 补充一段离线模式导入材料测试，再用 `.cursor` 里的 skill 演示一次全流程

建议把整场分享控制在 10 到 15 分钟，保持“先认知，再操作，再自动化”的节奏。

## Demo Storyline

建议按下面 4 段来讲：

1. `MCP 是什么`
  先展示当前客户端里注册了哪些 MCP server，说明 `java-tuning-agent` 只是其中之一
2. `java-tuning-agent 能做什么`
  简单介绍在线排查链路的 7 个核心 tool（完整能力含离线共 **13** 个，见仓库 [README](../README.md)）
3. `在线流程怎么落地`
  先手动完成一次在线排查流程
4. `离线流程怎么落地`
  演示离线草稿校验、可选 heap dump 分块上传、离线结论生成，再让 Agent 通过 skill 自动跑在线全流程

## Demo Preparation

### 1. 确认 `java-tuning-agent` MCP 已注册

本仓库当前的 Inspector 配置在 [inspector-mcp-main.json](../inspector-mcp-main.json)。

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
        "-Dspring.main.keep-alive=false",
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

这个 demo 默认监听 `8091`，并且故意提供几类适合诊断的场景：保留 `byte[]`、高堆占用、direct buffer/native memory、classloader/metaspace、JFR allocation/contention、可选死锁。

如果你要演示 direct/native memory 或 NMT 相关规则，推荐加上 `-XX:NativeMemoryTracking=summary`：

```powershell
mvn -f compat/memory-leak-demo/pom.xml spring-boot:run "-Dspring-boot.run.jvmArguments=-Xms128m -Xmx256m -XX:+UseG1GC -XX:NativeMemoryTracking=summary"
```

### 3. 预先制造一轮可观测现象

为了让后面的 tuning 演示更稳定，建议先打几次流量：

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/allocate -H "Content-Type: application/json" -d "{\"entries\":120,\"payloadKb\":512,\"tag\":\"round-1\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/allocate -H 'Content-Type: application/json' -d '{"entries":120,"payloadKb":512,"tag":"round-1"}'
```

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/raw/allocate -H "Content-Type: application/json" -d "{\"entries\":200,\"payloadKb\":256,\"tag\":\"raw-b\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/raw/allocate -H 'Content-Type: application/json' -d '{"entries":200,"payloadKb":256,"tag":"raw-b"}'
```

如果你准备展示 native memory / direct buffer：

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/direct/allocate -H "Content-Type: application/json" -d "{\"entries\":128,\"payloadKb\":1024,\"tag\":\"direct-128m\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/direct/allocate -H 'Content-Type: application/json' -d '{"entries":128,"payloadKb":1024,"tag":"direct-128m"}'
```

如果你准备展示 class-count 趋势或 metaspace：

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/classloader/allocate -H "Content-Type: application/json" -d "{\"loaders\":1000,\"tag\":\"proxy-loaders\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/classloader/allocate -H 'Content-Type: application/json' -d '{"loaders":1000,"tag":"proxy-loaders"}'
```

如果你准备在分享里展示 `thread dump` 和死锁识别，再执行一次：

PowerShell:

```powershell
curl.exe -X POST http://localhost:8091/api/leak/deadlock/trigger
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/deadlock/trigger
```

### 4. 确认 `.cursor` 里的 skill 和 rule

本次全流程演示会用到：

- [java-tuning-agent-workflow skill](../.cursor/skills/java-tuning-agent-workflow/SKILL.md)
- [tool 参数参考](../.cursor/skills/java-tuning-agent-workflow/reference.md)
- [项目规则](../.cursor/rules/java-tuning-agent-mcp.mdc)

你可以顺手提一句：这个 skill 约束了 Agent 必须按固定流程调用在线工具，并在采集证据后复用同一份 evidence 生成建议（离线另有六条工具链），而不是随便跳步骤。

## Part 1: 展示当前注册的 MCP

### 操作

在你使用的 MCP 客户端里打开 server 列表。

如果你用的是 MCP Inspector，就加载 [inspector-mcp-main.json](../inspector-mcp-main.json)，然后展示当前 server。

### 讲解词

可以直接这样说：

> 这里先不急着看 JVM tuning，本质上我们先看 MCP 生态。  
> 当前客户端里可以注册多个 MCP server，`java-tuning-agent` 只是其中一个。  
> 它的定位不是改代码，而是把本机 JVM 的诊断能力包装成标准 MCP tool，让 Agent 可以按流程调用。

## Part 2: 介绍 `java-tuning-agent`（在线 7 个 tool）

完整注册表里还有 **6 个离线 tool**（草稿校验、heap 分块上传与 finalize、离线 `generateOfflineTuningAdvice`、可选 `summarizeOfflineHeapDumpFile` / `analyzeOfflineHeapRetention`），本段不展开，详见 [README](../README.md) 与 [offline-mode-spec.md](../offline-mode-spec.md)。

### 讲解重点

在线排查建议按“先轻、后重、再解释”的顺序讲：


| Tool                               | 作用 / Role                                     | 现场怎么讲                                                 |
| ---------------------------------- | --------------------------------------------- | ----------------------------------------------------- |
| `listJavaApps`                     | 列出当前用户可见的 JVM 进程 / discover local JVMs        | 先找到“要分析谁”                                             |
| `inspectJvmRuntime`                | 做一次轻量只读快照 / safe readonly snapshot            | 先看基础运行状态                                              |
| `inspectJvmRuntimeRepeated`        | 做短窗口多次轻量采样 / repeated readonly samples        | 看趋势，而不是只看单点                                           |
| `collectMemoryGcEvidence`          | 采集中等成本证据 / collect memory-GC evidence         | 需要时再拿 histogram、thread dump、heap dump                 |
| `recordJvmFlightRecording`         | 录制短 JFR 并解析摘要 / short JFR recording           | 需要 allocation、contention、CPU 样本时再开 JFR                |
| `generateTuningAdvice`             | 一步式采集并生成结论 / one-shot advice                  | 适合快速试用，不适合已经采过 evidence 的场景                           |
| `generateTuningAdviceFromEvidence` | 从已采集证据产出结构化结论 / advise from existing evidence | 最后把同一份 evidence 归纳成 findings、recommendations、hotspots |


若现场有人问 **heap dump**：补充说明采集到 `.hprof` 后，服务端在默认配置下会用 **Shark** 做**浅层**按类摘要并写进报告（非 MAT dominator）；详见 README。

### 讲解词

> 这条主线对应的是一个很自然的排查链路：
> 先找进程，再看快照和趋势，再按需补 histogram、thread dump、heap dump 或 JFR，最后生成分析结论。  
> 所以它不是一个“单点工具”，而是一条可组合、可编排的 tuning workflow。

### demo 场景和 tool 对照


| demo 场景                   | 触发接口                                  | 最适合展示的 MCP 能力                                                                    |
| ------------------------- | ------------------------------------- | -------------------------------------------------------------------------------- |
| retained records          | `POST /api/leak/allocate`             | class histogram、source hotspot、heap pressure                                     |
| raw bytes                 | `POST /api/leak/raw/allocate`         | `[B` 直方图、heap dump shallow summary                                               |
| direct buffer             | `POST /api/leak/direct/allocate`      | native memory / direct buffer evidence，建议启用 NMT                                  |
| classloader growth        | `POST /api/leak/classloader/allocate` | `inspectJvmRuntimeRepeated` 的 class-count 趋势、metaspace/NMT                       |
| young GC churn            | `POST /api/leak/churn`                | repeated sampling 的 YGC 趋势                                                       |
| JFR allocation/contention | `POST /api/leak/jfr-workload`         | `recordJvmFlightRecording` 的 allocation / monitor contention / execution samples |
| deadlock                  | `POST /api/leak/deadlock/trigger`     | thread dump deadlock hints                                                       |


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
- `nativeMemorySummary`（启用 NMT 时更有价值）
- `resourceBudgetEvidence`

如果你前面已经触发了 `deadlock/trigger`，这里通常能看到死锁提示。  
如果你前面已经打过 `allocate` 和 `raw/allocate`，这里通常能看到 `byte[]` 或相关对象占用较高。  
如果你前面已经打过 `direct/allocate` 并且 JVM 启用了 `-XX:NativeMemoryTracking=summary`，这里可以重点看 direct/native memory 相关 evidence。

#### 讲解词

> 这一步的用户意图不是“我要调一个证据采集 tool”，而是“当前快照还不够，我需要更强的证据来判断问题在哪里”。  
> 这也正好体现了这个 agent 的边界感：先轻量观察，再按需升级证据。

### Step 4. 可选：展示趋势采样和 JFR

如果你想展示第一阶段之后新增的在线能力，可以在生成最终结论前补这两段。

#### repeated sampling：看短窗口趋势

适合配合 `/api/leak/churn` 或 `/api/leak/classloader/allocate`。

触发 classloader 场景：

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/classloader/allocate -H "Content-Type: application/json" -d "{\"loaders\":1000,\"tag\":\"proxy-loaders\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/classloader/allocate -H 'Content-Type: application/json' -d '{"loaders":1000,"tag":"proxy-loaders"}'
```

然后调用 `inspectJvmRuntimeRepeated`，示例参数：

```json
{
  "request": {
    "pid": 24980,
    "sampleCount": 3,
    "intervalMillis": 5000,
    "includeThreadCount": true,
    "includeClassCount": true,
    "confirmationToken": ""
  }
}
```

重点展示：

- `samples`
- `loadedClassCount`
- `youngGcCount`
- `oldUsagePercent`
- `warnings` / `missingData`

#### JFR：看 allocation、contention、CPU sample

先在 MCP 客户端里调用 `recordJvmFlightRecording`。示例参数：

```json
{
  "request": {
    "pid": 24980,
    "durationSeconds": 30,
    "settings": "profile",
    "jfrOutputPath": "/tmp/memory-leak-demo-24980.jfr",
    "maxSummaryEvents": 200000,
    "confirmationToken": "user-approved"
  }
}
```

JFR 录制窗口打开后，在另一个终端立刻打 workload，让它落在录制时间内：

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/jfr-workload -H "Content-Type: application/json" -d "{\"durationSeconds\":20,\"workerThreads\":4,\"payloadBytes\":4096}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/jfr-workload -H 'Content-Type: application/json' -d '{"durationSeconds":20,"workerThreads":4,"payloadBytes":4096}'
```

重点展示：

- `jfrPath`
- `summary.allocationSummary`
- `summary.threadSummary`
- `summary.executionSampleSummary`
- 后续 report 中的 `JFR shows allocation hotspots` / `JFR shows thread contention pressure`

### Step 5. 最后让系统给出结构化分析结论

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
- 如果启用了 NMT 并触发 direct buffer，报告可以利用 native/direct buffer evidence
- 如果补了 repeated sampling，报告可以利用趋势 evidence
- 如果补了 JFR summary，报告会出现 JFR allocation/contention/execution sample 相关 findings
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

建议按下面顺序演示（对应 6 个离线 tool）：

1. `validateOfflineAnalysisDraft`
2. `submitOfflineHeapDumpChunk`（可选，heap dump 很大时）
3. `finalizeOfflineHeapDump`（仅分块上传时）
4. `summarizeOfflineHeapDumpFile`（可选，仅看浅层摘要）
5. `analyzeOfflineHeapRetention`（可选，做 holder/retention 方向证据）
6. `generateOfflineTuningAdvice`

### Step 0. 推荐先用脚本导出离线 bundle

如果目标 JVM 当前能被本机 `jcmd` 访问，优先用仓库脚本导出材料。现在脚本会生成：

- B1-B6 必选材料：`b1-jvm-identity.txt`、`b2-jdk-vm-version.txt`、`b3-runtime-snapshot.txt`、`b4-class-histogram.txt`、`b5-thread-dump.txt`、`b6-heap-dump.hprof`
- R1-R3 推荐项：`r1-gc-log.txt` / `r1-gc-log-NOT_COLLECTED.txt`、`r2-app-log.txt` / `r2-app-log-NOT_COLLECTED.txt`、`r3-repeated-samples.json`
- 增强证据：`optional-native-memory-summary.txt` 或 `optional-native-memory-summary-SKIPPED.txt`、`optional-resource-budget.txt`
- 可直接作为草稿起点的 `offline-draft-template.json`

macOS/Linux shell:

```bash
scripts/export-jvm-diagnostics.sh --export-dir /tmp/memory-leak-demo-offline --process-id <pid> --gc-log-path <optional-gc-log> --app-log-path <optional-app-log>
```

PowerShell:

```powershell
.\scripts\export-jvm-diagnostics.ps1 -ExportDir 'C:\tmp\memory-leak-demo-offline' -ProcessId <pid> -GcLogPath '<optional-gc-log>' -AppLogPath '<optional-app-log>'
```

如果只是快速验证离线草稿校验，可以先跳过 heap dump：

```bash
scripts/export-jvm-diagnostics.sh --export-dir /tmp/memory-leak-demo-offline --process-id <pid> --skip-heap-dump --sample-count 1 --sample-interval-seconds 0
```

```powershell
.\scripts\export-jvm-diagnostics.ps1 -ExportDir 'C:\tmp\memory-leak-demo-offline' -ProcessId <pid> -SkipHeapDump -SampleCount 1 -SampleIntervalSeconds 0
```

跳过 heap dump 时，`offline-draft-template.json` 里的 `heapDumpAbsolutePath` 会为空；后续要么补一个 `.hprof` 路径，要么在离线 advice 阶段设置 `proceedWithMissingRequired=true` 降级继续。

### Step 1. 构建离线草稿并做一次校验

如果已经使用 export 脚本，优先打开 `offline-draft-template.json`，取其中的 `draft` 传给 `validateOfflineAnalysisDraft`。如果没有脚本导出，也可以手工准备一个 `draft`（最小建议先填 B1-B6）：

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

可选增强证据按需补充：

- `nativeMemorySummary`：`VM.native_memory summary` / `summary.diff` 的 `filePath` 或 `inlineText`，用于 native / direct buffer / metaspace 规则
- `backgroundNotes.resourceBudget`：容器内存、RSS、CPU quota 等 key=value 预算信息；格式错误会降级，不阻断离线分析

首次校验建议 `proceedWithMissingRequired=false`，先看缺失项。手工草稿示例：

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
    "nativeMemorySummary": { "filePath": "C:/demo/offline/nmt-summary.txt" },
    "explicitlyNoGcLog": true,
    "explicitlyNoAppLog": true,
    "explicitlyNoRepeatedSamples": true,
    "backgroundNotes": {
      "resourceBudget": "containerMemoryLimitBytes=1073741824\nprocessRssBytes=805306368\ncpuQuotaCores=2.0"
    }
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
- 默认不需要传 `analysisDepth`；如果要把 holder-oriented retention 证据接入本次离线 advice，传 `analysisDepth="deep"`，失败时会在 warnings / missingData / Markdown 中降级说明

示例（含源码上下文）：

```json
{
  "codeContextSummary": {
    "dependencies": [],
    "configuration": {},
    "applicationNames": ["MemoryLeakDemoApplication"],
    "sourceRoots": ["compat/memory-leak-demo"],
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
5. 复用 evidence 生成结构化分析结论

如果你要展示 repeated sampling、JFR 或 direct/native memory，可以在手动流程里演示；当前 skill 主线仍建议保持“识别进程、轻量快照、证据采集、结论生成”的稳定路径，便于现场复现。

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
2. “在线排查链路负责找 JVM、看快照、看趋势、补证据、录 JFR、复用同一份 evidence 出结论（整站共 13 个 tool）。”
3. “我先手动演示一遍主线，再补 direct buffer、classloader 和 JFR 这些扩展场景。”
4. “接着演示离线模式：校验草稿、可选上传 heap dump、可选 deep retention，最后生成离线结论。”
5. “现在再用 skill 跑一次在线主线，你会发现 Agent 已经会自己按这条流程去编排。”
6. “这就是把 JVM tuning 经验沉淀成 MCP tool、demo 场景和 skill 之后的价值。”

## Troubleshooting During Demo

### 没看到 `memory-leak-demo`

检查：

- `memory-leak-demo` 是否真的启动了
- 当前用户是否能看到目标 JVM
- `jps`、`jcmd`、`jstat` 是否可用

### 没有明显的内存问题信号

重新打一轮流量：

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/allocate -H "Content-Type: application/json" -d "{\"entries\":120,\"payloadKb\":512,\"tag\":\"round-2\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/allocate -H 'Content-Type: application/json' -d '{"entries":120,"payloadKb":512,"tag":"round-2"}'
```

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/raw/allocate -H "Content-Type: application/json" -d "{\"entries\":200,\"payloadKb\":256,\"tag\":\"raw-b-2\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/raw/allocate -H 'Content-Type: application/json' -d '{"entries":200,"payloadKb":256,"tag":"raw-b-2"}'
```

如果你要看 native/direct buffer：

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/direct/allocate -H "Content-Type: application/json" -d "{\"entries\":128,\"payloadKb\":1024,\"tag\":\"direct-128m-2\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/direct/allocate -H 'Content-Type: application/json' -d '{"entries":128,"payloadKb":1024,"tag":"direct-128m-2"}'
```

如果你要看 class-count/metaspace 趋势：

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/classloader/allocate -H "Content-Type: application/json" -d "{\"loaders\":1000,\"tag\":\"proxy-loaders-2\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/classloader/allocate -H 'Content-Type: application/json' -d '{"loaders":1000,"tag":"proxy-loaders-2"}'
```

### 没有 native memory / direct buffer 证据

确认 demo JVM 启动参数里包含：

```text
-XX:NativeMemoryTracking=summary
```

NMT 必须在目标 JVM 启动时打开；已经运行中的进程不能靠 MCP 后补这个开关。

### 看不到死锁

重新触发一次：

PowerShell:

```powershell
curl.exe -X POST http://localhost:8091/api/leak/deadlock/trigger
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/deadlock/trigger
```

如果它提示已经触发过，就重启 `memory-leak-demo` 再来一次。

## Cleanup

演示结束后，如果你想把 demo 进程清掉，可以停止运行 `memory-leak-demo` 的终端。  
如果生成了 heap dump，记得清理：

```powershell
Remove-Item -Force -LiteralPath "C:\Users\panpa\AppData\Local\Temp\java-tuning-agent-heap-20380.hprof"
```

分享前最好把这里的 PID 替换成现场实际值，或者直接把文件名改成你演示时真实生成的那个。

如果同一个 JVM 里跑过多个场景，也可以先清掉 retained stores 再继续：

PowerShell:

```powershell
curl.exe -X POST http://localhost:8091/api/leak/clear
curl.exe -X POST http://localhost:8091/api/leak/raw/clear
curl.exe -X POST http://localhost:8091/api/leak/direct/clear
curl.exe -X POST http://localhost:8091/api/leak/classloader/clear
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/clear
curl -X POST http://localhost:8091/api/leak/raw/clear
curl -X POST http://localhost:8091/api/leak/direct/clear
curl -X POST http://localhost:8091/api/leak/classloader/clear
```

