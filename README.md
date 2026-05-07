# Java Tuning Agent Example

This example exposes MCP tools for local Java discovery, safe JVM inspection, **memory/GC-oriented diagnosis**, and structured tuning advice. It is configured to run as a local stdio MCP server, not as an HTTP service.

## What MCP clients should discover

After the server is started over stdio, MCP clients should see **13** tools: seven for **live JVM** workflows and six for **offline / imported** bundles. Tool descriptions are bilingual at the MCP surface: English for broad MCP clients, 中文用于本地排障向导与 Agent 编排。

### Live JVM tools

| Tool | Role / 说明 |
|------|-------------|
| `listJavaApps` | Discover JVM processes visible to the current user；列出当前用户可见的本机 Java/JVM 进程。 |
| `inspectJvmRuntime` | Collect a **lightweight** readonly snapshot (`jcmd` + `jstat`) structured for diagnosis；采集轻量只读 JVM 快照。 |
| `inspectJvmRuntimeRepeated` | Collect repeated safe read-only snapshots for short trend analysis (`sampleCount`, `intervalMillis`, optional `includeThreadCount` / `includeClassCount`)；短窗口重复采样用于趋势判断。 |
| `recordJvmFlightRecording` | Record one short Java Flight Recorder session for a PID and return the `.jfr` path plus a bounded summary of GC, allocation, thread/lock, and execution sample events；短时 JFR 录制，需 `confirmationToken` 与绝对 `jfrOutputPath`。 |
| `collectMemoryGcEvidence` | Collect **medium-cost** evidence (class histogram; optional thread dump; optional **`GC.heap_dump`** to an absolute `.hprof` path) when `confirmationToken` is supplied for privileged options；按用户授权采集中等成本内存/GC 证据。 Returned pack includes **`heapDumpPath`** when the dump file exists. When **`java-tuning-agent.heap-summary.auto-enabled`** is `true` (default) and the `.hprof` file is present, the pack also includes a **Shark-based shallow heap summary** (`heapShallowSummary`) used by the diagnosis engine and appended to `formattedSummary` (see below). |
| `generateTuningAdvice` | Run the **rule-based memory/GC diagnosis engine** for the PID and `CodeContextSummary`；从 PID 与源码上下文生成结构化调优建议。 Default: lightweight snapshot only; optional **`collectClassHistogram`** + **`confirmationToken`** runs histogram first (same policy as `collectMemoryGcEvidence`). Callers may also pass optional `baselineEvidence`, `jfrSummary`, `repeatedSamplingResult`, and `resourceBudgetEvidence` collected in the same diagnosis window. Response includes **`formattedSummary`**: stable Markdown of the full report (`##` / `###`, lists, fenced `text` blocks for evidence), **plus** optional diagnosis context, key deltas, and heap dump shallow summary sections when the corresponding evidence is present. Hosts should **paste it into the message as renderable Markdown**—**not** wrapped in an outer code fence—so structure shows; avoid paraphrasing away **`suspectedCodeHotspots`**. |
| `generateTuningAdviceFromEvidence` | Run the same diagnosis/report path from an existing **`MemoryGcEvidencePack`**；复用已有证据包生成建议。 Use this immediately after `collectMemoryGcEvidence` so histogram/thread/heap evidence is **not collected again** and the report matches the evidence the user already saw. Optional `baselineEvidence`, `jfrSummary`, `repeatedSamplingResult`, and `resourceBudgetEvidence` parameters are merged only when matching fields are absent from the supplied pack. |

Native evidence (P1): `collectMemoryGcEvidence` now also attempts read-only `jcmd VM.native_memory` help/summary probes and surfaces a structured `nativeMemorySummary` (total reserved/committed, `NIO` direct category, `Class` category). If NMT is disabled, unavailable, or blocked by attach permissions, the pack degrades with explicit `warnings` and `missingData` instead of failing the whole request.
Native collection is capability-aware but not collector-gated: collector/JDK signals are warnings only, while actual NMT availability is determined by command support, permissions, and target JVM NMT state.

