package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 更新 RAG 配置请求
 */
@Data
public class RagConfigUpdateRequest {

    private Boolean enabled;

    /** 向量模型来源: builtin / api */
    private String embeddingSource;

    /** 内置 ONNX 向量模型标识 */
    private String builtinEmbeddingModelId;

    /** 外部向量模型 ID */
    private String vectorModelId;

    private String collectionName;

    @Min(value = 100, message = "分块大小不能小于 100")
    @Max(value = 10000, message = "分块大小不能超过 10000")
    private Integer chunkSize;

    @Min(value = 0, message = "分块重叠不能小于 0")
    @Max(value = 5000, message = "分块重叠不能超过 5000")
    private Integer chunkOverlap;

    @Min(value = 1, message = "检索 TopK 不能小于 1")
    @Max(value = 50, message = "检索 TopK 不能超过 50")
    private Integer retrievalTopK;

    @Min(value = 0, message = "最小分数不能小于 0")
    @Max(value = 1, message = "最小分数不能大于 1")
    private Double retrievalMinScore;

    @Min(value = 128, message = "向量维度不能小于 128")
    @Max(value = 8192, message = "向量维度不能超过 8192")
    private Integer embeddingDimension;

    private Boolean textCleaningEnabled;

    private Boolean rerankerEnabled;

    /** Reranker 模型来源: builtin / api */
    private String rerankerSource;

    @Min(value = 1, message = "Reranker TopN 不能小于 1")
    @Max(value = 50, message = "Reranker TopN 不能超过 50")
    private Integer rerankerTopN;

    @Min(value = 0, message = "Reranker 最小评分不能小于 0")
    @Max(value = 1, message = "Reranker 最小评分不能大于 1")
    private Double rerankerMinScore;

    // ===== v2：摄入与解析 =====

    @Min(value = 1, message = "单文件体积上限不能小于 1MB")
    @Max(value = 1024, message = "单文件体积上限不能超过 1024MB")
    private Integer maxFileSizeMb;

    private Boolean ingestCodeEnabled;

    private Boolean ingestExcelEnabled;

    /** multimodal / api / disabled */
    @Pattern(regexp = "multimodal|api|disabled", message = "OCR 模式仅支持 multimodal/api/disabled")
    private String ocrMode;

    private String ocrModelId;

    @Min(value = 1, message = "OCR 并发上限不能小于 1")
    @Max(value = 32, message = "OCR 并发上限不能超过 32")
    private Integer ocrMaxConcurrency;

    @Min(value = 1, message = "OCR 最小字符数不能小于 1")
    @Max(value = 2000, message = "OCR 最小字符数不能超过 2000")
    private Integer ocrMinTextChars;

    // ===== v2：切分策略 =====

    private Boolean sectionSplitEnabled;

    private Boolean codeAstSplitEnabled;

    private Boolean tableBlockSplitEnabled;

    private Boolean chunkAnchorEnabled;

    // ===== v2：索引与召回 =====

    private Boolean bm25Enabled;

    @Min(value = 1, message = "BM25 召回数不能小于 1")
    @Max(value = 200, message = "BM25 召回数不能超过 200")
    private Integer bm25TopK;

    @Min(value = 1, message = "向量召回数不能小于 1")
    @Max(value = 200, message = "向量召回数不能超过 200")
    private Integer vectorTopK;

    private Boolean prefilterRequired;

    // ===== v2：融合与重排 =====

    @Min(value = 1, message = "RRF k 不能小于 1")
    @Max(value = 1000, message = "RRF k 不能超过 1000")
    private Integer rrfK;

    @Min(value = 1, message = "融合候选池不能小于 1")
    @Max(value = 200, message = "融合候选池不能超过 200")
    private Integer fusionTopN;

    /** always / auto / never */
    @Pattern(regexp = "always|auto|never", message = "Rerank 策略仅支持 always/auto/never")
    private String rerankPolicy;

    @Min(value = 100, message = "Rerank 超时不能小于 100ms")
    @Max(value = 30000, message = "Rerank 超时不能超过 30000ms")
    private Integer rerankTimeoutMs;

    @Min(value = 1, message = "文件数量上限不能小于 1")
    @Max(value = 20, message = "文件数量上限不能超过 20")
    private Integer maxFilesPerContext;

    private Boolean entityOverlapFilterEnabled;

    @Min(value = 256, message = "上下文 Token 上限不能小于 256")
    @Max(value = 128000, message = "上下文 Token 上限不能超过 128000")
    private Integer contextTokenBudget;

    private Boolean keywordFastPathEnabled;

    // ===== v2：检索缓存 =====

    private Boolean retrievalCacheEnabled;

    @Min(value = 0, message = "缓存 TTL 不能小于 0")
    @Max(value = 86400, message = "缓存 TTL 不能超过 86400 秒")
    private Integer retrievalCacheTtlSeconds;

    // ===== v2：生成与合规 =====

    private Boolean citationRequired;

    private Boolean hitlOnLowFaithfulness;

    @Min(value = 0, message = "忠实度阈值不能小于 0")
    @Max(value = 1, message = "忠实度阈值不能大于 1")
    private Double faithfulnessThreshold;

    @Min(value = 1, message = "Recall@K 的 K 不能小于 1")
    @Max(value = 200, message = "Recall@K 的 K 不能超过 200")
    private Integer evalRecallK;
}
