package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import com.sun.management.HotSpotDiagnosticMXBean;

public final class HeapDumpFixtureProcessMain {

	private static Object retainedGraph;

	private HeapDumpFixtureProcessMain() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			throw new IllegalArgumentException("Expected arguments: <scenario> <heapDumpPath>");
		}
		String scenario = args[0];
		Path heapDumpPath = Path.of(args[1]).toAbsolutePath().normalize();
		Path parent = heapDumpPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.deleteIfExists(heapDumpPath);

		retainedGraph = switch (scenario) {
			case "retained-bytes" -> TestHeapDumpSupport.createRetainedBytesFixture();
			case "focus-packages" -> TestHeapDumpSupport.createFocusPackageFixture();
			case "dominating-static-owner" -> TestHeapDumpSupport.createDominatingStaticOwnerFixture();
			default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
		};

		HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
		bean.dumpHeap(heapDumpPath.toString(), false);
	}

}