When `nativeMemorySummary` is missing, reports now include command-level next steps: restart the target JVM with `-XX:NativeMemoryTracking=summary`, collect `jcmd <pid> VM.native_memory summary`, then rerun `collectMemoryGcEvidence` or provide offline `nativeMemorySummary`. Direct-buffer hints specifically ask for the NMT `NIO` category; metaspace/classloader hints ask for the NMT `Class` category so class metadata pressure is not confused with heap retention.

### Offline / imported bundle tools

| Tool | Role / 说明 |
|------|-------------|
| `validateOfflineAnalysisDraft` | Validate an `OfflineBundleDraft` (B1–B6, recommended “absent” flags, degradation)；校验离线诊断草稿并返回缺失项/降级提示。 |
| `submitOfflineHeapDumpChunk` | Upload one Base64 chunk of a large `.hprof` (with `uploadId` / `chunkIndex` / `chunkTotal`)；提交大型 heap dump 的 Base64 分块。 Starting a new upload opportunistically cleans expired chunk sessions using the configured TTL. |
| `finalizeOfflineHeapDump` | Merge chunks, verify SHA-256 and size, return absolute path for `heapDumpAbsolutePath`；合并并校验分块，返回可写入草稿的绝对路径。 |
| `generateOfflineTuningAdvice` | Assemble `MemoryGcEvidencePack` from the draft and run the same `generateAdviceFromEvidence` path as online；从导入材料生成与在线一致的结构化报告。 If `gcLogPathOrText` is present, JDK unified GC pause lines are parsed into `gcLogSummary` and used by pause-history rules; `repeatedSamplesPathOrText` accepts `inspectJvmRuntimeRepeated` output and feeds trend rules; `backgroundNotes.resourceBudget` accepts key=value container/RSS/CPU budget evidence and degrades on malformed values. If `heapDumpAbsolutePath` points to an existing file and heap summary auto-mode is on, the server **indexes the dump with Shark** and feeds **`heapShallowSummary`** into rules and `formattedSummary`; with `analysisDepth=deep`, it also attempts holder-oriented retention evidence and degrades honestly on fallback/failure. |
| `summarizeOfflineHeapDumpFile` | **Optional** ad-hoc call: return Markdown + structured top shallow-by-class rows for a local `.hprof` without running the full advice pipeline；独立预览本地 heap dump 的浅层按类摘要。 |
| `analyzeOfflineHeapRetention` | Analyze an existing `.hprof` for holder-oriented retention evidence；面向 holder/引用链/GC root 的 retention 证据分析，`analysisDepth=deep` attempts retained-style analysis and falls back honestly. |

Offline validation and advice now include target-consistency checks. The validator warns when B1/B3/B4/B5 PID markers disagree, and offline advice adds an `offlineTargetConsistency` gap plus warning when the imported `java_command` does not match the supplied `CodeContextSummary.applicationNames` or `candidatePackages`. This is intended to catch complete-but-wrong bundles before a report is attributed to the wrong source tree.

**Diagnosis engine note:** Findings and recommendations are produced by **deterministic Java rules** (`MemoryGcDiagnosisEngine`), including optional **heap shallow dominance** when `heapShallowSummary` is present—not by the LLM. The model’s role is tool orchestration and explanation. Shallow totals are **not** MAT retained-size / dominator analysis.

**Configuration (heap shallow summary):**

| Property | Default | Meaning |
|----------|---------|--------|
| `java-tuning-agent.heap-summary.auto-enabled` | `true` | When `true`, automatically run Shark indexing when a `.hprof` path is valid (online after `GC.heap_dump`, or offline draft path). Set `false` to skip indexing (large dumps / CI). |
| `java-tuning-agent.offline.heap-summary.default-top-classes` | `40` | Max types in the shallow leader table (Markdown + structured entries). |
| `java-tuning-agent.offline.heap-summary.default-max-output-chars` | `32000` | Max Markdown characters for the summary block. |

