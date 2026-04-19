package com.alibaba.cloud.ai.examples.javatuning.offline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.cloud.ai.examples.javatuning.runtime.GcHeapInfoParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JstatGcUtilParser;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmCollectionMetadata;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmGcSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmMemorySnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.JvmRuntimeSnapshot;
import com.alibaba.cloud.ai.examples.javatuning.runtime.VmVersionParser;

/**
 * Builds {@link JvmRuntimeSnapshot} from textual fields on {@link OfflineBundleDraft} (offline
 * import path).
 */
public class OfflineJvmSnapshotAssembler {

	private static final Pattern FLAG_G1_PATTERN = Pattern.compile("(?i)-XX:\\+UseG1GC");

	private static final Pattern FLAG_XMS_PATTERN = Pattern
		.compile("(?i)(?<!\\S)(?:-Xms|(?:-XX:InitialHeapSize=))(\\d+)([kmg]?)(?!\\S)");

	private static final Pattern FLAG_XMX_PATTERN = Pattern
		.compile("(?i)(?<!\\S)(?:-Xmx|(?:-XX:MaxHeapSize=))(\\d+)([kmg]?)(?!\\S)");

	private static final Pattern PID_EQUALS_PATTERN = Pattern.compile("(?i)pid\\s*=\\s*(\\d+)");

	private final VmVersionParser vmVersionParser = new VmVersionParser();

	private final GcHeapInfoParser heapInfoParser = new GcHeapInfoParser();

	private final JstatGcUtilParser gcUtilParser = new JstatGcUtilParser();

	public JvmRuntimeSnapshot assemble(OfflineBundleDraft draft) {
		long collectedAt = System.currentTimeMillis();
		List<String> warnings = new ArrayList<>();

		String jvmIdentityText = draft.jvmIdentityText() != null ? draft.jvmIdentityText() : "";
		String jdkInfoText = draft.jdkInfoText() != null ? draft.jdkInfoText() : "";
		String runtimeSnapshotText = draft.runtimeSnapshotText() != null ? draft.runtimeSnapshotText() : "";

		long pid = parsePid(jvmIdentityText, warnings);

		String jvmVersion = vmVersionParser.parse(jdkInfoText);

		List<String> vmFlags = extractVmFlags(jvmIdentityText);
		String flagsJoined = String.join(" ", vmFlags);

		String heapSection = extractHeapSection(runtimeSnapshotText);
		JvmMemorySnapshot memory = heapInfoParser.parse(heapSection);
		if (!heapSection.isBlank() && memory.heapUsedBytes() == 0L && memory.heapCommittedBytes() == 0L) {
			warnings.add("Heap parse from offline runtime text produced zero used/committed bytes");
		}

		Long xmsBytes = parseFlagSize(flagsJoined, FLAG_XMS_PATTERN);
		Long xmxBytes = parseFlagSize(flagsJoined, FLAG_XMX_PATTERN);
		long heapMaxBytes = xmxBytes != null ? xmxBytes : 0L;
		JvmMemorySnapshot structuredMemory = new JvmMemorySnapshot(memory.heapUsedBytes(), memory.heapCommittedBytes(),
				heapMaxBytes, memory.oldGenUsedBytes(), memory.oldGenCommittedBytes(), memory.metaspaceUsedBytes(),
				xmsBytes, xmxBytes);

		String gcSection = extractGcUtilSection(runtimeSnapshotText);
		String collector = inferCollector(jvmIdentityText, runtimeSnapshotText);
		JvmGcSnapshot gcParsed = gcUtilParser.parse(gcSection.isBlank() ? runtimeSnapshotText : gcSection);
		JvmGcSnapshot gc = new JvmGcSnapshot(collector, gcParsed.youngGcCount(), gcParsed.youngGcTimeMs(),
				gcParsed.fullGcCount(), gcParsed.fullGcTimeMs(), gcParsed.oldUsagePercent());

		JvmCollectionMetadata metadata = new JvmCollectionMetadata(List.of("offline-import"), collectedAt, 0L, false);

		return new JvmRuntimeSnapshot(pid, structuredMemory, gc, vmFlags, jvmVersion, null, null, metadata,
				List.copyOf(warnings));
	}

