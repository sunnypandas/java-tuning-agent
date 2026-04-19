# java-tuning-agent — tool arguments reference

Server name in config is often **`java-tuning-agent`**; Cursor may expose it as **`user-java-tuning-agent`**. Use the MCP tools exposed by that server.

**Workflow:** See **SKILL.md** — `collectMemoryGcEvidence` requires the **mandatory step-3 scope gate** first (AskQuestion or explicit chat choice), including **snapshot-only**; the agent must not silently use all-`false` without user selection.

## 1. `listJavaApps`

Arguments: empty object `{}`.

## 2. `inspectJvmRuntime`

```json
{ "pid": 12345 }
```

`pid` must appear in the latest `listJavaApps` result.

## 3. `collectMemoryGcEvidence`

Wrapper key is **`request`**. All inner fields are required by schema; use `false` / `""` when not using a privileged option.

**Lightweight (no extra jcmd cost):**

```json
{
  "request": {
    "pid": 12345,
    "includeClassHistogram": false,
    "includeThreadDump": false,
    "includeHeapDump": false,
    "heapDumpOutputPath": "",
    "confirmationToken": ""
  }
}
```

**With histogram (needs explicit consent + non-blank token):**

```json
{
  "request": {
    "pid": 12345,
    "includeClassHistogram": true,
    "includeThreadDump": false,
    "includeHeapDump": false,
    "heapDumpOutputPath": "",
    "confirmationToken": "java-tuning-agent:ui-approval:v1:pid=12345:scopes=classHistogram"
  }
}
```

**`confirmationToken` — two valid sources:**

1. **Structured UI (preferred):** After checkbox-style multi-select (e.g. AskQuestion `allow_multiple: true`), set flags from selections and use the **canonical token** (no user typing required):

   ```text
   java-tuning-agent:ui-approval:v1:pid=<decimalPid>:scopes=<sorted-comma-list>
   ```

   Scopes are literal names: `classHistogram`, `heapDump`, `threadDump`, sorted alphabetically, comma-separated, no spaces. Example all three:

   `java-tuning-agent:ui-approval:v1:pid=12345:scopes=classHistogram,heapDump,threadDump`

2. **Chat text:** Copy the user’s **verbatim** approval phrase as `confirmationToken`, or normalize an unambiguous list of scopes into the canonical form above.

**Heap dump:** set `includeHeapDump: true`, `heapDumpOutputPath` to an **absolute** `.hprof` path, and non-blank `confirmationToken`.

**Combined approval (one user message):** The user may list class histogram, thread dump, and heap dump in a **single** reply. Set the three `include*` flags to match; use canonical token or verbatim phrase as `confirmationToken`.

**Default heap path:** If the user defers to “default”, resolve the OS temp directory to an absolute path and use `java-tuning-agent-heap-{pid}.hprof` there. State that full path in chat before calling the tool.

**All three privileged (example paths are illustrative — substitute real absolute temp + pid):**

```json
{
  "request": {
    "pid": 12345,
    "includeClassHistogram": true,
    "includeThreadDump": true,
    "includeHeapDump": true,
    "heapDumpOutputPath": "C:/Users/you/AppData/Local/Temp/java-tuning-agent-heap-12345.hprof",
    "confirmationToken": "java-tuning-agent:ui-approval:v1:pid=12345:scopes=classHistogram,heapDump,threadDump"
  }
}
```

## 4. `generateTuningAdvice`

**Lightweight advice (no extra collection inside this tool):**

```json
{
  "pid": 12345,
  "environment": "local",
  "optimizationGoal": "reduce GC pause and stable latency",
  "collectClassHistogram": false,
  "collectThreadDump": false,
  "includeHeapDump": false,
  "heapDumpOutputPath": "",
  "confirmationToken": "",
  "codeContextSummary": {
    "dependencies": [],
    "configuration": {},
    "applicationNames": ["MyApplication"],
    "sourceRoots": ["C:/absolute/path/to/repo"],
    "candidatePackages": ["com.example.app"]
  }
}
```

**With histogram inside generateTuningAdvice:** `collectClassHistogram: true` and non-blank `confirmationToken`. For source file hints, set **`sourceRoots`** to absolute roots.

**codeContextSummary fields:**

| Field | Use |
|-------|-----|
| `applicationNames` | Simple names of `@SpringBootApplication` or main classes |
| `sourceRoots` | Absolute paths to source trees (empty if unknown) |
| `candidatePackages` | Package prefixes to bias hotspot ranking |
| `dependencies` | Optional Maven/Gradle coordinates strings |
| `configuration` | Optional key/value map (pools, profiles, etc.) |

When unknown, use `[]` or `{}` as appropriate; the schema requires all five keys present.

**Mirroring step 3:** If you already ran `collectMemoryGcEvidence` with histogram/thread/heap, call `generateTuningAdvice` with the same `collectClassHistogram` / `collectThreadDump` / `includeHeapDump` / `heapDumpOutputPath` / `confirmationToken` values in the same turn after **one** consent (UI multi-select or chat).

**Response shape:** `TuningAdviceReport` includes **`formattedSummary`**: Markdown with fixed section order (`##` / `###`, lists, fenced `text` code blocks for evidence). **Show it in the chat as renderable Markdown** — paste the string into the message body **without** wrapping the whole thing in an outer code fence (that hides formatting). Add a short preamble above if needed. Do not paraphrase away `suspectedCodeHotspots` or other sections.
