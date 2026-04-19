package com.schemaplexai.service.rag;

import lombok.Builder;
import lombok.Getter;

/**
 * RAG 运行时配置
 */
@Getter
@Builder
public class RagRuntimeSettings {

    private final String tenantId;
    private final boolean enabled;

    // 向量模型来源
    private final String embeddingSource;
    private final String builtinEmbeddingModelId;

    // 外部 API 向量模型配置
    private final String vectorModelConfigId;
    private final String vectorModelName;
    private final String provider;
    private final String modelId;
    private final String apiKey;
    private final String baseUrl;

    // 通用配置
    private final String collectionName;
    private final int chunkSize;
    private final int chunkOverlap;
    private final int retrievalTopK;
    private final double retrievalMinScore;
    private final int embeddingDimension;
    private final int maxQuotaTokens;
    private final boolean textCleaningEnabled;

    // Reranker 配置
    private final boolean rerankerEnabled;
    private final String rerankerSource;
    private final int rerankerTopN;
    private final double rerankerMinScore;

    /** 是否使用内置 ONNX 向量模型 */
    public boolean isBuiltinEmbedding() {
        return "builtin".equalsIgnoreCase(embeddingSource);
    }

    /** 是否使用内置 ONNX Reranker */
    public boolean isBuiltinReranker() {
        return "builtin".equalsIgnoreCase(rerankerSource);
    }
}
