# Deep memory leak analysis — implementation plan

> **For agentic workers:** Use checkbox steps for tracking. Implement in order; each phase should leave tests green.

**Goal:** Move beyond “histogram top-N” heuristics so `byte[]`-heavy and framework-heavy heaps still surface **actionable** signals and **application-scoped** hotspots, then extend toward **heap dump / dominator-style** workflows.

**Architecture:** Keep **readonly collectors** and **evidence packs** as the single source of truth; add small **pure-Java analyzers** (name normalization, ranked selection) and optional **high-cost** collection behind existing **confirmationToken** policy. Later phases may add **external dump file** ingestion or `jcmd GC.heap_dump` orchestration without embedding a full MAT stack in v1.

**Tech stack:** Java 17, Spring Boot 3.5, existing `jcmd` / `jstat` collectors, JUnit 5.

---

## Phase 1 — Histogram intelligence & hotspot ranking (this PR)

**Files**

- Create: `src/main/java/.../runtime/HistogramClassNames.java` — strip ` (module@ver)` suffix; detect primitive array descriptors (`[B`, `[I`, …).
- Modify: `src/main/java/.../source/LocalSourceHotspotFinder.java` — two-pass ranking: **candidate packages first**, then fill with non-framework types; normalize names for `.java` lookup.
- Modify: `src/main/java/.../agent/JavaTuningWorkflowService.java` — pass `candidatePackages` into finder.
- Modify: `src/main/java/.../advice/SuspectedLeakRule.java` — when dominant type is a **primitive array**, add **heap dump / dominator / alloc profiling** next steps (not only “compare histograms”).
- Test: `src/test/java/.../source/LocalSourceHotspotFinderTest.java` — Spring-vs-demo ordering; module suffix stripping.
- Test: extend `JavaTuningWorkflowServiceTest` if needed for `candidatePackages`.

- [x] **Step 1:** Add `HistogramClassNames` with unit-level usage covered by finder tests.
- [x] **Step 2:** Implement two-pass hotspot selection + framework deprioritization list (`org.springframework.`, `ch.qos.logback.`, `org.apache.catalina.`, `org.apache.tomcat.`, `io.netty.` — extend as needed).
- [x] **Step 3:** Wire `JavaTuningWorkflowService` to pass `CodeContextSummary.candidatePackages()`.
- [x] **Step 4:** Refine `SuspectedLeakRule` next steps for primitive-array dominance.
- [x] **Step 5:** Run `mvn -q test`.

**Success criteria:** For a histogram where Spring types outweigh demo types in bytes, **first hotspots** still include classes under `candidatePackages` when given `sourceRoots` + packages.

---

## Phase 2 — Evidence: optional heap dump path (done)

**Scope:** `MemoryGcEvidenceRequest` / policy: `includeHeapDump` + `heapDumpOutputPath` + `confirmationToken`; run `jcmd <pid> GC.heap_dump <file>`; return path + warnings (disk full, attach failure).

**Out of scope for v1:** Parsing HPROF inside the agent (large dependency / memory).

**Success criteria:** Agent produces a **portable dump file** and structured **next steps** (“open in MAT → dominator tree for `byte[]`”).

---

## Phase 3 — Dump analysis adapter (planned)

**Options (pick one later):**

1. Thin CLI wrapper around **Eclipse MAT** / **jhat**-successor (document OS command, parse minimal summary if feasible).
2. Add **MAT parser** or **hprof** library dependency and implement **dominator top-K for `byte[]`** (high effort, heavy JARs).
3. Integrate **JFR** recording file (`jcmd JFR.*`) for allocation events — different signal, often lighter than full heap parse.

**Success criteria:** Structured report section **“retainers of byte[]”** or **top allocation sites** with confidence labels.

---

## Phase 4 — Docs & MCP

- Update `README.md` / design spec sync: when to use histogram vs heap dump vs JFR.
- Regenerate MCP tool descriptors if new parameters are added (`collectHeapDump`, path).

---

## Notes

- **Primitive arrays** (`[B`) will never map to a `.java` file; guidance must stay in **findings / next steps**, not fake file hints.
- **Framework blocklist** is heuristic; false negatives possible — tune from real dumps.
