# Metaspace P1 Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make metaspace diagnosis capacity-aware and trend-aware by carrying metaspace committed/reserved, `jstat` `M`/`CCS`, and NMT `Class` growth into `MetaspacePressureRule`.

**Architecture:** Extend the existing compact evidence records with nullable fields and compatibility constructors, then update parsers and the metaspace rule to consume them. Keep all evidence in the current `JvmMemorySnapshot`, `JvmGcSnapshot`, `NativeMemorySummary`, and `MemoryGcEvidencePack` flow; do not add new MCP parameters or classloader-stat evidence in this P1 slice.

**Tech Stack:** Java 17 records, JUnit 5, AssertJ, Maven, existing rule-based diagnosis engine.

---

## File Structure

- Modify `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmMemorySnapshot.java`: add metaspace committed/reserved nullable fields and an 8-argument compatibility constructor.
- Modify `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcHeapInfoParser.java`: populate metaspace committed/reserved from `GC.heap_info`.
- Modify `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmGcSnapshot.java`: add `metaspaceUtilPercent` and `compressedClassSpaceUtilPercent` nullable fields plus a 6-argument compatibility constructor.
- Modify `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JstatGcUtilParser.java`: parse `M` and `CCS` from normal tabular and compact labeled `jstat -gcutil` output.
- Modify `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java`: preserve new memory and GC fields when creating enriched snapshots.
- Modify `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingResultParser.java`: read optional new JSON fields without breaking old samples.
- Modify `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineJvmSnapshotAssembler.java`: preserve new parsed fields in offline snapshots and fallback values.
- Modify `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MetaspacePressureRule.java`: evaluate capacity, utilization, and NMT class growth.
- Modify tests:
  - `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcHeapInfoParserTest.java`
  - `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JstatGcUtilParserTest.java`
  - `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/MetaspacePressureRuleTest.java`
  - `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineJvmSnapshotAssemblerTest.java`
  - `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorTest.java`

## Task 1: Carry Metaspace Committed And Reserved

**Files:**
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmMemorySnapshot.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcHeapInfoParser.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcHeapInfoParserTest.java`

- [ ] **Step 1: Write the failing parser test**

Add assertions to `shouldParseG1HeapInfo`:

```java
assertThat(parsed.metaspaceCommittedBytes()).isEqualTo(9216L * 1024L);
assertThat(parsed.metaspaceReservedBytes()).isEqualTo(65536L * 1024L);
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
mvn -q -Dtest=GcHeapInfoParserTest test
```

Expected: compile failure or assertion failure because `metaspaceCommittedBytes()` and `metaspaceReservedBytes()` do not exist yet.

- [ ] **Step 3: Extend `JvmMemorySnapshot` with nullable fields and compatibility constructor**

Replace the record declaration with:

```java
public record JvmMemorySnapshot(long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes,
		Long oldGenUsedBytes, Long oldGenCommittedBytes, Long metaspaceUsedBytes, Long metaspaceCommittedBytes,
		Long metaspaceReservedBytes, Long xmsBytes, Long xmxBytes) {

	public JvmMemorySnapshot(long heapUsedBytes, long heapCommittedBytes, long heapMaxBytes, Long oldGenUsedBytes,
			Long oldGenCommittedBytes, Long metaspaceUsedBytes, Long xmsBytes, Long xmxBytes) {
		this(heapUsedBytes, heapCommittedBytes, heapMaxBytes, oldGenUsedBytes, oldGenCommittedBytes,
				metaspaceUsedBytes, null, null, xmsBytes, xmxBytes);
	}

}
```

- [ ] **Step 4: Populate committed/reserved in `GcHeapInfoParser`**

Change local variables:

```java
Long metaspaceUsedBytes = null;
Long metaspaceCommittedBytes = null;
Long metaspaceReservedBytes = null;
```

Inside the existing metaspace match block:

```java
metaspaceUsedBytes = toBytes(metaspaceMatcher.group(1));
metaspaceCommittedBytes = toBytes(metaspaceMatcher.group(2));
metaspaceReservedBytes = toBytes(metaspaceMatcher.group(3));
```

Return the expanded snapshot:

```java
return new JvmMemorySnapshot(heapUsedBytes, heapCommittedBytes, heapMaxBytes, oldGenUsedBytes,
		oldGenCommittedBytes, metaspaceUsedBytes, metaspaceCommittedBytes, metaspaceReservedBytes, xmsBytes,
		xmxBytes);
