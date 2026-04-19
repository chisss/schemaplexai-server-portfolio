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

    private LocalDateTime updatedAt;
}
