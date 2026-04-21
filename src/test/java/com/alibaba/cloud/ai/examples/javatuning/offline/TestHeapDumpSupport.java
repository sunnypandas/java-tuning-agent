package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.management.HotSpotDiagnosticMXBean;

final class TestHeapDumpSupport {

	private static Object retainedGraph;

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

}