**Configuration (offline heap upload cleanup):**

| Property | Default | Meaning |
|----------|---------|--------|
| `java-tuning-agent.offline.heap-dump-upload.ttl-seconds` | `86400` | Age threshold for opportunistic cleanup before a new chunk upload starts. |
| `java-tuning-agent.offline.heap-dump-upload.cleanup-finalized` | `false` | When `false`, cleanup removes only incomplete upload chunk directories; set `true` to also remove old finalized `.hprof` files under the repository-managed final directory. |

**Configuration (command + sampling):**

| Property | Default | Meaning |
|----------|---------|--------|
| `java-tuning-agent.command.default-timeout-ms` | `15000` | Default timeout for bounded `jcmd` / `jstat` execution. |
| `java-tuning-agent.command.default-max-output-bytes` | `8388608` | Default output cap for safe command execution. |
| `java-tuning-agent.sampling.default-sample-count` | `3` | Default `sampleCount` for `inspectJvmRuntimeRepeated`. |
| `java-tuning-agent.sampling.default-interval-ms` | `10000` | Default `intervalMillis` between repeated safe-readonly samples. |
| `java-tuning-agent.sampling.max-sample-count` | `20` | Upper bound for repeated-sampling request size. |
| `java-tuning-agent.sampling.max-total-duration-ms` | `300000` | Upper bound for the total repeated-sampling window. |

**Configuration (JFR short recording):**

| Property | Default | Meaning |
|----------|---------|--------|
| `java-tuning-agent.jfr.default-duration-seconds` | `30` | Default `durationSeconds` for `recordJvmFlightRecording`. |
| `java-tuning-agent.jfr.min-duration-seconds` | `5` | Minimum recording duration. |
| `java-tuning-agent.jfr.max-duration-seconds` | `300` | Maximum recording duration. |
| `java-tuning-agent.jfr.completion-grace-ms` | `10000` | Grace period for the `.jfr` file to appear and stabilize. |
| `java-tuning-agent.jfr.default-max-summary-events` | `200000` | Default `maxSummaryEvents` parser cap. |
| `java-tuning-agent.jfr.top-limit` | `10` | Top-list size for parsed JFR summaries. |

You can set these in `application.yml`, environment variables, or JVM system properties using Spring Boot’s relaxed binding (`JAVA_TUNING_AGENT_HEAP_SUMMARY_AUTO_ENABLED=false`, etc.).

## Memory/GC diagnosis flow

1. **Default (lightweight):** `generateTuningAdvice` with **`collectClassHistogram: false`**, **`collectThreadDump: false`**, and **`confirmationToken: ""`** (or any value when both flags are false).  
   - Collects the same readonly snapshot as `inspectJvmRuntime(pid)` and runs diagnosis.  
   - The report includes **`confidence`** and **`confidenceReasons`**. When **`heapUsedBytes`** from `GC.heap_info` is missing or zero, rules can still use **`jstat` old-generation usage** for pressure and evidence-gap hints.

2. **Histogram-inclusive (one call):** `generateTuningAdvice` with **`collectClassHistogram: true`** and a non-blank **`confirmationToken`**.  
   - Runs **`collectMemoryGcEvidence`** internally, then diagnosis on the resulting **`MemoryGcEvidencePack`** (snapshot + histogram + collection warnings/missing fragments).  
   - Enables **`SuspectedLeakRule`** (including dominant **`byte[]` / `[B`** retention) and **`LocalSourceHotspotFinder`** when **`sourceRoots`** are set.

3. **Evidence-backed advice (two calls, no double collection):** call `collectMemoryGcEvidence(MemoryGcEvidenceRequest)` once, then pass that exact **`MemoryGcEvidencePack`** as `evidence` to **`generateTuningAdviceFromEvidence`** with `CodeContextSummary`, `environment`, and `optimizationGoal`.

