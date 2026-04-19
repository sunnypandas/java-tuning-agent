# Java Tuning Agent Example

This example exposes MCP tools for local Java discovery, safe JVM inspection, **memory/GC-oriented diagnosis**, and structured tuning advice. It is configured to run as a local stdio MCP server, not as an HTTP service.

## What MCP clients should discover

After the server is started over stdio, MCP clients should see these tools:

| Tool | Role |
|------|------|
| `listJavaApps` | Discover JVM processes visible to the current user. |
| `inspectJvmRuntime` | Collect a **lightweight** readonly snapshot (`jcmd` + `jstat`) structured for diagnosis. |
| `collectMemoryGcEvidence` | Collect **medium-cost** evidence (class histogram; optional thread dump; optional **`GC.heap_dump`** to an absolute `.hprof` path) when `confirmationToken` is supplied for privileged options. Returned pack includes **`heapDumpPath`** when the dump file exists. |
| `generateTuningAdvice` | Run the **memory/GC diagnosis engine** for the PID and `CodeContextSummary`. Default: lightweight snapshot only; optional **`collectClassHistogram`** + **`confirmationToken`** runs histogram first (same policy as `collectMemoryGcEvidence`). Response includes **`formattedSummary`**: stable Markdown of the full report (`##` / `###`, lists, fenced `text` blocks for evidence). Hosts should **paste it into the message as renderable Markdown**—**not** wrapped in an outer code fence—so structure shows; avoid paraphrasing away **`suspectedCodeHotspots`**. |

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

## Structured report (`TuningAdviceReport`)

- **`findings`**: rule-based or inferred memory/GC findings (`TuningFinding`: title, severity, evidence, reasoning type, impact).  
- **`recommendations`**: JVM/app actions (`TuningRecommendation`).  
- **`suspectedCodeHotspots`**: class-level hints with optional file paths (`SuspectedCodeHotspot`).  
- **`missingData`**: explicit gaps (e.g. histogram not collected).  
- **`nextSteps`**: suggested follow-up evidence or validation.  
- **`confidence`**: `high` \| `medium` \| `low`.  
- **`confidenceReasons`**: short explanations for the confidence level.  
- **`formattedSummary`**: Markdown string with **fixed section order** (`Findings` → `Recommendations` → `Suspected code hotspots` → `Missing data` → `Next steps` → `Confidence`). MCP/LLM clients should **surface this field verbatim** in the chat body as **renderable Markdown** (no outer fence around the whole string—that hides headings and lists). Use an outer code fence only if the user wants a raw copy-paste blob or the host cannot render Markdown. A short preamble (PID, `.hprof` path) above the summary is fine. The structured JSON fields remain for programmatic use.

## `CodeContextSummary`

- **`dependencies`**, **`configuration`**, **`applicationNames`**: summary metadata for the workload.  
- **`sourceRoots`**: directories to search for `.java` files when correlating histogram types (optional).  
- **`candidatePackages`**: reserved for richer routing (optional).  
- Use **`CodeContextSummary.withoutSource(...)`** when you only have the first three fields.

## Safety model

- Default commands are limited to `jps`, `jcmd`, and `jstat` (see configuration below).
- The runtime collector uses readonly diagnostics such as `jcmd VM.flags`, `jcmd GC.heap_info`, and `jstat -gcutil`.
- **Privileged diagnostics** (class histogram, thread dump, heap dump via **`GC.heap_dump`**, JFR) require a non-blank **`confirmationToken`**; heap dump also requires a non-blank absolute **`heapDumpOutputPath`**. Otherwise collection fails fast with `IllegalArgumentException`.
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

This repository includes a **project skill** that runs the four MCP tools in sequence (`listJavaApps` → `inspectJvmRuntime` → `collectMemoryGcEvidence` → `generateTuningAdvice`), supports interactive PID disambiguation, and requires explicit user approval before privileged options (histogram, thread dump, heap dump).

- [`.cursor/skills/java-tuning-agent-workflow/SKILL.md`](.cursor/skills/java-tuning-agent-workflow/SKILL.md) — workflow instructions for the agent  
- [`.cursor/skills/java-tuning-agent-workflow/reference.md`](.cursor/skills/java-tuning-agent-workflow/reference.md) — JSON argument templates for each tool  
- [`.cursor/rules/java-tuning-agent-mcp.mdc`](.cursor/rules/java-tuning-agent-mcp.mdc) — project rule so Agent reads the workflow before using MCP tools  

Cursor **discovers** project skills from `.cursor/skills/` at startup (see [Agent Skills](https://cursor.com/docs/skills)). To confirm this repo’s skill is visible: **Cursor Settings → Rules → Agent Decides** (look for `java-tuning-agent-workflow`). You can also type **`/java-tuning-agent-workflow`** in **Agent** chat to attach it explicitly.

Separately, register the `java-tuning-agent` MCP server in Cursor MCP settings (same idea as the `mcp.json` example above). Skills and MCP are independent: the skill defines *how* to call the tools; the server must still be enabled for the tools to appear.

## Quick verification flow

1. Start or register the server using one of the methods above.
2. Call `listJavaApps`.
3. Pick a PID and call `inspectJvmRuntime`.
4. Call `generateTuningAdvice` with a `CodeContextSummary` and flags: lightweight defaults, or `collectClassHistogram: true` plus `confirmationToken` for histogram-backed findings and hotspots (`sourceRoots` recommended).
5. Optionally call `collectMemoryGcEvidence` alone if you only need the raw evidence pack.

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
- Design: [docs/superpowers/specs/2026-04-11-memory-gc-diagnosis-agent-design.md](docs/superpowers/specs/2026-04-11-memory-gc-diagnosis-agent-design.md)  
- Implementation plan (task breakdown): [docs/superpowers/plans/2026-04-11-memory-gc-diagnosis-agent.md](docs/superpowers/plans/2026-04-11-memory-gc-diagnosis-agent.md)


Join: https://teams.microsoft.com/meet/377640496112745?p=SKf7FnVtYJqZjhsPpy
Meeting ID: 377 640 496 112 745
Passcode: 3uW2iT9c