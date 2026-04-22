package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class TestHeapDumpSupport {

	private static final int FOREIGN_ARRAY_COUNT = 8;

	private static final int FOREIGN_ARRAY_LENGTH = 50_000;

	private static final int PREFERRED_ARRAY_LENGTH = 48_000;

	private static final int SECONDARY_ARRAY_GROUP_COUNT = 4;

	private static final int SECONDARY_ARRAY_LENGTH = 49_000;

	private static final int PREFERRED_NODE_COUNT = 12;

	private TestHeapDumpSupport() {
	}

	static Path dumpStaticRetainedBytesHeap(Path dir) throws Exception {
		Files.createDirectories(dir);
		Path heap = Files.createTempFile("retained-bytes-", ".hprof");
		Files.deleteIfExists(heap);
		heap.toFile().deleteOnExit();
		runDumpFixtureProcess("retained-bytes", heap);
		awaitDumpReady(heap);
		return heap;
	}

	static Path dumpFocusPackagePreferenceHeap(Path dir) throws Exception {
		Files.createDirectories(dir);
		Path heap = Files.createTempFile("focus-packages-", ".hprof");
		Files.deleteIfExists(heap);
		heap.toFile().deleteOnExit();
		runDumpFixtureProcess("focus-packages", heap);
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

}
