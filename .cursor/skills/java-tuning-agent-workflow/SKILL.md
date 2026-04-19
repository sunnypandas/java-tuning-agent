---
name: java-tuning-agent-workflow
description: >-
  Runs the java-tuning-agent MCP pipeline in order: listJavaApps → inspectJvmRuntime →
  mandatory step-3 scope gate (AskQuestion or prior chat) → collectMemoryGcEvidence →
  generateTuningAdvice. No silent quick pass: user must choose quick-only or privileged scopes.
  PID disambiguation, canonical confirmation tokens, formattedSummary as Markdown (no outer fence).
  Triggers:
  JVM tuning, GC pause or footprint goals, memory leak diagnosis, heap pressure, local Java
  process analysis, jcmd/jstat/MCP tools user-java-tuning-agent or java-tuning-agent.
  中文场景：JVM调优、内存、堆、GC、垃圾回收、内存泄漏、Full GC、Java进程、结合源码诊断。
  Also when the user names an app or provides a source path for deeper CodeContextSummary.
---

# Java tuning agent — end-to-end MCP workflow

## Preconditions

- The **java-tuning-agent** MCP server is enabled (Cursor may show it as **user-java-tuning-agent**).
- Host commands **jps**, **jcmd**, **jstat** are available to that process; tools only work against JVMs the same user can see.
- Read [reference.md](reference.md) for exact JSON argument shapes when building tool calls.

## Default pipeline (four tools in order)

Execute **in this order**, carrying forward the chosen `pid` and any evidence into the final step:

