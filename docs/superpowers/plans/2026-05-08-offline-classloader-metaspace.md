# Offline Classloader Metaspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse offline `metaspaceEvidence` classloader statistics and use them to report suspected classloader retention or churn.

**Architecture:** Add compact classloader/metaspace evidence records to the runtime package, parse offline text in `OfflineEvidenceAssembler`, attach the summary to `MemoryGcEvidencePack`, and add a focused diagnosis rule after `MetaspacePressureRule`. Do not add live JVM commands or new MCP parameters.

**Tech Stack:** Java 17 records, JUnit 5, AssertJ, Maven, existing offline evidence and rule engine.

---

## File Structure

- Create `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ClassloaderMetaspaceEntry.java`.
- Create `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ClassloaderMetaspaceSummary.java`.
- Create `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ClassloaderMetaspaceParser.java`.
- Modify `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`.
- Modify `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineEvidenceAssembler.java`.
- Create `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/ClassloaderMetaspaceRule.java`.
- Modify `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java`.
- Update docs: `README.md`, `docs/offline-mode-spec.md`, `.cursor/skills/java-tuning-agent-workflow/SKILL.md`, `agent-pack/java-tuning-agent/skills/java-tuning-agent-workflow/SKILL.md`, and adapter copies.
- Add tests for parser, offline assembly, rule, and engine integration.

## Task 1: Parser And Summary Model

**Files:**
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ClassloaderMetaspaceParserTest.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ClassloaderMetaspaceEntry.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ClassloaderMetaspaceSummary.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/ClassloaderMetaspaceParser.java`

- [x] **Step 1: Write failing parser tests**

Create tests for:

```java
new ClassloaderMetaspaceParser().parse("""
ClassLoader Parent CLD* Classes ChunkSz BlockSz Type
0x1 0x0 0x2 1,200 65,536 32,768 com.example.ProxyClassLoader
0x3 0x0 0x4 25 4096 2048 jdk.internal.loader.ClassLoaders$AppClassLoader
""")
```

Expected: two entries, top classloader `com.example.ProxyClassLoader`, total classes `1225`, total bytes `104448`.

Create a `jmap -clstats` style test:

```java
ClassLoader Parent Alive Classes Bytes Type
0x10 0x0 true 850 49,152 com.example.PluginClassLoader
```

Expected: alive `true`, class count `850`, bytes `49152`.

Create an unparseable text test expecting no entries and a warning containing `Unable to parse classloader metaspace evidence`.

- [x] **Step 2: Run parser tests to verify failure**

Run:

```bash
mvn -q -Dtest=ClassloaderMetaspaceParserTest test
```

Expected: compile failure because parser/model classes do not exist.

- [x] **Step 3: Implement records and parser**

Implement:

```java
public record ClassloaderMetaspaceEntry(String classLoaderName, String parentClassLoaderName, Long classCount,
		Long bytes, Boolean alive, String rawLine) {
}
```

```java
public record ClassloaderMetaspaceSummary(List<ClassloaderMetaspaceEntry> entries, long totalClassCount,
		long totalBytes, List<String> warnings) {
}
```

Parser should skip headers/separators, parse VM rows with `loader parent cld classes chunk block type`, parse clstats rows with `loader parent alive classes bytes type`, and add a warning for non-blank text with no rows.

- [x] **Step 4: Run parser tests**

Run:

```bash
mvn -q -Dtest=ClassloaderMetaspaceParserTest test
```

Expected: PASS.

## Task 2: Attach Offline Metaspace Evidence

**Files:**
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/MemoryGcEvidencePack.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineEvidenceAssembler.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineEvidenceAssemblerTest.java`

- [x] **Step 1: Write failing offline assembler tests**

Add one test where `metaspaceEvidence.inlineText` contains VM.classloader stats and assert `pack.classloaderMetaspaceSummary()` is not null.

Add one regression test with missing `metaspaceEvidence` and assert `pack.classloaderMetaspaceSummary()` is null and `pack.missingData()` does not contain `metaspaceEvidence`.

- [x] **Step 2: Run offline tests to verify failure**

