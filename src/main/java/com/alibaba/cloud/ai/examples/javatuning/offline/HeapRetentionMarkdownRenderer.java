package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import com.alibaba.cloud.ai.examples.javatuning.runtime.GcRootHint;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.RetentionChainSummary;
import com.alibaba.cloud.ai.examples.javatuning.runtime.SuspectedHolderSummary;

public final class HeapRetentionMarkdownRenderer {

	public String render(Path heapDumpPath, HeapRetentionSummary summary, int maxOutputChars) {
		int boundedChars = maxOutputChars > 0 ? maxOutputChars : 16_000;
		StringBuilder sb = new StringBuilder();
		sb.append("### Heap retention analysis (local, holder-oriented)\n\n");
		sb.append(
				"Shark retention hint output. `reachable subgraph estimate` is a bounded graph-based approximation, and this is not MAT exact retained size.\n\n");
		if (heapDumpPath != null) {
			sb.append("**Source:** `").append(heapDumpPath).append("`\n\n");
		}

		if (!summary.warnings().isEmpty()) {
			sb.append("**Warnings:**\n");
			for (String warning : summary.warnings()) {
				sb.append("- ").append(warning).append("\n");
			}
			sb.append("\n");
		}

		if (!summary.dominantRetainedTypes().isEmpty()) {
			sb.append("**Dominant types**\n\n");
			sb.append("| Type | Objects | Terminal shallow |\n");
			sb.append("| --- | --- | --- |\n");
			for (var type : summary.dominantRetainedTypes()) {
				sb.append("| `").append(escapePipes(type.typeName())).append("` | ").append(type.objectCountApprox())
						.append(" | ").append(formatBytes(type.terminalShallowBytes())).append(" |\n");
			}
			sb.append("\n");
		}

		if (!summary.suspectedHolders().isEmpty()) {
			sb.append("**Suspected holders**\n\n");
			sb.append("| Holder | Role | Reachable subgraph estimate | Example path |\n");
			sb.append("| --- | --- | --- | --- |\n");
			for (SuspectedHolderSummary holder : summary.suspectedHolders()) {
				sb.append("| `").append(escapePipes(holder.holderType())).append("` | ").append(holder.holderRole())
						.append(" | ").append(formatBytes(holder.reachableSubgraphBytesApprox())).append(" | `")
						.append(escapePipes(holder.exampleFieldPath())).append("` |\n");
			}
			sb.append("\n");
			firstSourceHolder(summary.suspectedHolders())
				.ifPresent(sourceHolder -> sb.append("Likely source holder: `")
					.append(escapePipes(sourceHolder))
					.append("`\n\n"));
		}

		if (!summary.retentionChains().isEmpty()) {
			sb.append("**Representative chains**\n\n");
			for (RetentionChainSummary chain : summary.retentionChains()) {
				sb.append("- `").append(escapePipes(renderChain(chain))).append("`");
				sb.append(" (`reachable subgraph estimate` ").append(formatBytes(chain.reachableSubgraphBytesApprox()))
						.append(")\n");
			}
			sb.append("\n");
		}

		if (!summary.gcRootHints().isEmpty()) {
			sb.append("**GC root hints**\n\n");
			for (GcRootHint hint : summary.gcRootHints()) {
				sb.append("- `").append(escapePipes(hint.rootKind())).append("` near `")
						.append(escapePipes(hint.exampleOwnerType())).append("` (").append(hint.occurrenceCountApprox())
						.append(")\n");
			}
			sb.append("\n");
		}

		sb.append("**Confidence:** ").append(summary.confidenceAndLimits().confidence()).append("\n\n");
		for (String limitation : summary.confidenceAndLimits().limitations()) {
			sb.append("- ").append(limitation).append("\n");
		}
		for (String engineNote : summary.confidenceAndLimits().engineNotes()) {
			sb.append("- ").append(engineNote).append("\n");
		}

		return bound(sb.toString(), boundedChars);
	}

	private static java.util.Optional<String> firstSourceHolder(List<SuspectedHolderSummary> holders) {
		return holders.stream()
			.map(SuspectedHolderSummary::exampleFieldPath)
			.filter(path -> path != null && !path.isBlank())
			.findFirst();
	}

	private static String renderChain(RetentionChainSummary chain) {
		StringBuilder sb = new StringBuilder();
		sb.append(chain.rootKind());
		for (var segment : chain.segments()) {
			sb.append(" -> ");
			sb.append(shortType(segment.ownerType())).append(".").append(segment.referenceName());
		}
		sb.append(" -> ").append(chain.terminalType());
		return sb.toString();
	}

	private static String shortType(String typeName) {
		if (typeName == null || typeName.isBlank()) {
			return "(unknown)";
		}
		int lastDot = typeName.lastIndexOf('.');
		return lastDot >= 0 ? typeName.substring(lastDot + 1) : typeName;
	}

	private static String bound(String raw, int maxChars) {
		if (raw.length() <= maxChars) {
			return raw;
		}
		String suffix = "\n\n*(Output truncated to maxOutputChars.)*";
		if (maxChars <= suffix.length()) {
			return raw.substring(0, maxChars);
		}
		return raw.substring(0, maxChars - suffix.length()) + suffix;
	}

	private static String escapePipes(String value) {
		return value == null ? "" : value.replace("|", "\\|").replace("`", "\\`");
	}

	private static String formatBytes(long bytes) {
		if (bytes < 1024L) {
			return bytes + " B";
		}
		double value = bytes;
		String[] units = { "B", "KiB", "MiB", "GiB", "TiB" };
		int index = 0;
		while (value >= 1024.0d && index < units.length - 1) {
			value /= 1024.0d;
			index++;
		}
		return String.format(Locale.ROOT, "%.2f %s", value, units[index]);
	}

}