	private long parsePid(String jvmIdentityText, List<String> warnings) {
		if (jvmIdentityText == null || jvmIdentityText.isBlank()) {
			warnings.add("Could not parse PID from jvmIdentityText");
			return 0L;
		}
		Matcher pidEq = PID_EQUALS_PATTERN.matcher(jvmIdentityText);
		if (pidEq.find()) {
			return Long.parseLong(pidEq.group(1));
		}
		Matcher pidLine = Pattern.compile("(?m)^\\s*(\\d+)\\s*:").matcher(jvmIdentityText);
		if (pidLine.find()) {
			return Long.parseLong(pidLine.group(1));
		}
		Matcher firstNum = Pattern.compile("(\\d+)").matcher(jvmIdentityText);
		if (firstNum.find()) {
			return Long.parseLong(firstNum.group(1));
		}
		warnings.add("Could not parse PID from jvmIdentityText");
		return 0L;
	}

	private List<String> extractVmFlags(String jvmIdentityText) {
		if (jvmIdentityText == null || jvmIdentityText.isBlank()) {
			return List.of();
		}
		for (String rawLine : jvmIdentityText.split("\\R")) {
			String line = rawLine.trim();
			if (line.contains("-X") || line.contains("-XX")) {
				if (line.isEmpty()) {
					continue;
				}
				return List.of(line.split("\\s+"));
			}
		}
		return List.of();
	}

	private String inferCollector(String jvmIdentityText, String runtimeSnapshotText) {
		String combined = (jvmIdentityText != null ? jvmIdentityText : "") + "\n"
				+ (runtimeSnapshotText != null ? runtimeSnapshotText : "");
		if (FLAG_G1_PATTERN.matcher(combined).find()) {
			return "G1";
		}
		return "unknown";
	}

	private String extractHeapSection(String runtimeSnapshotText) {
		if (runtimeSnapshotText == null || runtimeSnapshotText.isBlank()) {
			return "";
		}
		String[] lines = runtimeSnapshotText.split("\\R");
		for (int i = 0; i < lines.length; i++) {
			String lower = lines[i].toLowerCase();
			if (lower.contains("garbage-first heap") || lower.contains("g1 young generation")
					|| lower.contains("g1 old generation")) {
				return String.join("\n", Arrays.copyOfRange(lines, i, lines.length));
			}
		}
		for (int i = 0; i < lines.length; i++) {
			String t = lines[i].trim();
			if (t.equalsIgnoreCase("Heap") || lines[i].contains("GC.heap_info")) {
				return String.join("\n", Arrays.copyOfRange(lines, i, lines.length));
			}
		}
		if (runtimeSnapshotText.toLowerCase().contains("heap")) {
			return runtimeSnapshotText;
		}
		return runtimeSnapshotText;
	}

	private String extractGcUtilSection(String runtimeSnapshotText) {
		if (runtimeSnapshotText == null || runtimeSnapshotText.isBlank()) {
			return "";
		}
		List<String> lines = runtimeSnapshotText.lines().map(String::trim).toList();
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			if (line.contains("S0") && (line.contains("YGCT") || line.contains("YGC"))) {
				StringBuilder sb = new StringBuilder();
				for (int j = i; j < lines.size(); j++) {
					String l = lines.get(j).trim();
					if (l.isEmpty()) {
						break;
					}
					sb.append(l).append('\n');
				}
				String block = sb.toString().trim();
				if (!block.isEmpty()) {
					return block;
				}
			}
		}
		return "";
	}

	private Long parseFlagSize(String flags, Pattern pattern) {
		if (flags == null || flags.isBlank()) {
			return null;
		}
		Matcher matcher = pattern.matcher(flags);
		if (!matcher.find()) {
			return null;
		}
		return toBytesWithUnit(matcher.group(1), matcher.group(2));
	}

	private long toBytesWithUnit(String value, String unit) {
		long parsed = Long.parseLong(value);
		if (unit == null || unit.isBlank()) {
			return parsed;
		}
		return switch (unit.toLowerCase()) {
			case "k" -> parsed * 1024L;
			case "m" -> parsed * 1024L * 1024L;
			case "g" -> parsed * 1024L * 1024L * 1024L;
			default -> parsed;
		};
	}

}
