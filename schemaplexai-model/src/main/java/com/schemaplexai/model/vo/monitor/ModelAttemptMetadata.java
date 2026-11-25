package com.schemaplexai.model.vo.monitor;

/** 模型请求的供应商计量明细，缺失字段表示供应商未提供相关信息。 */
public record ModelAttemptMetadata(
        int round,
        String phase,
        int attempt,
        String providerResponseId,
        boolean inputEstimated,
        boolean outputEstimated,
        Integer cachedInputTokens,
        Integer cacheWriteTokens,
        Integer reasoningTokens
) {}
