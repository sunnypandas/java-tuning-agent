# Java Tuning Agent Example

This example exposes MCP tools for local Java discovery, safe JVM inspection, **memory/GC-oriented diagnosis**, and structured tuning advice. It is configured to run as a local stdio MCP server, not as an HTTP service.

## What MCP clients should discover

After the server is started over stdio, MCP clients should see **12** tools: six for **live JVM** workflows and six for **offline / imported** bundles.

### Live JVM tools

| Tool | Role |
|------|------|
| `listJavaApps` | Discover JVM processes visible to the current user. |
| `inspectJvmRuntime` | Collect a **lightweight** readonly snapshot (`jcmd` + `jstat`) structured for diagnosis. |
| `inspectJvmRuntimeRepeated` | Collect repeated safe read-only snapshots for short trend analysis (`sampleCount`, `intervalMillis`, optional `includeThreadCount` / `includeClassCount`). |
| `recordJvmFlightRecording` | Record one short Java Flight Recorder session for a PID and return the `.jfr` path plus a bounded summary of GC, allocation, thread/lock, and execution sample events. Requires `confirmationToken` and an absolute `jfrOutputPath`. |
| `collectMemoryGcEvidence` | Collect **medium-cost** evidence (class histogram; optional thread dump; optional **`GC.heap_dump`** to an absolute `.hprof` path) when `confirmationToken` is supplied for privileged options. Returned pack includes **`heapDumpPath`** when the dump file exists. When **`java-tuning-agent.heap-summary.auto-enabled`** is `true` (default) and the `.hprof` file is present, the pack also includes a **Shark-based shallow heap summary** (`heapShallowSummary`) used by the diagnosis engine and appended to `formattedSummary` (see below). |
| `generateTuningAdvice` | Run the **rule-based memory/GC diagnosis engine** for the PID and `CodeContextSummary`. Default: lightweight snapshot only; optional **`collectClassHistogram`** + **`confirmationToken`** runs histogram first (same policy as `collectMemoryGcEvidence`). Response includes **`formattedSummary`**: stable Markdown of the full report (`##` / `###`, lists, fenced `text` blocks for evidence), **plus** an optional **heap dump shallow summary** section when a dump was collected and indexed. Hosts should **paste it into the message as renderable Markdown**—**not** wrapped in an outer code fence—so structure shows; avoid paraphrasing away **`suspectedCodeHotspots`**. |

### Offline / imported bundle tools

| Tool | Role |
|------|------|
| `validateOfflineAnalysisDraft` | Validate an `OfflineBundleDraft` (B1–B6, recommended “absent” flags, degradation). |
| `submitOfflineHeapDumpChunk` | Upload one Base64 chunk of a large `.hprof` (with `uploadId` / `chunkIndex` / `chunkTotal`). |
| `finalizeOfflineHeapDump` | Merge chunks, verify SHA-256 and size, return absolute path for `heapDumpAbsolutePath`. |
| `generateOfflineTuningAdvice` | Assemble `MemoryGcEvidencePack` from the draft and run the same `generateAdviceFromEvidence` path as online. If `heapDumpAbsolutePath` points to an existing file and heap summary auto-mode is on, the server **indexes the dump with Shark** and feeds **`heapShallowSummary`** into rules and `formattedSummary`. |
| `summarizeOfflineHeapDumpFile` | **Optional** ad-hoc call: return Markdown + structured top shallow-by-class rows for a local `.hprof` without running the full advice pipeline. |
| `analyzeOfflineHeapRetention` | Analyze an existing `.hprof` for holder-oriented retention evidence; `analysisDepth=deep` attempts retained-style analysis and falls back honestly. |

**Diagnosis engine note:** Findings and recommendations are produced by **deterministic Java rules** (`MemoryGcDiagnosisEngine`), including optional **heap shallow dominance** when `heapShallowSummary` is present—not by the LLM. The model’s role is tool orchestration and explanation. Shallow totals are **not** MAT retained-size / dominator analysis.