```

For the blank-output return, use:

```java
return new JvmMemorySnapshot(0L, 0L, 0L, null, null, null, null, null, null, null);
```

- [ ] **Step 5: Run the focused parser test**

Run:

```bash
mvn -q -Dtest=GcHeapInfoParserTest test
```

Expected: PASS.

## Task 2: Parse `jstat -gcutil` M And CCS Utilization

**Files:**
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmGcSnapshot.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JstatGcUtilParser.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JstatGcUtilParserTest.java`

- [ ] **Step 1: Write failing tests**

In `shouldParseGcUtilCounters`, add:

```java
assertThat(parsed.metaspaceUtilPercent()).isEqualTo(92.21d);
assertThat(parsed.compressedClassSpaceUtilPercent()).isEqualTo(88.12d);
```

Add a compact-output test:

```java
@Test
void shouldParseCompactMetaspaceAndCompressedClassSpaceUtilization() {
	String output = """
			targetPid: 1961
			jstat -gcutil: O 31.25 M 93.50 CCS 87.75 YGC 8 YGCT 0.015 FGC 0 FGCT 0.000
			""";

	JvmGcSnapshot parsed = new JstatGcUtilParser().parse(output);

	assertThat(parsed.oldUsagePercent()).isEqualTo(31.25d);
	assertThat(parsed.metaspaceUtilPercent()).isEqualTo(93.50d);
	assertThat(parsed.compressedClassSpaceUtilPercent()).isEqualTo(87.75d);
}
```

- [ ] **Step 2: Run focused test to verify it fails**

Run:

```bash
mvn -q -Dtest=JstatGcUtilParserTest test
```

Expected: compile failure because the new accessor methods do not exist.

- [ ] **Step 3: Extend `JvmGcSnapshot` with nullable utilization fields**

Replace the record with:

```java
public record JvmGcSnapshot(String collector, long youngGcCount, long youngGcTimeMs, long fullGcCount,
		long fullGcTimeMs, Double oldUsagePercent, Double metaspaceUtilPercent,
		Double compressedClassSpaceUtilPercent) {

	public JvmGcSnapshot(String collector, long youngGcCount, long youngGcTimeMs, long fullGcCount,
			long fullGcTimeMs, Double oldUsagePercent) {
		this(collector, youngGcCount, youngGcTimeMs, fullGcCount, fullGcTimeMs, oldUsagePercent, null, null);
	}

}
```

- [ ] **Step 4: Update `JstatGcUtilParser` labeled parsing**

Change `LABELED_VALUE` to include `M` and `CCS`:

```java
private static final Pattern LABELED_VALUE = Pattern
	.compile("(?i)\\b(YGC|YGCT|FGC|FGCT|O|M|CCS)\\b\\s+(-|\\d+(?:\\.\\d+)?)");
```

Add local variables in `parseLabeledGcUtil`:

```java
Double metaspaceUtilPercent = null;
Double compressedClassSpaceUtilPercent = null;
```

Add switch cases:

```java
case "M" -> metaspaceUtilPercent = parseDoubleOrNull(value);
case "CCS" -> compressedClassSpaceUtilPercent = parseDoubleOrNull(value);
```

Return:

```java
return new JvmGcSnapshot("unknown", youngGcCount, youngGcTimeMs, fullGcCount, fullGcTimeMs,
		oldUsagePercent, metaspaceUtilPercent, compressedClassSpaceUtilPercent);
```

- [ ] **Step 5: Update tabular parsing**

In `parse`, after `oldUsagePercent`:

