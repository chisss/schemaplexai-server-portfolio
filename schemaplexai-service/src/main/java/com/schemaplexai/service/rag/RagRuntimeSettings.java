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
    private final String vectorModelConfigId;
    private final String vectorModelName;
    private final String provider;
    private final String modelId;
    private final String apiKey;
    private final String baseUrl;
    private final String collectionName;
    private final int chunkSize;
    private final int chunkOverlap;
    private final int retrievalTopK;
    private final double retrievalMinScore;
    private final int embeddingDimension;
    private final int maxQuotaTokens;
    private final boolean textCleaningEnabled;
}