4. **Evidence-only:** `collectMemoryGcEvidence(MemoryGcEvidenceRequest)` still returns a raw **`MemoryGcEvidencePack`** for clients that want the pack without advice.

5. **Java API:** **`TuningAdviceRequest`** with optional **`classHistogramHint`** still works via **`JavaTuningWorkflowService.generateAdvice`** (no extra `jcmd` when you already have a histogram). `JavaTuningWorkflowService.generateAdviceFromEvidence` is the direct Java equivalent of `generateTuningAdviceFromEvidence`. See [docs/superpowers/specs/2026-04-11-memory-gc-diagnosis-agent-design.md](docs/superpowers/specs/2026-04-11-memory-gc-diagnosis-agent-design.md) §16.

6. **Source-aware hotspots:** Populate **`CodeContextSummary.sourceRoots`** (e.g. `compat/memory-leak-demo` or `.../src/main/java`). The workflow now correlates source hints from class histograms, thread stacks, JFR summaries, and heap-retention holder/chain evidence, so **`suspectedCodeHotspot`** entries may include **`fileHint`** paths and holder-field clues such as `AllocationRecord.payload` when the evidence supports them.

## JFR short profiling flow

Use `recordJvmFlightRecording` when a lightweight snapshot or repeated `jstat` samples show that you need profiling evidence rather than another heap artifact.

Required fields:

- `pid`: target JVM from `listJavaApps`
- `durationSeconds`: bounded recording window, typically 30
- `settings`: `profile` for more profiling signal or `default` for lower overhead
- `jfrOutputPath`: absolute path ending in `.jfr`; the file must not already exist
- `maxSummaryEvents`: parser event cap, typically `200000`
- `confirmationToken`: non-blank approval token

The tool runs one bounded `jcmd JFR.start ... duration=<Ns> filename=<path>` recording. On some JVMs this command returns promptly while the JVM keeps recording until it writes the `.jfr` output; the collector waits for that file's size to stabilize until the remaining requested `durationSeconds` window expires (estimated from elapsed `JFR.start` wall time when it returns early) plus `java-tuning-agent.jfr.completion-grace-ms` before reporting `jfrFile` missing.

It does not expose public `JFR.stop` lifecycle management, does not overwrite existing recordings, and can feed parsed findings into advice via `jfrSummary` / `generateTuningAdviceFromEvidence`.

## Structured report (`TuningAdviceReport`)

- **`findings`**: rule-based or inferred memory/GC findings (`TuningFinding`: title, severity, evidence, reasoning type, impact).  
- **`recommendations`**: JVM/app actions (`TuningRecommendation`).  
- **`suspectedCodeHotspots`**: class-level hints with optional file paths (`SuspectedCodeHotspot`), correlated across histogram, thread dump, JFR, and retention evidence when available.  
- **`missingData`**: explicit gaps (e.g. histogram not collected).  
- **`nextSteps`**: suggested follow-up evidence or validation.  
- **`confidence`**: `high` \| `medium` \| `low`.  
- **`confidenceReasons`**: short explanations for the confidence level.  
- **`formattedSummary`**: Markdown string with **fixed section order** for the rule-based report (`Findings` → `Recommendations` → `Suspected code hotspots` → `Missing data` → `Next steps` → `Confidence`). When a heap dump was **indexed** (Shark), the same string may end with an **extra** `### Heap dump file summary (local, shallow by class)` block (or a short **failed** note if indexing errored). MCP/LLM clients should **surface this field verbatim** in the chat body as **renderable Markdown** (no outer fence around the whole string—that hides headings and lists). Use an outer code fence only if the user wants a raw copy-paste blob or the host cannot render Markdown. A short preamble (PID, `.hprof` path) above the summary is fine. The structured JSON fields remain for programmatic use.