```java
Double metaspaceUtilPercent = values.length > 4 ? parseDoubleOrNull(values[4]) : null;
Double compressedClassSpaceUtilPercent = values.length > 5 ? parseDoubleOrNull(values[5]) : null;
return new JvmGcSnapshot("unknown", youngGcCount, youngGcTimeMs, fullGcCount, fullGcTimeMs,
		oldUsagePercent, metaspaceUtilPercent, compressedClassSpaceUtilPercent);
```

Leave blank and malformed fallback constructors on the 6-argument constructor.

- [ ] **Step 6: Run focused parser tests**

Run:

```bash
mvn -q -Dtest=JstatGcUtilParserTest test
```

Expected: PASS.

## Task 3: Preserve New Fields In Online And Offline Assembly

**Files:**
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineJvmSnapshotAssembler.java`
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingResultParser.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorTest.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineJvmSnapshotAssemblerTest.java`

- [ ] **Step 1: Write failing online preservation assertions**

In `SafeJvmRuntimeCollectorTest.shouldCollectHeapDumpWhenConfirmed`, after snapshot assertions, add:

```java
assertThat(pack.snapshot().memory().metaspaceCommittedBytes()).isEqualTo(9216L * 1024L);
assertThat(pack.snapshot().memory().metaspaceReservedBytes()).isEqualTo(65536L * 1024L);
assertThat(pack.snapshot().gc().metaspaceUtilPercent()).isEqualTo(92.21d);
assertThat(pack.snapshot().gc().compressedClassSpaceUtilPercent()).isEqualTo(88.12d);
```

- [ ] **Step 2: Write failing offline preservation assertions**

In `OfflineJvmSnapshotAssemblerTest.assemblesPidHeapAndGcFromOfflineTexts`, add a `Metaspace` line to `runtime`:

```text
Metaspace       used 8192K, committed 9216K, reserved 65536K
```

Then add assertions:

```java
assertThat(snap.memory().metaspaceUsedBytes()).isEqualTo(8192L * 1024L);
assertThat(snap.memory().metaspaceCommittedBytes()).isEqualTo(9216L * 1024L);
assertThat(snap.memory().metaspaceReservedBytes()).isEqualTo(65536L * 1024L);
assertThat(snap.gc().metaspaceUtilPercent()).isEqualTo(92.21d);
assertThat(snap.gc().compressedClassSpaceUtilPercent()).isEqualTo(88.12d);
```

- [ ] **Step 3: Run focused tests to verify failure**

Run:

```bash
mvn -q -Dtest=SafeJvmRuntimeCollectorTest,OfflineJvmSnapshotAssemblerTest test
```

Expected: FAIL because collector/assembler re-wrap snapshots without preserving new fields.

- [ ] **Step 4: Preserve memory fields in `SafeJvmRuntimeCollector.collect`**

Change `structuredMemory` construction to:

```java
JvmMemorySnapshot structuredMemory = new JvmMemorySnapshot(memory.heapUsedBytes(), memory.heapCommittedBytes(),
		heapMaxBytes, memory.oldGenUsedBytes(), memory.oldGenCommittedBytes(), memory.metaspaceUsedBytes(),
		memory.metaspaceCommittedBytes(), memory.metaspaceReservedBytes(), xmsBytes, xmxBytes);
```

Change GC re-wrap to:

```java
gc = new JvmGcSnapshot(collector, gc.youngGcCount(), gc.youngGcTimeMs(), gc.fullGcCount(),
		gc.fullGcTimeMs(), gc.oldUsagePercent(), gc.metaspaceUtilPercent(),
		gc.compressedClassSpaceUtilPercent());
```

- [ ] **Step 5: Preserve fields in `OfflineJvmSnapshotAssembler`**

Change `structuredMemory` construction to:

```java
JvmMemorySnapshot structuredMemory = new JvmMemorySnapshot(memory.heapUsedBytes(), memory.heapCommittedBytes(),
		heapMaxBytes, memory.oldGenUsedBytes(), memory.oldGenCommittedBytes(), memory.metaspaceUsedBytes(),
		memory.metaspaceCommittedBytes(), memory.metaspaceReservedBytes(), xmsBytes, xmxBytes);
```

