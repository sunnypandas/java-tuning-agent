package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.management.HotSpotDiagnosticMXBean;

final class TestHeapDumpSupport {

	private static Object retainedGraph;

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
		retainedGraph = new RetainedGraph(new RetainedBucket("alpha", new byte[256 * 1024]),
				new RetainedBucket("beta", new byte[192 * 1024]));

		Path heap = dir.resolve("retained-bytes-" + System.nanoTime() + ".hprof");
		HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
		bean.dumpHeap(heap.toString(), true);
		return heap;
	}

	static Path dumpFocusPackagePreferenceHeap(Path dir) throws Exception {
		Files.createDirectories(dir);
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
		retainedGraph = new PackagePreferenceGraph(foreignArrays, secondaryArrays, preferredArray);

		Path heap = dir.resolve("focus-packages-" + System.nanoTime() + ".hprof");
		HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
		bean.dumpHeap(heap.toString(), true);
		return heap;
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