Native/collector rules now include:
- `NativeMemoryPressureRule` (high total native committed ratio)
- `DirectBufferPressureRule` (NMT `NIO` direct pressure)
- `MetaspacePressureRule` (metaspace/class metadata pressure)

## `CodeContextSummary`

- **`dependencies`**, **`configuration`**, **`applicationNames`**: summary metadata for the workload.  
- **`sourceRoots`**: directories to search for `.java` files when correlating histogram types (optional).  
- **`candidatePackages`**: reserved for richer routing (optional).  
- Use **`CodeContextSummary.withoutSource(...)`** when you only have the first three fields.

## Safety model

- Default commands are limited to `jps`, `jcmd`, and `jstat` (see configuration below).
- The runtime collector uses readonly diagnostics such as `jcmd VM.flags`, `jcmd GC.heap_info`, and `jstat -gcutil`.
- **Privileged diagnostics** (class histogram, thread dump, heap dump via **`GC.heap_dump`**, JFR short recording) require a non-blank **`confirmationToken`**. Heap dump also requires a non-blank absolute **`heapDumpOutputPath`** ending in `.hprof`, with an existing parent directory and a target file that does not already exist; JFR requires a non-blank absolute **`jfrOutputPath`** ending in `.jfr`. Otherwise collection fails fast with `IllegalArgumentException`.
- The example is intended for local development or controlled readonly inspection of JVM processes visible to the current user.
- **Thread dump:** requesting `includeThreadDump` runs `jcmd <pid> Thread.print` and attaches the parsed summary to the evidence pack; collection warnings are surfaced when output is missing or cannot be parsed.

### GC/JDK capability matrix (P1 baseline)

For native-memory evidence (`VM.native_memory summary`) and collector-aware diagnostics, the current baseline recognizes:

- JDK: 11 / 17 / 21 / 25
- Collectors: G1, Parallel, CMS (legacy-only), ZGC, Serial

Compatibility behavior:
- Unknown collector or unknown/legacy JDK -> warn and keep probing NMT if `jcmd` exposes it
- CMS on newer JDKs -> warn about inconsistent collector/JDK signals; NMT itself is still probed independently
- NMT absence, disabled target NMT, or attach/permission failure -> degrade with warnings + `missingData` (`nativeMemorySummary`)
- Missing NMT in direct-buffer or classloader/metaspace scenarios -> next steps name the exact restart flag and `jcmd <pid> VM.native_memory summary` command to collect `NIO` / `Class` categories.

### Offline native inputs (P1)

`OfflineBundleDraft` now accepts optional native evidence sources using `OfflineArtifactSource` (`filePath` or `inlineText`):

- `nativeMemorySummary`: preferred VM.native_memory summary source
- `directBufferEvidence`: optional direct-buffer supporting evidence, currently retained for manual context/future expansion; primary direct-buffer rules consume `nativeMemorySummary` `NIO` data
- `metaspaceEvidence`: optional metaspace supporting evidence, currently retained for manual context/future expansion; primary metaspace rules consume runtime metaspace and `nativeMemorySummary` `Class` data

These fields are recommended (not required). If `nativeMemorySummary` is absent or unparsable, offline analysis degrades with explicit `warnings`/`missingData` while preserving backward compatibility for existing drafts.

## Configuration

The default command whitelist is in [src/main/resources/application.yml](src/main/resources/application.yml) under `java-tuning-agent.command-whitelist`.

The MCP server is configured as:

- `spring.main.web-application-type=none`
- `spring.main.keep-alive=false`
- `spring.ai.mcp.server.stdio=true`
- `spring.ai.mcp.server.name=java-tuning-agent`

For stdio MCP registrations, do not set `spring.main.keep-alive=true`: the server lifetime should be tied to the MCP client process/stdin. Forcing Spring Boot keep-alive can leave old MCP JVMs running after an LLM CLI session exits.