Change GC construction to:

```java
JvmGcSnapshot gc = new JvmGcSnapshot(collector, gcParsed.youngGcCount(), gcParsed.youngGcTimeMs(),
		gcParsed.fullGcCount(), gcParsed.fullGcTimeMs(), gcParsed.oldUsagePercent(),
		gcParsed.metaspaceUtilPercent(), gcParsed.compressedClassSpaceUtilPercent());
```

Change fallback memory return to:

```java
return new JvmMemorySnapshot(0L, 0L, 0L, null, null, null, null, null, null, null);
```

- [ ] **Step 6: Read optional fields in `RepeatedSamplingResultParser`**

Update memory parsing to pass:

```java
nullableLong(node.path("metaspaceCommittedBytes")),
nullableLong(node.path("metaspaceReservedBytes")),
```

between `metaspaceUsedBytes` and `xmsBytes`.

Update GC parsing to pass:

```java
nullableDouble(node.path("metaspaceUtilPercent")),
nullableDouble(node.path("compressedClassSpaceUtilPercent"))
```

after `oldUsagePercent`.

If no helper exists, add:

```java
private Double nullableDouble(JsonNode node) {
	return node == null || node.isMissingNode() || node.isNull() ? null : node.asDouble();
}
```

- [ ] **Step 7: Run focused preservation tests**

Run:

```bash
mvn -q -Dtest=SafeJvmRuntimeCollectorTest,OfflineJvmSnapshotAssemblerTest test
```

Expected: PASS.

## Task 4: Use Utilization And NMT Class Growth In Metaspace Rule

**Files:**
- Modify: `src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MetaspacePressureRule.java`
- Test: `src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/MetaspacePressureRuleTest.java`

- [ ] **Step 1: Write failing rule tests**

Add imports if needed:

```java
import java.util.Map;
import com.alibaba.cloud.ai.examples.javatuning.runtime.NativeMemorySummary;
```

Add test for NMT Class growth:

```java
@Test
void shouldReportMetaspacePressureWhenNmtClassCommittedGrows() {
	NativeMemorySummary nativeSummary = new NativeMemorySummary(1024L * 1024L * 1024L,
			512L * 1024L * 1024L, null, null, 128L * 1024L * 1024L, 96L * 1024L * 1024L,
			Map.of("class", new NativeMemorySummary.CategoryUsage(128L * 1024L * 1024L, 96L * 1024L * 1024L)),
			Map.of("class", new NativeMemorySummary.CategoryGrowth(64L * 1024L * 1024L, 48L * 1024L * 1024L)),
			List.of());
	MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshot(96L * 1024L * 1024L, 1_000L), null,
			null, List.of(), List.of(), null, null).withNativeMemorySummary(nativeSummary);
	DiagnosisScratch scratch = new DiagnosisScratch();

	new MetaspacePressureRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

	assertThat(scratch.findings()).extracting(TuningFinding::title)
		.contains("Metaspace or class metadata pressure");
	assertThat(scratch.findings().get(0).evidence()).contains("classCommittedGrowthMb=48");
}
```

Add test for `M` / `CCS` utilization:

```java
@Test
void shouldReportMetaspacePressureWhenJstatMetaspaceUtilizationIsHigh() {
	MemoryGcEvidencePack evidence = new MemoryGcEvidencePack(snapshotWithGcUtil(96L * 1024L * 1024L,
			120L * 1024L * 1024L, 256L * 1024L * 1024L, 1_000L, 96.25d, 91.50d), null, null,
			List.of("nativeMemorySummary"), List.of(), null, null);
	DiagnosisScratch scratch = new DiagnosisScratch();

	new MetaspacePressureRule().evaluate(evidence, CodeContextSummary.empty(), scratch);

	assertThat(scratch.findings()).extracting(TuningFinding::title)
		.contains("Metaspace or class metadata pressure");
	assertThat(scratch.findings().get(0).evidence()).contains("metaspaceUtilPercent=96.25")
		.contains("compressedClassSpaceUtilPercent=91.5");
}
```

