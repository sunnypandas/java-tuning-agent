# Memory Leak Demo

Spring Boot app that **intentionally** grows heap, retains `byte[]`, optionally triggers a **Java-level deadlock**, and can run **ephemeral allocation churn** so you can exercise `java-tuning-agent` (MCP tools, histogram, thread dump, diagnosis rules) on a known codebase.

- **Port:** `8091`  
- **Application name:** `memory-leak-demo` (for `listJavaApps` / discovery)  
- **Source root for hotspots:** from repo root use `compat/memory-leak-demo` in `CodeContextSummary.sourceRoots`.

## Quick machine-readable guide

After the app is up:

```http
GET http://localhost:8091/api/leak/validation-guide
```

Returns JSON: scenarios, suggested JVM flags, `curl` examples, and MCP hints aligned with this README.

## Run (recommended JVM flags)

**Default (retained growth + G1, matches agent heap parser):**

```powershell
mvn -f compat/memory-leak-demo/pom.xml spring-boot:run "-Dspring-boot.run.jvmArguments=-Xms128m -Xmx256m -XX:+UseG1GC"
```

**Large Xms/Xmx spread** (for agent rule *Heap min/max spread may amplify resizing work*):

```powershell
mvn -f compat/memory-leak-demo/pom.xml spring-boot:run "-Dspring-boot.run.jvmArguments=-Xms32m -Xmx256m -XX:+UseG1GC"
```

**Tighter heap** (easier to reach high utilization after fewer `allocate` calls):

```powershell
mvn -f compat/memory-leak-demo/pom.xml spring-boot:run "-Dspring-boot.run.jvmArguments=-Xms96m -Xmx192m -XX:+UseG1GC"
```

Use `curl.exe` on Windows so PowerShell does not rewrite `curl`.

## HTTP API overview

| Method | Path | Purpose |
|--------|------|--------|
| POST | `/api/leak/allocate` | Retain `AllocationRecord` (+ `byte[]`); best for **histogram → `AllocationRecord` + source hotspot** |
| GET | `/api/leak/stats` | Current retained stats (record store) |
| POST | `/api/leak/clear` | Clear record store |
| POST | `/api/leak/raw/allocate` | Retain plain **`byte[]`** only; best for **`[B`-heavy histogram** |
| GET | `/api/leak/raw/stats` | Stats for raw store |
| POST | `/api/leak/raw/clear` | Clear raw store |
| POST | `/api/leak/churn` | Short-lived allocations (no retention); increases **young GC** over repeated calls |
| POST | `/api/leak/deadlock/trigger` | One-shot **deadlock** (two daemon threads); **once per JVM** — restart to repeat |
| GET | `/api/leak/deadlock/status` | Whether deadlock feature is on and if it already ran |
| GET | `/api/leak/validation-guide` | JSON checklist for the tuning agent |

`POST /allocate` and `/raw/allocate` share the same JSON body: `entries` (1–2000), `payloadKb` (1–1024), non-blank `tag`.

### Examples (PowerShell)

Retained records (dominant type tends to be `AllocationRecord`):

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/allocate -H "Content-Type: application/json" -d "{\"entries\":120,\"payloadKb\":512,\"tag\":\"round-1\"}"
```

Retained raw bytes (dominant type tends to be `[B`):

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/raw/allocate -H "Content-Type: application/json" -d "{\"entries\":200,\"payloadKb\":256,\"tag\":\"raw-b\"}"
```

Churn (repeat or raise `iterations`; agent churn rule needs **very high** YGC counts):

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/churn -H "Content-Type: application/json" -d "{\"iterations\":2000000,\"payloadBytes\":4096}"
```

Deadlock (then collect thread dump with confirmation in the agent):

```powershell
curl.exe -X POST http://localhost:8091/api/leak/deadlock/trigger
```

Clear everything retained:

```powershell
curl.exe -X POST http://localhost:8091/api/leak/clear
curl.exe -X POST http://localhost:8091/api/leak/raw/clear
```

## MCP / agent workflow (happy path)

1. Start this demo with **G1** (`-XX:+UseG1GC`) and a bounded heap (see above).  
2. `listJavaApps` → PID for `memory-leak-demo`.  
3. `inspectJvmRuntime(pid)` — baseline.  
4. Drive one scenario (allocate, raw allocate, churn loop, or deadlock trigger).  
5. `inspectJvmRuntime` again **or** `collectMemoryGcEvidence` / `generateTuningAdvice` with:
   - `collectClassHistogram` + non-blank `confirmationToken` when you want histogram-backed findings.  
   - `collectThreadDump` + token after `deadlock/trigger`.  
6. `generateTuningAdvice` with `CodeContextSummary` including:
   - `sourceRoots`: `["compat/memory-leak-demo"]` (repo root as cwd for the agent).  
   - Optional text in `dependencies` / `configuration` describing `AllocationRecord`, `/api/leak/*`, and Spring MVC.  
7. Clear stores and/or restart JVM between scenarios to avoid mixing effects.

## Configuration

`application.yml`:

- `memory-leak-demo.features.deadlock-demo` — default `true`. Set `false` if you must not start deadlock threads (e.g. shared lab).

## Build / test

```powershell
mvn -f compat/memory-leak-demo/pom.xml test
```

## Design cross-reference

See `docs/superpowers/specs/2026-04-11-memory-gc-diagnosis-agent-design.md` §12.3 for the original compatibility scenario; this module implements the concrete HTTP surface and JVM examples for that flow.