## Agent pack for Codex, Cursor, and GitHub Copilot

Release assets include an optional **agent pack** zip:

```text
java-tuning-agent-agent-pack-<version>.zip
```

This pack is modeled after Codex plugin/skill packages: it has a `.codex-plugin/plugin.json`, an installable Codex skill under `skills/`, and client adapters for Cursor and GitHub Copilot under `adapters/`.

Source layout:

```text
agent-pack/java-tuning-agent/
  .codex-plugin/plugin.json
  INSTALL.md
  skills/java-tuning-agent-workflow/
  adapters/cursor/
  adapters/copilot/
  mcp/release/
  mcp/dev/
  scripts/
```

Codex release install:

```bash
agent-pack/java-tuning-agent/scripts/install-codex.sh /absolute/path/to/java-tuning-agent-0.1.0.jar
```

Windows PowerShell:

```powershell
.\agent-pack\java-tuning-agent\scripts\install-codex.ps1 C:\path\to\java-tuning-agent-0.1.0.jar
```

Cursor release install:

```bash
agent-pack/java-tuning-agent/scripts/install-cursor.sh /path/to/project /absolute/path/to/java-tuning-agent-0.1.0.jar
```

Windows PowerShell:

```powershell
.\agent-pack\java-tuning-agent\scripts\install-cursor.ps1 C:\path\to\project C:\path\to\java-tuning-agent-0.1.0.jar
```

GitHub Copilot release install:

```bash
agent-pack/java-tuning-agent/scripts/install-copilot.sh /path/to/project /absolute/path/to/java-tuning-agent-0.1.0.jar
```

Windows PowerShell:

```powershell
.\agent-pack\java-tuning-agent\scripts\install-copilot.ps1 C:\path\to\project C:\path\to\java-tuning-agent-0.1.0.jar
```

For source-tree MCP development in Codex or Cursor, use the dev installers/templates under `agent-pack/java-tuning-agent/mcp/dev/`. They use Maven with `-q` so Maven logs do not pollute stdio MCP:

```bash
agent-pack/java-tuning-agent/scripts/install-codex-dev.sh /absolute/path/to/java-tuning-agent/pom.xml
agent-pack/java-tuning-agent/scripts/install-cursor-dev.sh /path/to/project /absolute/path/to/java-tuning-agent/pom.xml
```

```powershell
.\agent-pack\java-tuning-agent\scripts\install-codex-dev.ps1 C:\path\to\java-tuning-agent\pom.xml
.\agent-pack\java-tuning-agent\scripts\install-cursor-dev.ps1 C:\path\to\project C:\path\to\java-tuning-agent\pom.xml
```

## Utility scripts (PowerShell / Bash)

The `scripts/` directory provides both Windows PowerShell and Unix Bash variants for local helper tasks.

### Export JVM diagnostics bundle

- PowerShell: `.\scripts\export-jvm-diagnostics.ps1 -ExportDir .\out\diag -ProcessId 12345`
- Bash: `scripts/export-jvm-diagnostics.sh --export-dir ./out/diag --process-id 12345`
- Interactive target JVM selection (omit PID):
  - PowerShell: `.\scripts\export-jvm-diagnostics.ps1 -ExportDir .\out\diag`
  - Bash: `scripts/export-jvm-diagnostics.sh --export-dir ./out/diag`
- Skip heap dump:
  - PowerShell: `-SkipHeapDump`
  - Bash: `--skip-heap-dump`

### Stop stray `java-tuning-agent` processes

- PowerShell: `.\scripts\kill-java-tuning-agent.ps1`
- Bash: `scripts/kill-java-tuning-agent.sh`

## MCP client config

```json
{
  "mcpServers": {
    "java-tuning-agent": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/absolute/path/to/java-tuning-agent-0.1.0.jar"]
    }
  }
}
```

Client-specific examples live in `agent-pack/java-tuning-agent/mcp/release/`.

