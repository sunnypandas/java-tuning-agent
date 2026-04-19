package com.alibaba.cloud.ai.examples.javatuning.mcp;

import java.nio.file.Path;
import java.util.Base64;

import com.alibaba.cloud.ai.examples.javatuning.advice.CodeContextSummary;
import com.alibaba.cloud.ai.examples.javatuning.advice.TuningAdviceReport;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapDumpChunkRepository;
import com.alibaba.cloud.ai.examples.javatuning.offline.HeapDumpChunkSubmissionResult;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineAnalysisService;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineBundleDraft;
import com.alibaba.cloud.ai.examples.javatuning.offline.OfflineDraftValidationResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * MCP tools for offline (imported) JVM evidence and analysis — independent from live PID collection.
 */
public class OfflineMcpTools {

	private final OfflineAnalysisService offlineAnalysisService;

	private final HeapDumpChunkRepository heapDumpChunkRepository;

	public OfflineMcpTools(OfflineAnalysisService offlineAnalysisService,
			HeapDumpChunkRepository heapDumpChunkRepository) {
		this.offlineAnalysisService = offlineAnalysisService;
		this.heapDumpChunkRepository = heapDumpChunkRepository;
	}

	@Tool(description = """
			离线模式：校验手工导入的 JVM 诊断草稿（必选 B1–B6 与推荐项标记）。无服务端会话；每次可携带完整草稿 JSON。\
			返回缺失必选 ID、是否允许继续（降级）、中文 nextPromptZh。\
			proceedWithMissingRequired=true 时在必选不齐情况下仍 allowedToProceed=true 并带 degradationWarnings。""")
	public OfflineDraftValidationResult validateOfflineAnalysisDraft(
			@ToolParam(description = "OfflineBundleDraft: jvmIdentityText, jdkInfoText, runtimeSnapshotText, classHistogram, threadDump, heapDumpAbsolutePath, 推荐项与背景字段") OfflineBundleDraft draft,
			@ToolParam(description = "是否在必选不齐时仍允许后续生成报告（降级）。") boolean proceedWithMissingRequired) {
		return offlineAnalysisService.validate(draft, proceedWithMissingRequired);
	}

	@Tool(description = """
			离线模式：提交 heap dump 的一个分块（Base64）。uploadId 为空时创建新上传会话并使用 chunkTotal 作为总分块数；\
			后续调用沿用返回的 uploadId。索引为 0..chunkTotal-1。\
			全部 chunk 提交后使用 finalizeOfflineHeapDump 校验 SHA-256 与大小；将返回的绝对路径写入 OfflineBundleDraft.heapDumpAbsolutePath。""")
	public HeapDumpChunkSubmissionResult submitOfflineHeapDumpChunk(
			@ToolParam(description = "已有上传 id；留空则创建新上传。") String uploadId,
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
			离线模式：在完成全部分块后校验并合并为单个 .hprof 文件。\
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
			离线模式：从导入材料生成与在线模式相同结构的 TuningAdviceReport（含 formattedSummary）。\
			需先通过校验或声明降级（proceedWithMissingRequired）。\
			若草稿包含类直方图、线程栈或堆路径，须提供非空 confirmationToken（与 collectMemoryGcEvidence / generateTuningAdvice 特权语义一致）。""")
	public TuningAdviceReport generateOfflineTuningAdvice(
			@ToolParam(description = "可选源码根与包名，用于热点关联。") CodeContextSummary codeContextSummary,
			@ToolParam(description = "完整 OfflineBundleDraft（heap 路径可为 finalizeOfflineHeapDump 的结果）。") OfflineBundleDraft draft,
			@ToolParam(description = "环境标签，如 prod / local。") String environment,
			@ToolParam(description = "优化目标描述。") String optimizationGoal,
			@ToolParam(description = "用户同意使用导入的诊断材料时的 token；无直方图/线程/堆时可空。") String confirmationToken,
			@ToolParam(description = "必选缺失时是否仍生成（降级）。") boolean proceedWithMissingRequired) {
		environment = environment == null ? "" : environment;
		optimizationGoal = optimizationGoal == null ? "" : optimizationGoal;
		confirmationToken = confirmationToken == null ? "" : confirmationToken;
		CodeContextSummary ctx = codeContextSummary == null ? CodeContextSummary.empty() : codeContextSummary;
		return offlineAnalysisService.generateOfflineAdvice(draft, ctx, environment, optimizationGoal,
				confirmationToken, proceedWithMissingRequired);
	}

}