| Step | Tool | Purpose |
|------|------|--------|
| 1 | `listJavaApps` | Discover local JVMs and metadata (`pid`, `displayName`, `mainClassOrJar`, `commandLine`, hints). |
| 2 | `inspectJvmRuntime` | Safe read-only metrics/snapshot for the selected `pid` (`jcmd` / `jstat` only). |
| 3 | `collectMemoryGcEvidence` | Optional **medium-cost** evidence (class histogram, thread dump, heap dump). **Never call until [Mandatory step-3 scope gate](#mandatory-step-3-scope-gate-no-silent-quick-pass) is satisfied.** |
| 4 | `generateTuningAdvice` | Structured tuning advice; may reuse runtime data and optional **code context**. |

### Phased execution (stable order — do not collapse)

Treat the workflow as **phases**. Completing an earlier phase is required before starting the next, except when the user’s **same message** already satisfies the gate (see below).

| Phase | Actions | Stop / wait until |
|-------|---------|-------------------|
| **P1 — Discovery** | `listJavaApps`; resolve target `pid` | Ambiguous PID → numbered list, **wait** for user choice |
| **P2 — Safe snapshot** | `inspectJvmRuntime(pid)` | Never batch with P3 tools before the step-3 gate when the host supports **AskQuestion** |
| **P3 — Scope gate** | User chooses step-3 evidence scope | See [Mandatory step-3 scope gate](#mandatory-step-3-scope-gate-no-silent-quick-pass) |
| **P3b — Evidence** | `collectMemoryGcEvidence` with flags matching the gate outcome | — |
| **P4 — Advice** | `generateTuningAdvice` mirroring P3b privileged flags + token | If `optimizationGoal` is unknown and not inferable in one short phrase, **ask once** before P4 |

**Pipeline rule:** Do not skip step 1. Do not call step 2 until a single target `pid` is agreed. **Do not call step 3 (`collectMemoryGcEvidence`) until the step-3 scope gate is satisfied** — including when the outcome is “quick pass only” (all `include*` false). Step 4 always runs last for the narrative “tuning analysis” outcome.

### Mandatory step-3 scope gate (no silent quick pass)

The agent **must not** choose step-3 scope on the user’s behalf. **“Quick pass” (all `includeClassHistogram` / `includeThreadDump` / `includeHeapDump` false) is a user choice**, not a default shortcut to skip UI.

**Gate satisfied when any one of these is true:**

1. **Structured UI:** The host supports **AskQuestion** (or equivalent), and the user has submitted a selection for step 3 **in this conversation** — including explicitly choosing **“仅快照 / 不采集 histogram·thread·heap”** (or English: **“Snapshot only — no histogram, thread dump, or heap dump”**) as the exclusive option, **or** a multi-select of privileged scopes.  
   - **Required options** for the question (minimum): **class histogram**, **thread dump**, **heap dump**, **snapshot only (no privileged collection)**. Use `allow_multiple: true` only for the three privileged items; implement **“snapshot only”** as a single exclusive option the user picks *instead of* the others, or use two-step UX if the host cannot express mutual exclusion — in that case **stop** after listing the four choices and wait for a **numbered reply** (see fallback).

2. **Prior / current chat:** The user’s message **already** states which scopes they want **or** explicitly requests snapshot-only / quick pass (e.g. “只要 jcmd 快照，不要 histogram”). Then map flags + token per [Single message covers histogram, thread dump, heap dump, and token](#single-message-covers-histogram-thread-dump-heap-dump-and-token); snapshot-only → all `include*` false, `confirmationToken` `""`.

3. **Fallback (no AskQuestion):** Print a **numbered list** of the same four choices (three privileged + snapshot-only) and **wait** for the user’s reply **before** `collectMemoryGcEvidence`.

**Forbidden shortcuts**

- **Do not** call `collectMemoryGcEvidence` in the **same assistant turn** as `listJavaApps` / `inspectJvmRuntime` **when** the host provides **AskQuestion**, unless the user’s **current message** already satisfies gate (2). Typical pattern: **turn A** → P1 + P2 + **AskQuestion**; **turn B** (after user answers) → P3b + P4.
- **Do not** set all `include*` false to “save time” without the user having chosen snapshot-only via (1), (2), or (3).

**Critical — step 3 visibility:** If the agent skips the step-3 gate and runs `collectMemoryGcEvidence` (privileged **or** snapshot-only) without **either** (i) a structured UI selection recorded after the question, **or** (ii) a user chat message that explicitly chose snapshot-only or listed scopes, the user sees **no** prompt — that is an agent mistake. For privileged flags, **also** require non-blank `confirmationToken` as today.

**Interactive pipeline:** After P1 and P2, briefly summarize results, then run the **scope gate** (AskQuestion preferred). After each later step, briefly summarize. **Fallback** for step 3 if no structured UI: numbered list + wait (see gate (3)).

## Recognizing user intents

### A — “Analyze the current project”

1. Treat **workspace root** as the primary `sourceRoots` entry (absolute path). Add other module roots if clearly a multi-module repo.
2. Infer **candidatePackages** from `pom.xml` / `build.gradle` (`groupId`, `group`, or Java package dirs under `src/main/java`).
3. Infer **applicationNames** when possible: search for `@SpringBootApplication` / known `main` class simple names; if uncertain, ask once.
4. Call **step 1**, then resolve **pid** (see [Resolving the target PID](#resolving-the-target-pid)). Prefer JVMs whose `mainClassOrJar` or `commandLine` matches this project’s artifact or main class over IDE/MCP/helper processes.
5. Run **P2** (`inspectJvmRuntime`), then the **step-3 scope gate** (see [Mandatory step-3 scope gate](#mandatory-step-3-scope-gate-no-silent-quick-pass)); only then **P3b** (`collectMemoryGcEvidence`) and **P4** (`generateTuningAdvice`). Use `environment` default **`local`** unless the user says otherwise. Set `optimizationGoal` from the user request or ask once before P4 if unclear.

### B — “App name + optional source path”

1. **Match name** against `listJavaApps` results: `displayName`, `mainClassOrJar` (simple class name or JAR file name), and substrings of `commandLine`.
2. **If 0 matches:** show a **short filtered list** (exclude obvious noise like pure `Jps`, or duplicate MCP agent JARs unless the user targets the agent itself) and ask them to pick **PID** or **index**.
3. **If 1 match:** confirm in one line, then proceed.
4. **If 2+ matches:** show numbered candidates with `pid`, `mainClassOrJar`, and one-line `commandLine` snippet; wait for user choice.
5. **Source path:**
   - **Provided:** add absolute path(s) to `codeContextSummary.sourceRoots`, refine `candidatePackages` / `applicationNames` from that tree if easy; step 4 can map histogram hints to files.
   - **Not provided:** use empty `sourceRoots` and minimal context; step 4 is **runtime-heavy** — state clearly that file-level hotspot hints may be limited.

## Resolving the target PID

- Never guess across ambiguous PIDs.
- **Deprioritize** for “current app” analysis: Red Hat JDT LS, Spring Boot language server, other editors’ Java language servers, duplicate **java-tuning-agent** MCP JAR rows unless explicitly requested.
- **Prefer:** `spring-boot:run`, the project’s `main` class, or a JAR path under the workspace.
- If still ambiguous, output a **numbered list** and stop until the user selects.

## Privileged and optional calls

### `collectMemoryGcEvidence` (step 3)

- **Cost / impact:** histogram and thread dump add pause/load; heap dump writes a large `.hprof` file and needs disk space.
- **Required user interaction:** Before any `includeClassHistogram`, `includeThreadDump`, or `includeHeapDump` is `true`, the user must **explicitly consent** via **either** [Structured UI approval](#structured-ui-approval-preferred) **or** a clear chat reply. The MCP API still requires a **non-blank** `confirmationToken` string — use the [Canonical UI token](#canonical-ui-token-format) when consent came from multi-select; otherwise you may copy the user’s **exact** approval phrase as the token.
- **Heap dump:** `includeHeapDump: true` requires an **absolute** `heapDumpOutputPath` ending in `.hprof`; agree the path with the user first, unless they defer to the [Default heap dump path](#default-heap-dump-path) below.
- **Snapshot-only (quick pass):** After the user **explicitly chooses** snapshot-only via the [step-3 gate](#mandatory-step-3-scope-gate-no-silent-quick-pass), call step 3 with **all** `include*` flags **`false`**, `heapDumpOutputPath` `""`, and `confirmationToken` `""` (still pass the full `request` object per schema), then step 4 lightweight. The agent **must not** pick this path without that explicit choice.

### Structured UI approval (preferred)

When the agent can emit a **structured question** (e.g. Cursor **AskQuestion**):

1. **Required:** Offer **snapshot only** (no histogram, no thread dump, no heap dump) as a **first-class** choice — same prominence as privileged options. Users must be able to opt into lightweight step 3 **without** the agent pre-deciding.
2. **Privileged multi-select:** For **class histogram**, **thread dump**, **heap dump**, use **AskQuestion** with `allow_multiple: true` **only if** the host can express “snapshot only” as mutually exclusive (e.g. separate single-choice question first: “Evidence: (A) Snapshot only (B) Include privileged collections” → if B, then multi-select which privileged). If mutual exclusion is awkward, use **two questions** or fall back to a **numbered list** (see [Mandatory step-3 scope gate](#mandatory-step-3-scope-gate-no-silent-quick-pass)).
3. After the user submits, set each `include*` flag **only** for options they selected; snapshot-only → all `false`.
4. Set `confirmationToken` to the [Canonical UI token](#canonical-ui-token-format) for that PID and selection set when **any** privileged scope is on (do **not** require the user to type a sentence). Snapshot-only → `confirmationToken` `""`.

This is the **default** consent path for step 3 when structured UI is available — better usability than mandatory free-form text.

### Canonical UI token format

After UI multi-select (or when encoding the same intent deterministically), use **exactly** this pattern so runs are auditable and consistent:

```text
java-tuning-agent:ui-approval:v1:pid=<decimalPid>:scopes=<sorted-comma-list>
```

- **`<decimalPid>`:** Target JVM pid for this call (same as `request.pid`).
- **`<sorted-comma-list>`:** Subset of `classHistogram`, `heapDump`, `threadDump`, sorted **alphabetically**, no spaces.  
  - Examples: `classHistogram` only → `scopes=classHistogram`. All three → `scopes=classHistogram,heapDump,threadDump`.
- If **no** privileged scope was selected, keep all `include*` `false` and `confirmationToken` `""` (do not emit a ui-approval token).

Use this **same** string as `confirmationToken` on every privileged MCP call in that turn (`collectMemoryGcEvidence` and matching `generateTuningAdvice` flags).

### Single message covers histogram, thread dump, heap dump, and token

The user may approve **everything in one chat message** instead of separate rounds:

- They state which of **class histogram**, **thread dump**, and **heap dump** they want (any subset or all three).
- **Token:** Either they paste a phrase you copy verbatim as `confirmationToken`, **or** you normalize their intent into the [Canonical UI token](#canonical-ui-token-format) if their message unambiguously lists the same scopes (still non-blank).

Use that **same non-blank string** as `confirmationToken` for every privileged MCP call in that turn (`collectMemoryGcEvidence` and, if applicable, `generateTuningAdvice` flags mirroring the same scope).

### Default heap dump path

When the user says to use the **default** path (or does not care, and heap dump is approved):

1. Resolve the host OS **default temp directory** to an **absolute** path (e.g. Windows: `%TEMP%` / `%LOCALAPPDATA%\Temp`; Unix: `/tmp` or `$TMPDIR`).
2. Set `heapDumpOutputPath` to `{thatDirectory}/java-tuning-agent-heap-{pid}.hprof` (forward slashes or platform-native separators both OK if the tool accepts them).
3. **Before** running `includeHeapDump: true`, print the **full resolved absolute path** in the reply so the user knows where the file will land.

If the user later specifies a different directory, override this default for that run only.

### `generateTuningAdvice` (step 4)

- **Lightweight (default):** `collectClassHistogram`, `collectThreadDump`, `includeHeapDump` all **`false`**, `confirmationToken` `""`, `heapDumpOutputPath` `""` — uses snapshot-style data without extra privileged collection inside this tool.
- **Richer analysis:** Offer toggles mirroring step 3 (histogram / thread / heap). Any of those `true` requires the same **non-blank** `confirmationToken` (and heap path rules for heap dump) — use the **canonical UI token** or the user’s verbatim phrase, same as step 3. If step 3 already ran with privileged flags, mirror the **same** scope and token here when you want advice to include the same evidence; one consent (UI or chat) covers both tools in one turn.
- Always set **`optimizationGoal`** and **`environment`** explicitly. Fill **`codeContextSummary`** per intent A or B; use empty arrays/objects where unknown.

## Output expectations — `formattedSummary`

After step 4, `TuningAdviceReport` includes **`formattedSummary`**: a **single Markdown document** (not HTML) with a **fixed section order**: Findings → Recommendations → Suspected code hotspots → Missing data → Next steps → Confidence. The generator uses **`##` / `###` headings**, **bold labels**, **bullet lists**, and **fenced code blocks** (info string `text`) for long evidence strings so structure survives in the string.

### How to show it in chat (readability)

- **Default — render as Markdown:** Paste or stream `formattedSummary` **directly into the assistant message body** (no surrounding fence). The client preview then applies headings, bold, lists, and inner code fences — this is the intended reading experience.
- **Do not** wrap the **entire** `formattedSummary` in an outer Markdown code fence (three backticks with an optional `markdown` language tag). That forces **monospace plain text** and **hides** structure; users perceive it as “unformatted.” Only use an outer fence if the user **explicitly** asks for a verbatim copy-paste blob or the host cannot render Markdown at all.
- **Preamble is fine:** A short intro (PID, heap path, consent scope) **above** the pasted summary is encouraged; keep the summary body **uncovered** so it still renders.
- **Do not replace** `formattedSummary` with a hand-written paraphrase that drops **`suspectedCodeHotspots`**, findings, or recommendations. If you add interpretation, do it **after** the full summary or in a separate short section.
- **JSON context:** In raw tool JSON, newlines appear as `\n`; that is normal. The string content is still Markdown once unescaped for display.

If the user skipped step 3 or privileged flags, `formattedSummary` still states missing data / empty hotspot sections explicitly.

## Quick checklist

Copy and track in the reply:

```text
[ ] 1. listJavaApps
[ ] 2. Target pid agreed (interactive if needed)
[ ] 3. inspectJvmRuntime(pid)
[ ] 3b. Step-3 scope gate: AskQuestion (or chat) — user chose snapshot-only and/or privileged scopes; no silent quick pass
[ ] 4. collectMemoryGcEvidence — flags match gate; token/path if privileged
[ ] 5. generateTuningAdvice — codeContextSummary + goals; collect* flags mirror `collectMemoryGcEvidence` (same turn, same consent)
[ ] 6. Present formattedSummary as Markdown in the message (no outer code fence); short preamble optional
```