Add helper:

```java
private static JvmRuntimeSnapshot snapshotWithGcUtil(long metaspaceUsedBytes, long metaspaceCommittedBytes,
		long metaspaceReservedBytes, long loadedClassCount, Double metaspaceUtilPercent,
		Double compressedClassSpaceUtilPercent) {
	return new JvmRuntimeSnapshot(1961L,
			new JvmMemorySnapshot(128L * 1024L * 1024L, 256L * 1024L * 1024L, 1024L * 1024L * 1024L, null,
					null, metaspaceUsedBytes, metaspaceCommittedBytes, metaspaceReservedBytes, null, null),
			new JvmGcSnapshot("G1", 0L, 0L, 0L, 0L, 20.0d, metaspaceUtilPercent,
					compressedClassSpaceUtilPercent),
			List.of(), "", null, loadedClassCount, new JvmCollectionMetadata(List.of(), 0L, 0L, false),
			List.of());
}
```

- [ ] **Step 2: Run focused test to verify failure**

Run:

```bash
mvn -q -Dtest=MetaspacePressureRuleTest test
```

Expected: FAIL because the rule ignores NMT class growth and utilization.

- [ ] **Step 3: Implement growth and utilization helpers in `MetaspacePressureRule`**

Add thresholds:

```java
private static final long LARGE_BYTES = 512L * 1024L * 1024L;
private static final long SIGNIFICANT_CLASS_GROWTH_BYTES = 32L * 1024L * 1024L;
private static final double HIGH_UTILIZATION_PERCENT = 90.0d;
```

Read new values in `evaluate`:

```java
Long metaspaceCommittedValue = evidence.snapshot().memory() != null
		? evidence.snapshot().memory().metaspaceCommittedBytes() : null;
Long metaspaceReservedValue = evidence.snapshot().memory() != null
		? evidence.snapshot().memory().metaspaceReservedBytes() : null;
long metaspaceCommitted = metaspaceCommittedValue == null ? 0L : metaspaceCommittedValue;
long metaspaceReserved = metaspaceReservedValue == null ? 0L : metaspaceReservedValue;
Long classCommittedGrowth = classCommittedGrowth(summary);
Double metaspaceUtilPercent = evidence.snapshot().gc() != null ? evidence.snapshot().gc().metaspaceUtilPercent()
		: null;
Double compressedClassSpaceUtilPercent = evidence.snapshot().gc() != null
		? evidence.snapshot().gc().compressedClassSpaceUtilPercent() : null;
```

Use these booleans:

```java
boolean largeMetaspace = metaspaceUsed >= LARGE_BYTES || metaspaceCommitted >= LARGE_BYTES;
boolean classPressure = classCommitted != null && classCommitted >= LARGE_BYTES;
boolean classGrowthPressure = classCommittedGrowth != null
		&& classCommittedGrowth >= SIGNIFICANT_CLASS_GROWTH_BYTES;
boolean metaspaceUtilPressure = metaspaceUtilPercent != null && metaspaceUtilPercent >= HIGH_UTILIZATION_PERCENT;
boolean classSpaceUtilPressure = compressedClassSpaceUtilPercent != null
		&& compressedClassSpaceUtilPercent >= HIGH_UTILIZATION_PERCENT;
```

Return only when none are true:

```java
if (!largeMetaspace && !classPressure && !classGrowthPressure && !metaspaceUtilPressure
		&& !classSpaceUtilPressure) {
	return;
}
```

Add helper:

```java
private static Long classCommittedGrowth(NativeMemorySummary summary) {
	if (summary == null || summary.categoryGrowth().isEmpty()) {
		return null;
	}
	NativeMemorySummary.CategoryGrowth growth = summary.categoryGrowth().get("class");
	return growth != null ? growth.committedDeltaBytes() : null;
}
```

- [ ] **Step 4: Build richer evidence string**