Run:

```bash
mvn -q -Dtest=OfflineEvidenceAssemblerTest test
```

Expected: compile failure because `classloaderMetaspaceSummary()` does not exist.

- [x] **Step 3: Extend `MemoryGcEvidencePack`**

Add nullable `ClassloaderMetaspaceSummary classloaderMetaspaceSummary` to the record, preserve existing constructors by passing `null`, add `withClassloaderMetaspaceSummary(...)`, and update all internal copy methods to preserve the new field.

- [x] **Step 4: Load and attach `metaspaceEvidence`**

In `OfflineEvidenceAssembler`, instantiate `ClassloaderMetaspaceParser`, load `draft.metaspaceEvidence()`, parse non-blank text, attach parsed summaries, add warnings, and add `metaspaceEvidence` to missing data only for file load failures or non-blank unparseable text.

- [x] **Step 5: Run offline tests**

Run:

```bash
mvn -q -Dtest=OfflineEvidenceAssemblerTest test
```

Expected: PASS.

## Task 3: Diagnosis Rule

**Files:**
- Create: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/ClassloaderMetaspaceRuleTest.java`
- Create: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/ClassloaderMetaspaceRule.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngine.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/MemoryGcDiagnosisEngineTest.java`

- [x] **Step 1: Write failing rule tests**

Create tests proving:

- Many `ProxyClassLoader` rows produce finding title `Suspected classloader retention or churn`.
- Classloader evidence plus NMT `Class` committed growth includes `nmtClassGrowthCorroborated=true` in evidence.

- [x] **Step 2: Run rule tests to verify failure**

Run:

```bash
mvn -q -Dtest=ClassloaderMetaspaceRuleTest test
```

Expected: compile failure because the rule does not exist.

- [x] **Step 3: Implement `ClassloaderMetaspaceRule`**

Trigger when top class count is at least `500`, top bytes at least `32MB`, repeated generated/proxy pattern count is at least `3`, or classloader evidence is corroborated by NMT `Class` committed growth of at least `32MB`.

Recommendation action should be `Inspect classloader retention and generated class lifecycle`.

- [x] **Step 4: Register rule in engine**

Add `new ClassloaderMetaspaceRule()` after `new MetaspacePressureRule()`.

- [x] **Step 5: Run rule and engine tests**

Run:

```bash
mvn -q -Dtest=ClassloaderMetaspaceRuleTest,MemoryGcDiagnosisEngineTest test
```

Expected: PASS.

## Task 4: Documentation And Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/offline-mode-spec.md`
- Modify: `.cursor/skills/java-tuning-agent-workflow/SKILL.md`
- Modify: `agent-pack/java-tuning-agent/skills/java-tuning-agent-workflow/SKILL.md`
- Modify: `agent-pack/java-tuning-agent/adapters/cursor/skills/java-tuning-agent-workflow/SKILL.md`

- [x] **Step 1: Update docs wording**

Replace wording that says `metaspaceEvidence` is future/supporting only with wording that it now accepts classloader stats for offline classloader/metaspace attribution.

- [x] **Step 2: Run focused verification**

Run:

```bash
mvn -q -Dtest=ClassloaderMetaspaceParserTest,OfflineEvidenceAssemblerTest,ClassloaderMetaspaceRuleTest,MemoryGcDiagnosisEngineTest test
```

Expected: PASS.

- [x] **Step 3: Run full verification**

Run:

```bash
mvn -q test
```

Expected: exit code 0.

- [x] **Step 4: Commit**

Run:

```bash
git add src/main/java src/test/java README.md docs/offline-mode-spec.md .cursor/skills/java-tuning-agent-workflow/SKILL.md agent-pack/java-tuning-agent/skills/java-tuning-agent-workflow/SKILL.md agent-pack/java-tuning-agent/adapters/cursor/skills/java-tuning-agent-workflow/SKILL.md docs/superpowers/plans/2026-05-08-offline-classloader-metaspace.md
git commit -m "feat: add offline classloader metaspace evidence"
```

Expected: commit succeeds after tests pass.
