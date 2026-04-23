package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;

final class TestHeapDumpSupport {

	private static final int FOREIGN_ARRAY_COUNT = 8;

	private static final int FOREIGN_ARRAY_LENGTH = 50_000;

	private static final int PREFERRED_ARRAY_LENGTH = 48_000;

	private static final int SECONDARY_ARRAY_GROUP_COUNT = 4;

	private static final int SECONDARY_ARRAY_LENGTH = 49_000;

	private static final int PREFERRED_NODE_COUNT = 12;

	private static final int DOMINATING_OWNER_PAYLOAD_COUNT = 6;

	private static final int DOMINATING_OWNER_PAYLOAD_LENGTH = 160 * 1024;

	private static final int DOMINATING_OWNER_LEAF_PAYLOAD_LENGTH = 24 * 1024;

	private static final int SHARED_OWNER_PAYLOAD_LENGTH = 192 * 1024;

	private static final int BRANCH_OVERHEAD_PAYLOAD_LENGTH = 32 * 1024;

	private static final AtomicInteger SCENARIO_COUNTER = new AtomicInteger();

	private TestHeapDumpSupport() {
	}

	static Path dumpStaticRetainedBytesHeap(Path dir) throws Exception {
		Path heap = prepareHeapPath(dir, "retained-bytes");
		runDumpFixtureProcess("retained-bytes", heap);
		awaitDumpReady(heap);
		return heap;
	}

	static Path dumpFocusPackagePreferenceHeap(Path dir) throws Exception {
		Path heap = prepareHeapPath(dir, "focus-packages");
		runDumpFixtureProcess("focus-packages", heap);
		awaitDumpReady(heap);
		return heap;
	}

	static Path dumpDominatingStaticOwnerHeap(Path dir) throws Exception {
		Path heap = prepareHeapPath(dir, "dominating-static-owner");
		runDumpFixtureProcess("dominating-static-owner", heap);
		awaitDumpReady(heap);
		return heap;
	}

	static Object createRetainedBytesFixture() {
		return new RetainedGraph(new RetainedBucket("alpha", new byte[256 * 1024]),
				new RetainedBucket("beta", new byte[192 * 1024]));
	}

	static Object createFocusPackageFixture() {
		String[][] foreignArrays = new String[FOREIGN_ARRAY_COUNT][];
		for (int index = 0; index < FOREIGN_ARRAY_COUNT; index++) {
			foreignArrays[index] = new String[FOREIGN_ARRAY_LENGTH];
		}
		SecondaryNode[][] secondaryArrays = new SecondaryNode[SECONDARY_ARRAY_GROUP_COUNT][];
		for (int index = 0; index < SECONDARY_ARRAY_GROUP_COUNT; index++) {
			secondaryArrays[index] = new SecondaryNode[SECONDARY_ARRAY_LENGTH];
		}
		PreferredNode[] preferredArray = new PreferredNode[PREFERRED_ARRAY_LENGTH];
		for (int index = 0; index < PREFERRED_NODE_COUNT; index++) {
			preferredArray[index] = new PreferredNode("preferred-" + index, new byte[64 * 1024],
					new PreferredLeaf(new byte[32 * 1024]));
		}
		return new PackagePreferenceGraph(foreignArrays, secondaryArrays, preferredArray);
	}

	static Object createDominatingStaticOwnerFixture() {
		DominatedPayload[] payloads = new DominatedPayload[DOMINATING_OWNER_PAYLOAD_COUNT];
		for (int index = 0; index < DOMINATING_OWNER_PAYLOAD_COUNT; index++) {
			payloads[index] = new DominatedPayload("dominated-" + index, new byte[DOMINATING_OWNER_PAYLOAD_LENGTH],
					new DominatedLeaf(new byte[DOMINATING_OWNER_LEAF_PAYLOAD_LENGTH]));
		}

		SharedPayload sharedPayload = new SharedPayload("shared-across-branches", new byte[SHARED_OWNER_PAYLOAD_LENGTH],
				new DominatedLeaf(new byte[DOMINATING_OWNER_LEAF_PAYLOAD_LENGTH]));
		DominatingStaticOwner owner = new DominatingStaticOwner("owner", payloads, sharedPayload,
				new byte[BRANCH_OVERHEAD_PAYLOAD_LENGTH]);
		SharedReferenceBranch branch = new SharedReferenceBranch(sharedPayload, payloads[0],
				new byte[BRANCH_OVERHEAD_PAYLOAD_LENGTH]);
		return new DominatingStaticOwnerGraph(owner, branch);
	}

	private static Path prepareHeapPath(Path dir, String prefix) throws IOException {
		Files.createDirectories(dir);
		Path heap = dir.resolve(prefix + "-" + SCENARIO_COUNTER.incrementAndGet() + ".hprof");
		Files.deleteIfExists(heap);
		heap.toFile().deleteOnExit();
		return heap;
	}

