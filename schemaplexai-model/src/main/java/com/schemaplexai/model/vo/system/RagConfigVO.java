package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 配置视图对象
 */
@Data
public class RagConfigVO {

    private String tenantId;
    private Boolean enabled;

    // 向量模型配置
    private String embeddingSource;
    private String builtinEmbeddingModelId;
    private String builtinEmbeddingModelName;
    private Integer builtinEmbeddingDimension;
    private String vectorModelId;
    private String vectorModelName;
    private String provider;
    private String modelId;

    // 通用配置
    private String collectionName;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer retrievalTopK;
    private Double retrievalMinScore;
    private Integer embeddingDimension;
    private Integer maxQuotaTokens;
    private Boolean textCleaningEnabled;

    // Reranker 配置
    private Boolean rerankerEnabled;
    private String rerankerSource;
    private String builtinScoringModelId;
    private String builtinScoringModelName;
    private Integer rerankerTopN;
    private Double rerankerMinScore;

    // ===== v2：摄入与解析 =====
    private Integer maxFileSizeMb;
    private Boolean ingestCodeEnabled;
    private Boolean ingestExcelEnabled;
    private String ocrMode;
    private String ocrModelId;
    private String ocrModelName;
    private Integer ocrMaxConcurrency;
    private Integer ocrMinTextChars;

    // ===== v2：切分策略 =====
    private Boolean sectionSplitEnabled;
    private Boolean codeAstSplitEnabled;
    private Boolean tableBlockSplitEnabled;
    private Boolean chunkAnchorEnabled;

    // ===== v2：索引与召回 =====
    private Boolean bm25Enabled;
    private Integer bm25TopK;
    private Integer vectorTopK;
    private Boolean prefilterRequired;

    // ===== v2：融合与重排 =====
    private Integer rrfK;
    private Integer fusionTopN;
    private String rerankPolicy;
    private Integer rerankTimeoutMs;
    private Integer maxFilesPerContext;
    private Boolean entityOverlapFilterEnabled;
    private Integer contextTokenBudget;
    private Boolean keywordFastPathEnabled;

    // ===== v2：检索缓存 =====
    private Boolean retrievalCacheEnabled;
    private Integer retrievalCacheTtlSeconds;

    // ===== v2：生成与合规 =====
    private Boolean citationRequired;
    private Boolean hitlOnLowFaithfulness;
    private Double faithfulnessThreshold;

    // ===== v2：评测 =====
    private Integer evalRecallK;

    private LocalDateTime updatedAt;
}