**Configuration (heap shallow summary):**

| Property | Default | Meaning |
|----------|---------|--------|
| `java-tuning-agent.heap-summary.auto-enabled` | `true` | When `true`, automatically run Shark indexing when a `.hprof` path is valid (online after `GC.heap_dump`, or offline draft path). Set `false` to skip indexing (large dumps / CI). |
| `java-tuning-agent.offline.heap-summary.default-top-classes` | `40` | Max types in the shallow leader table (Markdown + structured entries). |
| `java-tuning-agent.offline.heap-summary.default-max-output-chars` | `32000` | Max Markdown characters for the summary block. |

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

3. **Evidence-only:** `collectMemoryGcEvidence(MemoryGcEvidenceRequest)` still returns a raw **`MemoryGcEvidencePack`** for clients that want the pack without advice.

4. **Java API:** **`TuningAdviceRequest`** with optional **`classHistogramHint`** still works via **`JavaTuningWorkflowService.generateAdvice`** (no extra `jcmd` when you already have a histogram). See [docs/superpowers/specs/2026-04-11-memory-gc-diagnosis-agent-design.md](docs/superpowers/specs/2026-04-11-memory-gc-diagnosis-agent-design.md) §16.

5. **Source-aware hotspots:** Populate **`CodeContextSummary.sourceRoots`** (e.g. `compat/memory-leak-demo` or `.../src/main/java`). With a histogram in the same diagnosis request, **`suspectedCodeHotspot`** entries may include **`fileHint`** paths to matching `.java` files.

## JFR short profiling flow

Use `recordJvmFlightRecording` when a lightweight snapshot or repeated `jstat` samples show that you need profiling evidence rather than another heap artifact.

Required fields:

- `pid`: target JVM from `listJavaApps`
- `durationSeconds`: bounded recording window, typically 30
- `settings`: `profile` for more profiling signal or `default` for lower overhead
- `jfrOutputPath`: absolute path ending in `.jfr`; the file must not already exist
- `maxSummaryEvents`: parser event cap, typically `200000`
- `confirmationToken`: non-blank approval token

The tool runs one bounded `jcmd JFR.start ... duration=<Ns> filename=<path>` recording. It does not expose public `JFR.stop` lifecycle management, does not overwrite existing recordings, and does not feed JFR findings into `generateTuningAdvice` yet.

## Structured report (`TuningAdviceReport`)

- **`findings`**: rule-based or inferred memory/GC findings (`TuningFinding`: title, severity, evidence, reasoning type, impact).  
- **`recommendations`**: JVM/app actions (`TuningRecommendation`).  
- **`suspectedCodeHotspots`**: class-level hints with optional file paths (`SuspectedCodeHotspot`).  
- **`missingData`**: explicit gaps (e.g. histogram not collected).  
- **`nextSteps`**: suggested follow-up evidence or validation.  
- **`confidence`**: `high` \| `medium` \| `low`.  
- **`confidenceReasons`**: short explanations for the confidence level.  
- **`formattedSummary`**: Markdown string with **fixed section order** for the rule-based report (`Findings` → `Recommendations` → `Suspected code hotspots` → `Missing data` → `Next steps` → `Confidence`). When a heap dump was **indexed** (Shark), the same string may end with an **extra** `### Heap dump file summary (local, shallow by class)` block (or a short **failed** note if indexing errored). MCP/LLM clients should **surface this field verbatim** in the chat body as **renderable Markdown** (no outer fence around the whole string—that hides headings and lists). Use an outer code fence only if the user wants a raw copy-paste blob or the host cannot render Markdown. A short preamble (PID, `.hprof` path) above the summary is fine. The structured JSON fields remain for programmatic use.

## `CodeContextSummary`