	private static void runDumpFixtureProcess(String scenario, Path heap) throws Exception {
		ProcessBuilder processBuilder = new ProcessBuilder(javaExecutable(), "-cp", System.getProperty("java.class.path"),
				HeapDumpFixtureProcessMain.class.getName(), scenario, heap.toAbsolutePath().toString());
		processBuilder.redirectErrorStream(true);
		Process process = processBuilder.start();
		String output = readProcessOutput(process);
		if (!process.waitFor(90L, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IllegalStateException("Timed out creating fixture heap dump for scenario " + scenario);
		}
		if (process.exitValue() != 0) {
			throw new IllegalStateException(
					"Fixture heap dump process failed for scenario " + scenario + ": " + output.trim());
		}
	}

	private static String readProcessOutput(Process process) throws IOException {
		try (var input = process.getInputStream()) {
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void awaitDumpReady(Path heap) throws Exception {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20L);
		long previousSize = -1L;
		int stableChecks = 0;

		while (System.nanoTime() < deadlineNanos) {
			if (!Files.isRegularFile(heap)) {
				Thread.sleep(50L);
				continue;
			}

			long currentSize = Files.size(heap);
			if (currentSize > 0L && currentSize == previousSize) {
				stableChecks++;
			}
			else {
				stableChecks = 0;
			}
			previousSize = currentSize;

			if (stableChecks >= 2 && canOpenForRead(heap)) {
				return;
			}
			Thread.sleep(50L);
		}

		throw new IllegalStateException("Heap dump did not become ready in time: " + heap);
	}

	private static boolean canOpenForRead(Path heap) {
		try (var ignored = Files.newInputStream(heap)) {
			return true;
		}
		catch (Exception ignored) {
			return false;
		}
	}

	private static String javaExecutable() {
		String javaHome = System.getProperty("java.home");
		String executable = isWindows() ? "java.exe" : "java";
		return Path.of(javaHome, "bin", executable).toString();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	static String preferredNodeArrayTypeName() {
		return PreferredNode.class.getName() + "[]";
	}

	static String secondaryNodeArrayTypeName() {
		return SecondaryNode.class.getName() + "[]";
	}

	private static final class RetainedGraph {

		private final RetainedBucket primary;

		private final RetainedBucket secondary;

		private RetainedGraph(RetainedBucket primary, RetainedBucket secondary) {
			this.primary = primary;
			this.secondary = secondary;
		}

	}

	private static final class RetainedBucket {

		@SuppressWarnings("unused")
		private final String label;

		@SuppressWarnings("unused")
		private final byte[] payload;

		private RetainedBucket(String label, byte[] payload) {
			this.label = label;
			this.payload = payload;
		}

	}

	private static final class PackagePreferenceGraph {

		private final String[][] foreignArrays;

		private final SecondaryNode[][] secondaryArrays;

		private final PreferredNode[] preferredArray;

		private PackagePreferenceGraph(String[][] foreignArrays, SecondaryNode[][] secondaryArrays,
				PreferredNode[] preferredArray) {
			this.foreignArrays = foreignArrays;
			this.secondaryArrays = secondaryArrays;
			this.preferredArray = preferredArray;
		}

	}

	private static final class SecondaryNode {

		@SuppressWarnings("unused")
		private final String label;

		private SecondaryNode(String label) {
			this.label = label;
		}

	}

	private static final class PreferredNode {

		@SuppressWarnings("unused")
		private final String label;

		@SuppressWarnings("unused")
		private final byte[] payload;

		@SuppressWarnings("unused")
		private final PreferredLeaf leaf;

		private PreferredNode(String label, byte[] payload, PreferredLeaf leaf) {
			this.label = label;
			this.payload = payload;
			this.leaf = leaf;
		}

	}

	private static final class PreferredLeaf {

		@SuppressWarnings("unused")
		private final byte[] payload;

		private PreferredLeaf(byte[] payload) {
			this.payload = payload;
		}

	}

	private static final class DominatingStaticOwnerGraph {

		private final DominatingStaticOwner owner;

		private final SharedReferenceBranch branch;

		private DominatingStaticOwnerGraph(DominatingStaticOwner owner, SharedReferenceBranch branch) {
			this.owner = owner;
			this.branch = branch;
		}

	}

	private static final class DominatingStaticOwner {

		@SuppressWarnings("unused")
		private final String label;

		@SuppressWarnings("unused")
		private final DominatedPayload[] payloads;

		@SuppressWarnings("unused")
		private final SharedPayload sharedPayload;

		@SuppressWarnings("unused")
		private final byte[] ownerMetadata;

		private DominatingStaticOwner(String label, DominatedPayload[] payloads, SharedPayload sharedPayload,
				byte[] ownerMetadata) {
			this.label = label;
			this.payloads = payloads;
			this.sharedPayload = sharedPayload;
			this.ownerMetadata = ownerMetadata;
		}

	}

	private static final class SharedReferenceBranch {

		@SuppressWarnings("unused")
		private final SharedPayload sharedPayload;

		@SuppressWarnings("unused")
		private final DominatedPayload sharedPayloadAlias;

		@SuppressWarnings("unused")
		private final byte[] branchMetadata;

		private SharedReferenceBranch(SharedPayload sharedPayload, DominatedPayload sharedPayloadAlias,
				byte[] branchMetadata) {
			this.sharedPayload = sharedPayload;
			this.sharedPayloadAlias = sharedPayloadAlias;
			this.branchMetadata = branchMetadata;
		}

	}

	private static final class DominatedPayload {

		@SuppressWarnings("unused")
		private final String label;

		@SuppressWarnings("unused")
		private final byte[] payload;

		@SuppressWarnings("unused")
		private final DominatedLeaf leaf;

		private DominatedPayload(String label, byte[] payload, DominatedLeaf leaf) {
			this.label = label;
			this.payload = payload;
			this.leaf = leaf;
		}

	}

	private static final class SharedPayload {

		@SuppressWarnings("unused")
		private final String label;

		@SuppressWarnings("unused")
		private final byte[] payload;

		@SuppressWarnings("unused")
		private final DominatedLeaf leaf;

		private SharedPayload(String label, byte[] payload, DominatedLeaf leaf) {
			this.label = label;
			this.payload = payload;
			this.leaf = leaf;
		}

	}

	private static final class DominatedLeaf {

		@SuppressWarnings("unused")
		private final byte[] payload;

		private DominatedLeaf(byte[] payload) {
			this.payload = payload;
		}

	}

}