Add helper:

```java
private String evidenceText(long metaspaceUsed, long metaspaceCommitted, long metaspaceReserved,
		Long classCommitted, Long classCommittedGrowth, Double metaspaceUtilPercent,
		Double compressedClassSpaceUtilPercent) {
	return "metaspaceUsedMb=" + mb(metaspaceUsed) + ", metaspaceCommittedMb=" + mb(metaspaceCommitted)
			+ ", metaspaceReservedMb=" + mb(metaspaceReserved) + ", classCommittedMb="
			+ mb(classCommitted == null ? 0L : classCommitted) + ", classCommittedGrowthMb="
			+ mb(classCommittedGrowth == null ? 0L : classCommittedGrowth) + ", metaspaceUtilPercent="
			+ percentText(metaspaceUtilPercent) + ", compressedClassSpaceUtilPercent="
			+ percentText(compressedClassSpaceUtilPercent);
}

private String percentText(Double value) {
	return value == null ? "unknown" : Double.toString(value);
}
```

Use it in the `TuningFinding`.

- [ ] **Step 5: Update missing NMT next step text**

Change the missing-NMT next step string to:

```java
"Collect NMT Class category with -XX:NativeMemoryTracking=summary and jcmd " + evidence.snapshot().pid()
		+ " VM.native_memory summary to distinguish class metadata pressure from heap retention."
```

Update existing tests to expect the command-oriented string.

- [ ] **Step 6: Run focused rule tests**

Run:

```bash
mvn -q -Dtest=MetaspacePressureRuleTest test
```

Expected: PASS.

## Task 5: Focused Integration And Regression

**Files:**
- Modify only if tests expose propagation gaps.
- Test: focused parser, collector, assembler, and rule tests.

- [ ] **Step 1: Run focused suite**

Run:

```bash
mvn -q -Dtest=GcHeapInfoParserTest,JstatGcUtilParserTest,MetaspacePressureRuleTest,NativeMemorySummaryParserTest,SafeJvmRuntimeCollectorTest,OfflineJvmSnapshotAssemblerTest test
```

Expected: PASS.

- [ ] **Step 2: Fix constructor or propagation compile failures**

If compile failures appear for direct record constructors, prefer preserving compatibility constructors over editing every unrelated test. Only update direct construction sites when they need to assert the new fields.

- [ ] **Step 3: Run full test suite**

Run:

```bash
mvn -q test
```

Expected: PASS.

- [ ] **Step 4: Review diff**

Run:

```bash
git diff -- src/main/java src/test/java docs/superpowers/plans/2026-05-08-metaspace-p1-enhancement.md
```

Expected: diff is limited to the P1 metaspace fields, parsers, rule behavior, tests, and this implementation plan.

- [ ] **Step 5: Commit implementation**

Run:

```bash
git add src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmMemorySnapshot.java \
	src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcHeapInfoParser.java \
	src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JvmGcSnapshot.java \
	src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JstatGcUtilParser.java \
	src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollector.java \
	src/main/java/com/alibaba/cloud/ai/examples/javatuning/runtime/RepeatedSamplingResultParser.java \
	src/main/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineJvmSnapshotAssembler.java \
	src/main/java/com/alibaba/cloud/ai/examples/javatuning/advice/MetaspacePressureRule.java \
	src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/GcHeapInfoParserTest.java \
	src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/JstatGcUtilParserTest.java \
	src/test/java/com/alibaba/cloud/ai/examples/javatuning/advice/MetaspacePressureRuleTest.java \
	src/test/java/com/alibaba/cloud/ai/examples/javatuning/offline/OfflineJvmSnapshotAssemblerTest.java \
	src/test/java/com/alibaba/cloud/ai/examples/javatuning/runtime/SafeJvmRuntimeCollectorTest.java \
	docs/superpowers/plans/2026-05-08-metaspace-p1-enhancement.md
git commit -m "feat: strengthen metaspace p1 diagnosis"
```

Expected: commit succeeds after focused and full tests pass.
