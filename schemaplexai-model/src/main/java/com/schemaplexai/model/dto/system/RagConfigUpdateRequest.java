package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
}