- **`dependencies`**, **`configuration`**, **`applicationNames`**: summary metadata for the workload.  
- **`sourceRoots`**: directories to search for `.java` files when correlating histogram types (optional).  
- **`candidatePackages`**: reserved for richer routing (optional).  
- Use **`CodeContextSummary.withoutSource(...)`** when you only have the first three fields.

## Safety model

- Default commands are limited to `jps`, `jcmd`, and `jstat` (see configuration below).
- The runtime collector uses readonly diagnostics such as `jcmd VM.flags`, `jcmd GC.heap_info`, and `jstat -gcutil`.
- **Privileged diagnostics** (class histogram, thread dump, heap dump via **`GC.heap_dump`**, JFR short recording) require a non-blank **`confirmationToken`**. Heap dump also requires a non-blank absolute **`heapDumpOutputPath`**; JFR requires a non-blank absolute **`jfrOutputPath`** ending in `.jfr`. Otherwise collection fails fast with `IllegalArgumentException`.
- The example is intended for local development or controlled readonly inspection of JVM processes visible to the current user.
- **Thread dump:** requesting `includeThreadDump` may still yield a warning in the evidence pack until full collection is implemented; histogram collection is the supported upgrade path today.

## Configuration

The default command whitelist is in [src/main/resources/application.yml](src/main/resources/application.yml) under `java-tuning-agent.command-whitelist`.

The MCP server is configured as:

- `spring.main.web-application-type=none`
- `spring.ai.mcp.server.stdio=true`
- `spring.ai.mcp.server.name=java-tuning-agent`

## Use from Codex

Register this project as a local stdio MCP server (run from the repository root):

```bash
codex mcp add java-tuning-agent -- mvn -f pom.xml spring-boot:run
```

Verify:

```bash
codex mcp list
```

Then start a Codex session in this repository and ask it to use the `java-tuning-agent` MCP tools.

## Use from VS Code / GitHub Copilot

Create or open `.vscode/mcp.json` in the repository root:

```json
{
  "servers": {
    "java-tuning-agent": {
      "type": "stdio",
      "command": "mvn",
      "args": [
        "-f",
        "${workspaceFolder}/pom.xml",
        "spring-boot:run"
      ]
    }
  }
}
```

Then:

1. Run `MCP: Open Workspace Folder Configuration` if you want to edit `mcp.json` from the Command Palette.
2. Run `MCP: List Servers`.
3. Start the `java-tuning-agent` server if it is not already running.
4. Open Copilot Chat or agent mode and confirm the tools are available.

For user-level configuration, put the same server entry in your user `mcp.json`.

## Use from Cursor

This repository includes a **project skill** that runs the **live** MCP tools in sequence (`listJavaApps` → `inspectJvmRuntime` → optional `inspectJvmRuntimeRepeated` → optional `recordJvmFlightRecording` → `collectMemoryGcEvidence` → `generateTuningAdvice`), supports interactive PID disambiguation, and requires explicit user approval before privileged options (histogram, thread dump, heap dump, JFR). For **offline** bundles, the skill documents a separate path using all six offline tools, including `analyzeOfflineHeapRetention`.

- [`.cursor/skills/java-tuning-agent-workflow/SKILL.md`](.cursor/skills/java-tuning-agent-workflow/SKILL.md) — workflow instructions for the agent  
- [`.cursor/skills/java-tuning-agent-workflow/reference.md`](.cursor/skills/java-tuning-agent-workflow/reference.md) — JSON argument templates for each tool  
- [`.cursor/rules/java-tuning-agent-mcp.mdc`](.cursor/rules/java-tuning-agent-mcp.mdc) — project rule so Agent reads the workflow before using MCP tools  

