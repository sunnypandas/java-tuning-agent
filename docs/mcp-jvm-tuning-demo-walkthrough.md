# MCP JVM Tuning Demo Walkthrough

这份文档是一份可直接照着操作的分享脚本，目标是完成两段演示：

1. 先展示当前注册的 MCP，并重点介绍 `java-tuning-agent` 以及它暴露的 4 个 tool
2. 先手动完成一次 JVM tuning 流程，再用 `.cursor` 里的 skill 演示一次全流程

建议把整场分享控制在 10 到 15 分钟，保持“先认知，再操作，再自动化”的节奏。

## Demo Storyline

建议按下面 3 段来讲：

1. `MCP 是什么`
  先展示当前客户端里注册了哪些 MCP server，说明 `java-tuning-agent` 只是其中之一
2. `java-tuning-agent 能做什么`
  简单介绍它的 4 个 tool，解释这 4 类能力分别负责什么
3. `怎么把它真正用起来`
  先手动完成一次排查流程，再让 Agent 通过 skill 自动跑完整流程

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

你可以顺手提一句：这个 skill 约束了 Agent 必须按固定流程调用这 4 个 tool，而不是随便跳步骤。

## Part 1: 展示当前注册的 MCP

### 操作

在你使用的 MCP 客户端里打开 server 列表。

如果你用的是 MCP Inspector，就加载 [inspector-mcp-main.json](/c:/Users/panpa/Workspace/java-tuning-agent/inspector-mcp-main.json:1)，然后展示当前 server。

### 讲解词

可以直接这样说：

> 这里先不急着看 JVM tuning，本质上我们先看 MCP 生态。  
> 当前客户端里可以注册多个 MCP server，`java-tuning-agent` 只是其中一个。  
> 它的定位不是改代码，而是把本机 JVM 的诊断能力包装成标准 MCP tool，让 Agent 可以按流程调用。

## Part 2: 介绍 `java-tuning-agent` 和它的 4 个 tool

### 讲解重点

这 4 个 tool 建议按这个顺序讲：


| Tool                      | 作用               | 现场怎么讲                                      |
| ------------------------- | ---------------- | ------------------------------------------ |
| `listJavaApps`            | 列出当前用户可见的 JVM 进程 | 先找到“要分析谁”                                  |
| `inspectJvmRuntime`       | 做一次轻量只读快照        | 先看基础运行状态                                   |
| `collectMemoryGcEvidence` | 采集中等成本证据         | 需要时再拿 histogram、thread dump、heap dump      |
| `generateTuningAdvice`    | 产出结构化分析结论        | 最后把现象归纳成 findings、recommendations、hotspots |


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

## Part 4: 用 skill 演示一次全流程

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

- 不是把 4 个 tool 藏起来，而是把调用顺序产品化
- 不是让 Agent 自由发挥，而是让它遵守 skill 和 rule
- 手动流程适合教学和调试
- skill 流程适合真实使用和复用

## Recommended Live Script

如果你想把全程压缩成一段比较自然的分享，可以照下面的节奏：

1. “先看 MCP 列表，我们的 `java-tuning-agent` 是其中一个 server。”
2. “它暴露了 4 个 tool，分别负责找 JVM、看快照、补证据、出结论。”
3. “我先手动演示一遍，但会用用户语言来描述诉求，而不是直接念 tool 名字。”
4. “现在再用 skill 跑一次，你会发现 Agent 已经会自己按这条流程去编排。”
5. “这就是把 JVM tuning 经验沉淀成 MCP tool 和 skill 之后的价值。”

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