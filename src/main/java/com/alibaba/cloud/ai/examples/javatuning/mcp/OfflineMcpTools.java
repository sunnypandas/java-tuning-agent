package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapDumpChunkRepository;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapDumpChunkSubmissionResult;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapDumpFileSummaryResult;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapRetentionAnalyzer;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineAnalysisService;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineBundleDraft;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineDraftValidationResult;
import com.alibaba.cloud.ai.examples.javatuning.offline.SharkHeapDumpSummarizer;
import com.alibaba.cloud.ai.examples.javatuning.runtime.HeapRetentionAnalysisResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * MCP tools for offline (imported) JVM evidence and analysis independent from live PID collection.
 */
public class OfflineMcpTools {

	private final OfflineAnalysisService offlineAnalysisService;

	private final HeapDumpChunkRepository heapDumpChunkRepository;

	private final SharkHeapDumpSummarizer heapDumpSummarizer;

	private final HeapRetentionAnalyzer heapRetentionAnalyzer;

	public OfflineMcpTools(OfflineAnalysisService offlineAnalysisService,
			HeapDumpChunkRepository heapDumpChunkRepository, SharkHeapDumpSummarizer heapDumpSummarizer,
			HeapRetentionAnalyzer heapRetentionAnalyzer) {
		this.offlineAnalysisService = offlineAnalysisService;
		this.heapDumpChunkRepository = heapDumpChunkRepository;
		this.heapDumpSummarizer = heapDumpSummarizer;
		this.heapRetentionAnalyzer = heapRetentionAnalyzer;
	}

	@Tool(description = """
			离线模式：校验手工导入的 JVM 诊断草稿（必选 B1-B6 与推荐项标记）。无服务端会话；每次可携带完整草稿 JSON。
			English: Validate an imported offline JVM diagnosis draft and return missing required inputs, degradation status, and the next Chinese prompt.
			草稿字段形状以 MCP inputSchema 为准：jvmIdentityText/jdkInfoText/runtimeSnapshotText 为普通字符串；
			classHistogram/threadDump 为 OfflineArtifactSource 对象，只能传 {"filePath":"..."} 或 {"inlineText":"..."}，不得传 bare string；
			heapDumpAbsolutePath 为普通字符串路径。若本地文件已存在，优先使用 filePath 而不是 inlineText。
			返回缺失必选 ID、是否允许继续（降级）、中文 nextPromptZh。
			proceedWithMissingRequired=true 时在必选不齐情况下仍 allowedToProceed=true 并带 degradationWarnings。""")
	public OfflineDraftValidationResult validateOfflineAnalysisDraft(
			@ToolParam(description = "OfflineBundleDraft。关键字段：classHistogram/threadDump 必须是 {\"filePath\":\"...\"} 或 {\"inlineText\":\"...\"}；不要给这两个字段传 bare string。heapDumpAbsolutePath 才是普通字符串路径。") OfflineBundleDraft draft,
			@ToolParam(description = "是否在必选不齐时仍允许后续生成报告（降级）。") boolean proceedWithMissingRequired) {
		return offlineAnalysisService.validate(draft, proceedWithMissingRequired);
	}

