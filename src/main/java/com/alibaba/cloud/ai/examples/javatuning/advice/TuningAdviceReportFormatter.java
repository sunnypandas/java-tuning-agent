package com.alibaba.cloud.ai.examples.javatuning.advice;

import java.util.List;

/**
 * Builds a stable Markdown summary of a {@link TuningAdviceReport} so MCP clients can surface the full
 * report (including {@code suspectedCodeHotspots}) without re-serializing JSON field-by-field.
 * <p>
 * Uses headings (## / ###), lists, and fenced {@code text} blocks so renderers show structure; raw JSON
 * transport still escapes newlines as {@code \n}.
 */
public final class TuningAdviceReportFormatter {

	private TuningAdviceReportFormatter() {
	}

	public static String toMarkdown(TuningAdviceReport report) {
		StringBuilder sb = new StringBuilder();

		sb.append("## Findings\n\n");
		appendFindings(sb, report.findings());

		sb.append("## Recommendations\n\n");
		appendRecommendations(sb, report.recommendations());

		sb.append("## Suspected code hotspots\n\n");
		appendHotspots(sb, report.suspectedCodeHotspots());

		sb.append("## Missing data\n\n");
		appendBulletLines(sb, report.missingData(), "(none)");

		sb.append("## Next steps\n\n");
		appendBulletLines(sb, report.nextSteps(), "(none)");

		sb.append("## Confidence\n\n");
		sb.append("- **level:** ").append(nullToEmpty(report.confidence())).append('\n');
		sb.append("- **reasons:**\n");
		if (report.confidenceReasons().isEmpty()) {
			sb.append("  - (none)\n");
		}
		else {
			for (String r : report.confidenceReasons()) {
				sb.append("  - ").append(r).append('\n');
			}
		}
		return sb.toString().trim() + '\n';
	}

	private static void appendFindings(StringBuilder sb, List<TuningFinding> findings) {
		if (findings.isEmpty()) {
			sb.append("(none)\n\n");
			return;
		}
		int i = 1;
		for (TuningFinding f : findings) {
			sb.append("### ").append(i++).append(". ").append(nullToEmpty(f.title())).append(" — **")
					.append(nullToEmpty(f.severity())).append("**\n\n");
			sb.append("- **Evidence**\n\n");
			appendFencedBlock(sb, nullToEmpty(f.evidence()));
			sb.append("- **Reasoning:** ").append(nullToEmpty(f.reasoningType())).append('\n');
			sb.append("- **Impact:** ").append(nullToEmpty(f.impact())).append("\n\n");
		}
	}

	private static void appendRecommendations(StringBuilder sb, List<TuningRecommendation> recs) {
		if (recs.isEmpty()) {
			sb.append("(none)\n\n");
			return;
		}
		int n = 1;
		for (TuningRecommendation r : recs) {
			sb.append("### ").append(n++).append(". ").append(nullToEmpty(r.action())).append(" — `")
					.append(nullToEmpty(r.category())).append("`\n\n");
			sb.append("- **Config example:** ").append(nullToEmpty(r.configExample())).append('\n');
			sb.append("- **Expected benefit:** ").append(nullToEmpty(r.expectedBenefit())).append('\n');
			sb.append("- **Risk:** ").append(nullToEmpty(r.risk())).append('\n');
			sb.append("- **Preconditions:** ").append(nullToEmpty(r.preconditions())).append("\n\n");
		}
	}

	private static void appendHotspots(StringBuilder sb, List<SuspectedCodeHotspot> hotspots) {
		if (hotspots.isEmpty()) {
			sb.append("(none — class histogram + `sourceRoots` needed for file-level hints.)\n\n");
			return;
		}
		int i = 1;
		for (SuspectedCodeHotspot h : hotspots) {
			sb.append("### ").append(i++).append(". `").append(nullToEmpty(h.className())).append("`\n\n");
			sb.append("- **File:** ").append(nullToEmpty(h.fileHint())).append('\n');
			sb.append("- **Reason:** ").append(nullToEmpty(h.suspicionReason())).append('\n');
			sb.append("- **Evidence:** ").append(nullToEmpty(h.evidenceLink())).append('\n');
			sb.append("- **Confidence:** ").append(nullToEmpty(h.confidence())).append("\n\n");
		}
	}

	private static void appendFencedBlock(StringBuilder sb, String content) {
		String body = content.isEmpty() ? "(none)" : content;
		boolean useTildeFence = body.contains("```");
		String open = useTildeFence ? "~~~text\n" : "```text\n";
		String close = useTildeFence ? "\n~~~\n\n" : "\n```\n\n";
		sb.append(open);
		sb.append(body);
		if (!body.endsWith("\n")) {
			sb.append('\n');
		}
		sb.append(close);
	}

	private static void appendBulletLines(StringBuilder sb, List<String> lines, String ifEmpty) {
		if (lines.isEmpty()) {
			sb.append(ifEmpty).append("\n\n");
			return;
		}
		for (String line : lines) {
			sb.append("- ").append(nullToEmpty(line)).append('\n');
		}
		sb.append('\n');
	}

	private static String nullToEmpty(String s) {
		return s == null ? "" : s;
	}
}