## Quick verification flow

1. Start or register the server using one of the methods above.
2. Call `listJavaApps`.
3. Pick a PID and call `inspectJvmRuntime`.
4. Optionally call `inspectJvmRuntimeRepeated` with `sampleCount` and `intervalMillis` when you need short trend evidence.
5. Optionally call `recordJvmFlightRecording` with `durationSeconds`, `settings`, an absolute `jfrOutputPath`, `maxSummaryEvents`, and `confirmationToken` when you need short profiling evidence.
6. Call `collectMemoryGcEvidence` for the selected scope, then pass that exact evidence pack to `generateTuningAdviceFromEvidence` with a `CodeContextSummary`.
7. Use `generateTuningAdvice` only as a one-shot shortcut when you did not already call `collectMemoryGcEvidence`; use `analyzeOfflineHeapRetention` later for a local `heapDumpAbsolutePath` with `analysisDepth`.

## Troubleshooting

- If the IDE cannot discover the server, ensure it launches the process in foreground stdio mode rather than as a detached background process.
- If the server starts but no tools appear, restart the MCP server from the IDE and refresh the tool cache.
- If process discovery returns nothing, confirm `jps`, `jcmd`, and `jstat` are on `PATH`.
- If the server starts as a web app and fails, check that [src/main/resources/application.yml](src/main/resources/application.yml) still sets `spring.main.web-application-type: none`.

### Windows: `mvn package` fails because the jar is in use

On Windows, **`java -jar target/java-tuning-agent-*.jar`** keeps that file **locked**, so Maven cannot overwrite it during **`package`**.

**Recommended local workflow**

1. Point MCP at **`mvn -q -f <pom> -Pstdio-mcp-dev spring-boot:run`** instead of **`java -jar`** so the process loads **`target/classes`** and dependency jars from your local repository, not the repackaged executable jar. Keep `-q` for stdio MCP so Maven does not write normal progress logs into the protocol stream.
2. Use the Maven profile **`stdio-mcp-dev`**, which injects the same stdio-related `-D` flags as a jar launch without forcing Spring Boot keep-alive. Example config: [inspector-mcp-dev.json](inspector-mcp-dev.json) (replace `${workspaceFolder}` with your repo path if the client does not expand it).
3. While coding, run **`mvn compile`** or **`mvn test`**; restart the MCP server in the IDE to pick up changes. Run **`mvn package`** only when you need a distributable jar **after** stopping every process that was started with **`java -jar .../target/...jar`** (including duplicate MCP workers).

**If you must keep `java -jar`**

- Stop the MCP server (and any stray `java-tuning-agent` JVMs) before **`mvn package`**, or build to another file name / directory with a one-off copy step after stopping the server.

## Further reading

- Agent pack: [agent-pack/java-tuning-agent/README.md](agent-pack/java-tuning-agent/README.md)
- Cursor workflow skill: [`.cursor/skills/java-tuning-agent-workflow/SKILL.md`](.cursor/skills/java-tuning-agent-workflow/SKILL.md)  
- Offline mode requirements: [docs/offline-mode-spec.md](docs/offline-mode-spec.md)  
- Design: [docs/superpowers/specs/2026-04-11-memory-gc-diagnosis-agent-design.md](docs/superpowers/specs/2026-04-11-memory-gc-diagnosis-agent-design.md)  
- Offline design: [docs/superpowers/specs/2026-04-19-offline-mode-design.md](docs/superpowers/specs/2026-04-19-offline-mode-design.md)  
- Implementation plan (task breakdown): [docs/superpowers/plans/2026-04-11-memory-gc-diagnosis-agent.md](docs/superpowers/plans/2026-04-11-memory-gc-diagnosis-agent.md)  
- Offline implementation plan: [docs/superpowers/plans/2026-04-19-offline-mode.md](docs/superpowers/plans/2026-04-19-offline-mode.md)
