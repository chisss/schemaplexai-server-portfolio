package com.schemaplexai.service.rag;

import lombok.Builder;
import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * RAG 运行时配置（v2）。
 *
 * <p>由 {@link RagConfigService#resolveSettings(String)} 按租户解析，供摄入、索引、检索
 * 三侧共享。字段全部为基本类型/字符串，避免下游再加空判断。
 */
@Getter
@Builder(toBuilder = true)
public class RagRuntimeSettings {

    private final String tenantId;
    private final boolean enabled;

    // ── 向量模型来源 ──
    private final String embeddingSource;
    private final String builtinEmbeddingModelId;
    private final String vectorModelConfigId;
    private final String vectorModelName;
    private final String provider;
    private final String modelId;
    private final String apiKey;
    private final String baseUrl;

    // ── 通用 ──
    private final String collectionName;
    private final int chunkSize;
    private final int chunkOverlap;
    private final int retrievalTopK;
    private final double retrievalMinScore;
    private final int embeddingDimension;
    private final int maxQuotaTokens;
    private final boolean textCleaningEnabled;

    // ── Reranker ──
    private final boolean rerankerEnabled;
    private final String rerankerSource;
    private final int rerankerTopN;
    private final double rerankerMinScore;

    // ── v2 摄入与解析 ──
    private final int maxFileSizeMb;
    private final boolean ingestCodeEnabled;
    private final boolean ingestExcelEnabled;
    private final String ocrMode;
    private final String ocrModelConfigId;
    private final String ocrModelName;
    private final int ocrMaxConcurrency;
    private final int ocrMinTextChars;

    // ── v2 切分策略 ──
    private final boolean sectionSplitEnabled;
    private final boolean codeAstSplitEnabled;
    private final boolean tableBlockSplitEnabled;
    private final boolean chunkAnchorEnabled;

    // ── v2 索引与召回 ──
    private final boolean bm25Enabled;
    private final int bm25TopK;
    private final int vectorTopK;
    private final boolean prefilterRequired;

    // ── v2 融合与重排 ──
    private final int rrfK;
    private final int fusionTopN;
    private final String rerankPolicy;
    private final int rerankTimeoutMs;
    private final int maxFilesPerContext;
    private final boolean entityOverlapFilterEnabled;
    private final int contextTokenBudget;
    private final boolean keywordFastPathEnabled;

    // ── v2 检索缓存 ──
    private final boolean retrievalCacheEnabled;
    private final int retrievalCacheTtlSeconds;

    // ── v2 生成与合规 ──
    private final boolean citationRequired;
    private final boolean hitlOnLowFaithfulness;
    private final double faithfulnessThreshold;

    // ── v2 评测 ──
    private final int evalRecallK;

    /** 是否使用内置 ONNX 向量模型 */
    public boolean isBuiltinEmbedding() {
        return "builtin".equalsIgnoreCase(embeddingSource);
    }

    /** 是否使用内置 ONNX Reranker */
    public boolean isBuiltinReranker() {
        return "builtin".equalsIgnoreCase(rerankerSource);
    }

    /** 精排总开关：Reranker 打开且策略不为 never 才生效 */
    public boolean isRerankActive() {
        return rerankerEnabled && !RagDefaults.RERANK_POLICY_NEVER.equalsIgnoreCase(rerankPolicy);
    }

    public boolean isRerankAlways() {
        return isRerankActive() && RagDefaults.RERANK_POLICY_ALWAYS.equalsIgnoreCase(rerankPolicy);
    }

    public boolean isRerankAuto() {
        return isRerankActive() && RagDefaults.RERANK_POLICY_AUTO.equalsIgnoreCase(rerankPolicy);
    }

    /** OCR 是否可用（disabled 时扫描件直接判失败） */
    public boolean isOcrEnabled() {
        return StringUtils.hasText(ocrMode)
                && !RagDefaults.OCR_MODE_DISABLED.equalsIgnoreCase(ocrMode);
    }

    /** 是否允许外部 OCR API 降级 */
    public boolean isOcrApiAllowed() {
        return RagDefaults.OCR_MODE_API.equalsIgnoreCase(ocrMode);
    }

    /**
     * 检索版本指纹：凡影响「召回结果集合」的配置变更都应改变该指纹，
     * 用于让检索缓存自然失效，避免改完配置还命中旧结果。
     */
    public String retrievalVersion() {
        return String.join("|",
                collectionName == null ? "" : collectionName,
                embeddingSource == null ? "" : embeddingSource.toLowerCase(Locale.ROOT),
                isBuiltinEmbedding() ? builtinEmbeddingModelId : modelId,
                String.valueOf(embeddingDimension),
                String.valueOf(chunkSize),
                String.valueOf(chunkOverlap),
                sectionSplitEnabled ? "1" : "0",
                codeAstSplitEnabled ? "1" : "0",
                tableBlockSplitEnabled ? "1" : "0",
                bm25Enabled ? "1" : "0");
    }
}
