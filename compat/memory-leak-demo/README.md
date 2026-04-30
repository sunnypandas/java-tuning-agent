# Memory Leak Demo

Spring Boot app that intentionally grows heap, retains `byte[]`, retains direct buffers, grows generated proxy classloaders, optionally triggers a Java-level deadlock, and can run allocation/contention workloads for JFR. It gives `java-tuning-agent` a known codebase for MCP tools, histogram, thread dump, repeated sampling, JFR, native-memory, and diagnosis-rule validation.

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

**Native memory scenarios** (`/direct/*`, class metadata/NMT evidence):

```powershell
mvn -f compat/memory-leak-demo/pom.xml spring-boot:run "-Dspring-boot.run.jvmArguments=-Xms128m -Xmx256m -XX:+UseG1GC -XX:NativeMemoryTracking=summary"
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
| POST | `/api/leak/direct/allocate` | Retain direct `ByteBuffer`; best with `-XX:NativeMemoryTracking=summary` |
| GET | `/api/leak/direct/stats` | Stats for direct buffer store |
| POST | `/api/leak/direct/clear` | Clear direct buffer store |
| POST | `/api/leak/classloader/allocate` | Retain generated proxy classloaders; useful for class-count/metaspace trend checks |
| GET | `/api/leak/classloader/stats` | Stats for retained generated classloaders |
| POST | `/api/leak/classloader/clear` | Clear retained proxy classloaders |
| POST | `/api/leak/churn` | Short-lived allocations (no retention); increases **young GC** over repeated calls |
| POST | `/api/leak/jfr-workload` | Short allocation + monitor contention workload for `recordJvmFlightRecording` |
| POST | `/api/leak/deadlock/trigger` | One-shot **deadlock** (two daemon threads); **once per JVM** — restart to repeat |
| GET | `/api/leak/deadlock/status` | Whether deadlock feature is on and if it already ran |
| GET | `/api/leak/validation-guide` | JSON checklist for the tuning agent |

`POST /allocate` and `/raw/allocate` share the same JSON body: `entries` (1–2000), `payloadKb` (1–1024), non-blank `tag`.

### Examples

Retained records (dominant type tends to be `AllocationRecord`):

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/allocate -H "Content-Type: application/json" -d "{\"entries\":120,\"payloadKb\":512,\"tag\":\"round-1\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/allocate -H 'Content-Type: application/json' -d '{"entries":120,"payloadKb":512,"tag":"round-1"}'
```

Retained raw bytes (dominant type tends to be `[B`):

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/raw/allocate -H "Content-Type: application/json" -d "{\"entries\":200,\"payloadKb\":256,\"tag\":\"raw-b\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/raw/allocate -H 'Content-Type: application/json' -d '{"entries":200,"payloadKb":256,"tag":"raw-b"}'
```

Churn (repeat or raise `iterations`; agent churn rule needs **very high** YGC counts):

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/churn -H "Content-Type: application/json" -d "{\"iterations\":2000000,\"payloadBytes\":4096}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/churn -H 'Content-Type: application/json' -d '{"iterations":2000000,"payloadBytes":4096}'
```

Direct buffers (observe with NMT/native-memory evidence):

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/direct/allocate -H "Content-Type: application/json" -d "{\"entries\":128,\"payloadKb\":1024,\"tag\":\"direct-128m\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/direct/allocate -H 'Content-Type: application/json' -d '{"entries":128,"payloadKb":1024,"tag":"direct-128m"}'
```

Generated classloaders (observe with `inspectJvmRuntimeRepeated` and class count):

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/classloader/allocate -H "Content-Type: application/json" -d "{\"loaders\":1000,\"tag\":\"proxy-loaders\"}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/classloader/allocate -H 'Content-Type: application/json' -d '{"loaders":1000,"tag":"proxy-loaders"}'
```

JFR workload (start `recordJvmFlightRecording` first, then run this while recording):

PowerShell:

```powershell
curl.exe --% -X POST http://localhost:8091/api/leak/jfr-workload -H "Content-Type: application/json" -d "{\"durationSeconds\":20,\"workerThreads\":4,\"payloadBytes\":4096}"
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/jfr-workload -H 'Content-Type: application/json' -d '{"durationSeconds":20,"workerThreads":4,"payloadBytes":4096}'
```

Deadlock (then collect thread dump with confirmation in the agent):

PowerShell:

```powershell
curl.exe -X POST http://localhost:8091/api/leak/deadlock/trigger
```

macOS/Linux shell:

```bash
curl -X POST http://localhost:8091/api/leak/deadlock/trigger
```

Clear everything retained:

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

## MCP / agent workflow (happy path)

1. Start this demo with **G1** (`-XX:+UseG1GC`) and a bounded heap (see above).  
2. `listJavaApps` → PID for `memory-leak-demo`.  
3. `inspectJvmRuntime(pid)` — baseline.  
4. Drive one scenario (allocate, raw allocate, churn loop, or deadlock trigger).  
5. `inspectJvmRuntime` again **or** `collectMemoryGcEvidence` with:
   - `includeClassHistogram` + non-blank `confirmationToken` when you want histogram-backed findings.
   - `includeThreadDump` + token after `deadlock/trigger`.
6. For trend and JFR checks, optionally call:
   - `inspectJvmRuntimeRepeated` while `/churn` or `/classloader/allocate` is running.
   - `recordJvmFlightRecording`, then call `/api/leak/jfr-workload` during the recording window.
7. `generateTuningAdviceFromEvidence` with the returned evidence pack and `CodeContextSummary` including:
   - `sourceRoots`: `["compat/memory-leak-demo"]` (repo root as cwd for the agent).  
   - Optional text in `dependencies` / `configuration` describing `AllocationRecord`, `/api/leak/*`, and Spring MVC.  
8. Use `generateTuningAdvice` only as a one-shot shortcut when you did not already call `collectMemoryGcEvidence`.
9. Clear stores and/or restart JVM between scenarios to avoid mixing effects.

## Offline export bundle

Use the repo export script when you want to validate offline mode from this demo process. It writes B1-B6 required files, recommended R1-R3 markers, optional native/resource-budget evidence, and `offline-draft-template.json` for `validateOfflineAnalysisDraft`.

macOS/Linux shell:

```bash
scripts/export-jvm-diagnostics.sh --export-dir /tmp/memory-leak-demo-offline --process-id <pid> --gc-log-path <optional-gc-log> --app-log-path <optional-app-log>
```

PowerShell:

```powershell
.\scripts\export-jvm-diagnostics.ps1 -ExportDir 'C:\tmp\memory-leak-demo-offline' -ProcessId <pid> -GcLogPath '<optional-gc-log>' -AppLogPath '<optional-app-log>'
```

For a fast smoke test without a heap dump, add `--skip-heap-dump` / `-SkipHeapDump`; then either fill `heapDumpAbsolutePath` later or run offline advice with `proceedWithMissingRequired=true`.

## Configuration

`application.yml`:

- `memory-leak-demo.features.deadlock-demo` — default `true`. Set `false` if you must not start deadlock threads (e.g. shared lab).

## Build / test

```powershell
mvn -f compat/memory-leak-demo/pom.xml test
```

## Design cross-reference

See `docs/superpowers/specs/2026-04-11-memory-gc-diagnosis-agent-design.md` §12.3 for the original compatibility scenario; this module implements the concrete HTTP surface and JVM examples for that flow.
