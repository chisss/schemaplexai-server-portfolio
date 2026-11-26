package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 运行诊断视图。
 *
 * <p>用途：让运维/管理员在配置台上直接看到"配置是否真的生效"，
 * 而不是改完参数后靠猜。字段全部来自运行时解析结果（{@code RagRuntimeSettings}）与索引实测值。
 */
@Data
public class RagDiagnosticsVO {

    private String tenantId;
    private Boolean enabled;
    private LocalDateTime updatedAt;

    // 向量索引
    private String embeddingSource;
    private String vectorModelName;
    private String collectionName;
    private Integer embeddingDimension;
    private Integer vectorTopK;

    // BM25 索引
    private Boolean bm25Enabled;
    private Boolean bm25Available;
    private Long bm25ChunkCount;
    private Integer bm25TopK;

    // 切分
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Boolean sectionSplitEnabled;
    private Boolean codeAstSplitEnabled;
    private Boolean tableBlockSplitEnabled;
    private Boolean chunkAnchorEnabled;

    // 融合与精排
    private Integer fusionTopN;
    private Integer rrfK;
    private Integer retrievalTopK;
    private Boolean rerankEnabled;
    private String rerankPolicy;
    private Boolean rerankActive;
    private Integer rerankerTopN;
    private Integer rerankTimeoutMs;

    // 上下文控制
    private Integer maxFilesPerContext;
    private Boolean entityOverlapFilterEnabled;
    private Integer contextTokenBudget;

    // 缓存
    private Boolean retrievalCacheEnabled;
    private Integer retrievalCacheTtlSeconds;
    private Integer retrievalCacheEntries;

    // 摄入
    private String ocrMode;
    private String ocrModelName;
    private Integer ocrMaxConcurrency;
    private Integer maxFileSizeMb;

    // 合规与评测
    private Boolean citationRequired;
    private Boolean hitlOnLowFaithfulness;
    private Double faithfulnessThreshold;
    private Integer evalRecallK;

    /** 检索版本指纹：配置变更后会变，可用于确认缓存已失效 */
    private String retrievalVersion;
}
