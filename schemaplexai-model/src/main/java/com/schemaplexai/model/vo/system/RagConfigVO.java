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
    private String vectorModelId;
    private String vectorModelName;
    private String provider;
    private String modelId;
    private String collectionName;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer retrievalTopK;
    private Double retrievalMinScore;
    private Integer embeddingDimension;
    private Integer maxQuotaTokens;
    private Boolean textCleaningEnabled;
    private LocalDateTime updatedAt;
}