	@Tool(description = """
			离线模式：提交 heap dump 的一个分块（Base64）。uploadId 为空时创建新上传会话并使用 chunkTotal 作为总分块数；
			English: Submit one Base64 heap dump chunk; reuse the returned uploadId for later chunks.
			后续调用沿用返回的 uploadId。索引为 0..chunkTotal-1。
			全部 chunk 提交后使用 finalizeOfflineHeapDump 校验 SHA-256 与大小；将返回的绝对路径写入 OfflineBundleDraft.heapDumpAbsolutePath。""")
	public HeapDumpChunkSubmissionResult submitOfflineHeapDumpChunk(
			@ToolParam(required = false, description = "已有上传 id；留空则创建新上传。") String uploadId,
			@ToolParam(description = "当前块序号，从 0 开始。") int chunkIndex,
			@ToolParam(description = "总分块数（创建上传时必填且与各次调用一致）。") int chunkTotal,
			@ToolParam(description = "分块字节的 Base64（标准 RFC4648）。") String chunkBase64) {
		String id = (uploadId == null || uploadId.isBlank())
				? heapDumpChunkRepository.createUpload(chunkTotal)
				: uploadId.trim();
		byte[] bytes = chunkBase64 == null || chunkBase64.isBlank() ? new byte[0]
				: Base64.getDecoder().decode(chunkBase64);
		heapDumpChunkRepository.appendChunk(id, chunkIndex, bytes);
		return new HeapDumpChunkSubmissionResult(id,
				"Chunk " + chunkIndex + "/" + chunkTotal + " stored for upload " + id);
	}

	@Tool(description = """
			离线模式：在完成全部分块后校验并合并为单个 .hprof 文件。
			English: Finalize uploaded chunks into one verified .hprof file and return the absolute heapDumpAbsolutePath value.
			成功时返回 finalizeHeapDumpPath（绝对路径），请写入草稿的 heapDumpAbsolutePath。""")
	public OfflineHeapDumpFinalizeResult finalizeOfflineHeapDump(
			@ToolParam(description = "submitOfflineHeapDumpChunk 返回的 uploadId。") String uploadId,
			@ToolParam(description = "整个文件的 SHA-256 十六进制（小写或大写均可）。") String expectedSha256Hex,
			@ToolParam(description = "整个文件的精确字节长度。") long expectedSizeBytes) {
		Path path = heapDumpChunkRepository.finalize(uploadId, expectedSha256Hex, expectedSizeBytes);
		return new OfflineHeapDumpFinalizeResult(path.toAbsolutePath().normalize().toString(),
				"Heap dump finalized; set heapDumpAbsolutePath on your draft to this path.");
	}

	@Tool(description = """
			离线模式：从导入材料生成与在线模式相同结构的 TuningAdviceReport（含 formattedSummary）。
			English: Generate the same structured tuning advice report from imported offline evidence.
			需先通过校验或声明降级（proceedWithMissingRequired）。
			若草稿包含类直方图、线程栈或堆路径，须提供非空 confirmationToken（与 collectMemoryGcEvidence / generateTuningAdvice 特权语义一致）。
			analysisDepth 可为空；为空时按 balanced 处理，只有 deep 会在存在 heapDumpAbsolutePath 时附加 retention 证据。
			草稿字段形状以 MCP inputSchema 为准：classHistogram/threadDump 为 OfflineArtifactSource 对象，只能传 {"filePath":"..."} 或 {"inlineText":"..."}，不得传 bare string；
			heapDumpAbsolutePath 为普通字符串路径。若本地文件已存在，优先使用 filePath。
			当 heapDumpAbsolutePath 指向已存在的 .hprof 时，服务端自动用 Shark 做浅层按类索引，并参与规则诊断与报告（见 java-tuning-agent.heap-summary.auto-enabled）。""")
	public TuningAdviceReport generateOfflineTuningAdvice(
			@ToolParam(required = false, description = "可选源码根与包名，用于热点关联。") CodeContextSummary codeContextSummary,
			@ToolParam(description = "完整 OfflineBundleDraft。关键字段：classHistogram/threadDump 必须是 {\"filePath\":\"...\"} 或 {\"inlineText\":\"...\"}；不要给这两个字段传 bare string。heapDumpAbsolutePath 才是普通字符串路径。") OfflineBundleDraft draft,
			@ToolParam(description = "环境标签，如 prod / local。") String environment,
			@ToolParam(description = "优化目标描述。") String optimizationGoal,
			@ToolParam(required = false, description = "堆分析深度：fast / balanced / deep。为空时按 balanced 处理；只有 deep 会尝试附加 retention 证据。") String analysisDepth,
			@ToolParam(required = false, description = "用户同意使用导入的诊断材料时的 token；无直方图/线程/堆时可空。") String confirmationToken,
			@ToolParam(description = "必选缺失时是否仍生成（降级）。") boolean proceedWithMissingRequired) {
		environment = environment == null ? "" : environment;
		optimizationGoal = optimizationGoal == null ? "" : optimizationGoal;
		analysisDepth = analysisDepth == null ? "" : analysisDepth;
		confirmationToken = confirmationToken == null ? "" : confirmationToken;
		CodeContextSummary ctx = codeContextSummary == null ? CodeContextSummary.empty() : codeContextSummary;
		return offlineAnalysisService.generateOfflineAdvice(draft, ctx, environment, optimizationGoal, analysisDepth,
				confirmationToken, proceedWithMissingRequired);
	}