Cursor **discovers** project skills from `.cursor/skills/` at startup (see [Agent Skills](https://cursor.com/docs/skills)). To confirm this repo’s skill is visible: **Cursor Settings → Rules → Agent Decides** (look for `java-tuning-agent-workflow`). You can also type **`/java-tuning-agent-workflow`** in **Agent** chat to attach it explicitly.

Separately, register the `java-tuning-agent` MCP server in Cursor MCP settings (same idea as the `mcp.json` example above). Skills and MCP are independent: the skill defines *how* to call the tools; the server must still be enabled for the tools to appear.

## Quick verification flow

1. Start or register the server using one of the methods above.
2. Call `listJavaApps`.
3. Pick a PID and call `inspectJvmRuntime`.
4. Optionally call `inspectJvmRuntimeRepeated` with `sampleCount` and `intervalMillis` when you need short trend evidence.
5. Optionally call `recordJvmFlightRecording` with `durationSeconds`, `settings`, an absolute `jfrOutputPath`, `maxSummaryEvents`, and `confirmationToken` when you need short profiling evidence.
6. Call `generateTuningAdvice` with a `CodeContextSummary` and flags: lightweight defaults, or `collectClassHistogram: true` plus `confirmationToken` for histogram-backed findings and hotspots (`sourceRoots` recommended).
7. Optionally call `collectMemoryGcEvidence` alone if you only need the raw evidence pack, or `analyzeOfflineHeapRetention` later on a local `heapDumpAbsolutePath` with `analysisDepth`.

## Troubleshooting

- If the IDE cannot discover the server, ensure it launches the process in foreground stdio mode rather than as a detached background process.
- If the server starts but no tools appear, restart the MCP server from the IDE and refresh the tool cache.
- If process discovery returns nothing, confirm `jps`, `jcmd`, and `jstat` are on `PATH`.
- If the server starts as a web app and fails, check that [src/main/resources/application.yml](src/main/resources/application.yml) still sets `spring.main.web-application-type: none`.

### Windows: `mvn package` fails because the jar is in use

On Windows, **`java -jar target/java-tuning-agent-*.jar`** keeps that file **locked**, so Maven cannot overwrite it during **`package`**.

**Recommended local workflow**

1. Point MCP at **`mvn spring-boot:run`** instead of **`java -jar`** so the process loads **`target/classes`** and dependency jars from your local repository, not the repackaged executable jar.
2. Use the Maven profile **`stdio-mcp-dev`**, which injects the same stdio-related `-D` flags as a jar launch. Example config: [inspector-mcp-dev.json](inspector-mcp-dev.json) (replace `${workspaceFolder}` with your repo path if the client does not expand it).
3. While coding, run **`mvn compile`** or **`mvn test`**; restart the MCP server in the IDE to pick up changes. Run **`mvn package`** only when you need a distributable jar **after** stopping every process that was started with **`java -jar .../target/...jar`** (including duplicate MCP workers).

**If you must keep `java -jar`**

- Stop the MCP server (and any stray `java-tuning-agent` JVMs) before **`mvn package`**, or build to another file name / directory with a one-off copy step after stopping the server.

## Further reading

- Cursor workflow skill: [`.cursor/skills/java-tuning-agent-workflow/SKILL.md`](.cursor/skills/java-tuning-agent-workflow/SKILL.md)  
- Offline mode requirements: [docs/offline-mode-spec.md](docs/offline-mode-spec.md)  
- Design: [docs/superpowers/specs/2026-04-11-memory-gc-diagnosis-agent-design.md](docs/superpowers/specs/2026-04-11-memory-gc-diagnosis-agent-design.md)  
- Offline design: [docs/superpowers/specs/2026-04-19-offline-mode-design.md](docs/superpowers/specs/2026-04-19-offline-mode-design.md)  
- Implementation plan (task breakdown): [docs/superpowers/plans/2026-04-11-memory-gc-diagnosis-agent.md](docs/superpowers/plans/2026-04-11-memory-gc-diagnosis-agent.md)  
- Offline implementation plan: [docs/superpowers/plans/2026-04-19-offline-mode.md](docs/superpowers/plans/2026-04-19-offline-mode.md)
