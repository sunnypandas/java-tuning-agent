package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapDumpShallowSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapShallowClassEntry;

import shark.CloseableHeapGraph;
import shark.HprofHeapGraph;
import shark.HprofRecordTag;
import shark.HeapObject;

/**
 * Builds structured shallow totals plus bounded Markdown from a heap dump using LeakCanary Shark.
 */
public final class SharkHeapDumpSummarizer {

	private final int defaultTopClasses;

	private final int defaultMaxOutputChars;

	public SharkHeapDumpSummarizer(int defaultTopClasses, int defaultMaxOutputChars) {
		this.defaultTopClasses = defaultTopClasses > 0 ? defaultTopClasses : 40;
		this.defaultMaxOutputChars = defaultMaxOutputChars > 0 ? defaultMaxOutputChars : 32_000;
	}

	/**
	 * Full structured summary for evidence packs and MCP mapping.
	 */
	public HeapDumpShallowSummary summarize(Path heapDumpPath, Integer topClassLimit, Integer maxOutputChars) {
		int topN = topClassLimit != null && topClassLimit > 0 ? topClassLimit : defaultTopClasses;
		int maxChars = maxOutputChars != null && maxOutputChars > 0 ? maxOutputChars : defaultMaxOutputChars;

		if (heapDumpPath == null) {
			return emptyFailure("heapDumpPath is null");
		}
		Path normalized = heapDumpPath.toAbsolutePath().normalize();
		if (!Files.isRegularFile(normalized)) {
			return emptyFailure("Not a regular file: " + normalized);
		}

		Map<String, Long> shallowByClass = new HashMap<>();
		long totalTracked;
		try (CloseableHeapGraph graph = openGraph(normalized.toFile())) {
			totalTracked = aggregateShallow(graph, shallowByClass);
		}
		catch (IOException e) {
			return emptyFailure("Failed to read heap dump: " + e.getMessage());
		}
		catch (RuntimeException | OutOfMemoryError e) {
			String msg = e instanceof OutOfMemoryError ? "Out of memory while indexing heap dump (try a smaller dump or raise -Xmx)"
					: e.getMessage();
			return emptyFailure(msg == null ? e.getClass().getSimpleName() : msg);
		}

		List<HeapShallowClassEntry> topEntries = buildTopEntries(shallowByClass, totalTracked, topN);
		String markdown = renderMarkdown(normalized, totalTracked, shallowByClass, topN, maxChars);
		boolean truncated = markdown.length() >= maxChars;
		return new HeapDumpShallowSummary(topEntries, totalTracked, truncated, markdown, "");
	}

	private static HeapDumpShallowSummary emptyFailure(String error) {
		return new HeapDumpShallowSummary(List.of(), 0L, false, "", error);
	}

	private static CloseableHeapGraph openGraph(java.io.File heapFile) {
		return HprofHeapGraph.Companion.openHeapGraph(heapFile, null, EnumSet.allOf(HprofRecordTag.class));
	}

	private static long aggregateShallow(CloseableHeapGraph graph, Map<String, Long> shallowByClass) {
		long total = 0L;

		Iterator<HeapObject> objects = graph.getObjects().iterator();
		while (objects.hasNext()) {
			HeapObject obj = objects.next();
			var inst = obj.getAsInstance();
			if (inst != null) {
				int size = inst.getByteSize();
				add(shallowByClass, inst.getInstanceClassName(), size);
				total += (long) size;
				continue;
			}
			var objArray = obj.getAsObjectArray();
			if (objArray != null) {
				int size = objArray.getByteSize();
				add(shallowByClass, objArray.getArrayClassName(), size);
				total += (long) size;
				continue;
			}
			var primArray = obj.getAsPrimitiveArray();
			if (primArray != null) {
				int size = primArray.getByteSize();
				add(shallowByClass, primArray.getArrayClassName(), size);
				total += (long) size;
			}
		}

		return total;
	}

	private static void add(Map<String, Long> shallowByClass, String className, int sizeBytes) {
		String key = className == null || className.isBlank() ? "(unknown class)" : className;
		shallowByClass.merge(key, (long) sizeBytes, Long::sum);
	}

	private static List<HeapShallowClassEntry> buildTopEntries(Map<String, Long> shallowByClass, long totalTracked,
			int topN) {
		List<Map.Entry<String, Long>> sorted = new ArrayList<>(shallowByClass.entrySet());
		sorted.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()));
		List<HeapShallowClassEntry> out = new ArrayList<>(Math.min(topN, sorted.size()));
		int rank = 1;
		for (Map.Entry<String, Long> e : sorted) {
			if (rank > topN) {
				break;
			}
			long bytes = e.getValue();
			double pct = totalTracked <= 0L ? 0.0d : (100.0d * bytes / (double) totalTracked);
			out.add(new HeapShallowClassEntry(e.getKey(), bytes, pct));
			rank++;
		}
		return List.copyOf(out);
	}

	private static String renderMarkdown(Path heapDumpPath, long totalTracked, Map<String, Long> shallowByClass, int topN,
			int maxChars) {
		List<Map.Entry<String, Long>> sorted = new ArrayList<>(shallowByClass.entrySet());
		sorted.sort(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()));

		StringBuilder sb = new StringBuilder();
		sb.append("### Heap dump file summary (local, shallow by class)\n\n");
		sb.append("**Source:** `").append(heapDumpPath).append("`\n\n");
		sb.append(
				"This section is generated on the MCP server by parsing the `.hprof` with **Shark**. It lists **shallow** sizes only (per-class totals of instance and array payloads as reported by Shark). ");
		sb.append("It is **not** MAT/Eclipse retained-size / dominator analysis.\n\n");

		sb.append("| Metric | Value |\n");
		sb.append("| --- | --- |\n");
		sb.append("| Tracked shallow total (instances + arrays) | ").append(formatBytes(totalTracked)).append(" |\n");
		sb.append("| Distinct classes / array types in table | ").append(sorted.size()).append(" |\n\n");

		sb.append("| Rank | Type | Shallow bytes | Approx. % of tracked |\n");
		sb.append("| --- | --- | --- | --- |\n");

		int rank = 1;
		for (Map.Entry<String, Long> e : sorted) {
			if (rank > topN) {
				break;
			}
			long bytes = e.getValue();
			double pct = totalTracked <= 0L ? 0.0d : (100.0d * bytes / (double) totalTracked);
			sb.append("| ").append(rank).append(" | `").append(escapeTicks(e.getKey())).append("` | ")
					.append(formatBytes(bytes)).append(" | ").append(String.format(Locale.ROOT, "%.2f", pct))
					.append("% |\n");
			rank++;
			if (sb.length() >= maxChars) {
				sb.append("\n*(Output truncated to maxOutputChars.)*\n");
				break;
			}
		}

		String out = sb.toString();
		if (out.length() > maxChars) {
			return out.substring(0, maxChars) + "\n*(Output truncated to maxOutputChars.)*\n";
		}
		return out;
	}

	private static String escapeTicks(String raw) {
		return raw.replace("`", "\\`");
	}

	private static String formatBytes(long bytes) {
		if (bytes < 1024L) {
			return bytes + " B";
		}
		double v = bytes;
		String[] units = { "B", "KiB", "MiB", "GiB", "TiB" };
		int u = 0;
		while (v >= 1024.0 && u < units.length - 1) {
			v /= 1024.0;
			u++;
		}
		return String.format(Locale.ROOT, "%.2f %s", v, units[u]);
	}
}