	@Tool(description = """
			离线模式：在 MCP 服务端本地解析 heap dump 文件（.hprof），生成有字符上限的 Markdown 摘要。
			English: Summarize a local .hprof with bounded Shark shallow-by-class rows without running full advice.
			摘要是 Shark 的浅层按类型合计（实例与数组），不是 MAT retained/dominator 分析；不向模型传输原始二进制。
			可与 finalizeOfflineHeapDump 输出的路径配合使用。""")
	public HeapDumpFileSummaryResult summarizeOfflineHeapDumpFile(
			@ToolParam(description = "已完成写入的 .hprof 绝对路径（可与 OfflineBundleDraft.heapDumpAbsolutePath 相同）。") String heapDumpAbsolutePath,
			@ToolParam(required = false, description = "按浅层字节排序后输出的最大类型条数；留空则用服务端默认（通常 40）。") Integer topClassLimit,
			@ToolParam(required = false, description = "Markdown 最大字符数；留空则用服务端默认（通常 32000）。") Integer maxOutputChars) {
		Path path = heapDumpAbsolutePath == null || heapDumpAbsolutePath.isBlank()
				? null
				: Path.of(heapDumpAbsolutePath.trim());
		return HeapDumpFileSummaryResult.from(heapDumpSummarizer.summarize(path, topClassLimit, maxOutputChars));
	}

	@Tool(description = """
			Offline mode: analyze an existing local .hprof for retention-oriented evidence and return structured JSON plus Markdown.
			中文：离线分析已有本地 .hprof，产出 holder/引用链/GC root 方向的 retention 证据与 Markdown 摘要。
			This tool focuses on suspected holders, representative reference chains, and GC-root hints; it is separate from summarizeOfflineHeapDumpFile, which only provides a shallow-by-class summary.
			retainedBytesApprox is populated only when the analyzer can defend that retained-style interpretation; otherwise it may be null and reachableSubgraphBytesApprox is only an approximate ranking hint.
			""")
	public HeapRetentionAnalysisResult analyzeOfflineHeapRetention(
			@ToolParam(description = "Absolute path to an existing .hprof file on the host.") String heapDumpAbsolutePath,
			@ToolParam(required = false, description = "Maximum number of holder / chain rows to include in the retention result.") Integer topObjectLimit,
			@ToolParam(required = false, description = "Maximum Markdown characters to return in the retention summary.") Integer maxOutputChars,
			@ToolParam(required = false, description = "Analysis depth hint: fast, balanced, or deep.") String analysisDepth,
			@ToolParam(required = false, description = "Optional terminal types to prioritize, such as byte[] or java.lang.String[].") List<String> focusTypes,
			@ToolParam(required = false, description = "Optional business package prefixes used to prioritize candidates before bounded truncation; not a hard filter.") List<String> focusPackages) {
		Path path = heapDumpAbsolutePath == null || heapDumpAbsolutePath.isBlank()
				? null
				: Path.of(heapDumpAbsolutePath.trim());
		return heapRetentionAnalyzer.analyze(path, topObjectLimit, maxOutputChars, analysisDepth, focusTypes,
				focusPackages);
	}

}
