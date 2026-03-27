package com.schemaplexai.service.vector.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.vector.EmbeddingService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAI text-embedding-3-small 嵌入实现（基于 LangChain4j，维度 1536）
 *
 * <p>激活条件: {@code ai.embedding.provider=openai}（默认激活）
 * <p>若 API Key 为空，自动降级到 {@link MockEmbeddingServiceImpl}
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "ai.embedding", name = "provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiEmbeddingServiceImpl implements EmbeddingService {

    private static final int DIMENSION = 1536;

    private final AiModelMapper aiModelMapper;
    private final MockEmbeddingServiceImpl mockFallback = new MockEmbeddingServiceImpl();
    private final ConcurrentHashMap<String, EmbeddingModel> modelCache = new ConcurrentHashMap<>();

    public OpenAiEmbeddingServiceImpl(AiModelMapper aiModelMapper) {
        this.aiModelMapper = aiModelMapper;
    }

    @Override
    public float[] embed(String text) {
        AiModelConfig config = resolveEmbeddingModelConfig();
        if (config == null || !StringUtils.hasText(config.getApiKey())) {
            log.warn("未找到可用的 Embedding 模型配置(apiKey/baseUrl/modelId)，降级到 Mock 实现");
            return mockFallback.embed(text);
        }
        try {
            EmbeddingModel embeddingModel = modelCache.computeIfAbsent(config.cacheKey(), k -> buildModel(config));
            Response<Embedding> response = embeddingModel.embed(text);
            return response.content().vector();
        } catch (Exception e) {
            log.warn("Embedding API 调用失败，降级到 Mock 实现: {}", e.getMessage());
            return mockFallback.embed(text);
        }
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    private EmbeddingModel buildModel(AiModelConfig config) {
        log.info("创建 OpenAiEmbeddingModel 实例: modelId={}", config.getModelId());
        String baseUrl = buildEmbeddingBaseUrl(config.getBaseUrl());
        return OpenAiEmbeddingModel.builder()
                .apiKey(config.getApiKey())
                .baseUrl(baseUrl)
                .modelName(config.getModelId())
                .timeout(Duration.ofSeconds(Math.max(config.getTimeoutSeconds(), 30)))
                .build();
    }

    /**
     * 确保 baseUrl 格式为 LangChain4j 所需的 v1 前缀路径，如 https://api.openai.com/v1
     */
    private String buildEmbeddingBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return "https://api.openai.com/v1";
        }
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (trimmed.endsWith("/v1")) {
            return trimmed;
        }
        return trimmed + "/v1";
    }

    /**
     * 从 sf_ai_model 解析可用的 Embedding 配置
     * 选择顺序:
     * 1) active + provider=openai + useCase 包含 embedding/向量
     * 2) active + provider=openai + modelId 以 text-embedding 开头
     * 3) active + provider=openai 第一条
     */
    private AiModelConfig resolveEmbeddingModelConfig() {
        AiModel model = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                .eq(AiModel::getProvider, "openai")
                .and(w -> w.like(AiModel::getUseCase, "embedding")
                        .or().like(AiModel::getUseCase, "向量"))
                .orderByAsc(AiModel::getCreatedAt)
                .last("LIMIT 1"));
        if (model == null) {
            model = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                    .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                    .eq(AiModel::getProvider, "openai")
                    .likeRight(AiModel::getModelId, "text-embedding")
                    .orderByAsc(AiModel::getCreatedAt)
                    .last("LIMIT 1"));
        }
        if (model == null) {
            model = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                    .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                    .eq(AiModel::getProvider, "openai")
                    .orderByAsc(AiModel::getCreatedAt)
                    .last("LIMIT 1"));
        }
        if (model == null) {
            return null;
        }
        AiModelConfig config = AiModelConfig.from(model);
        if (!StringUtils.hasText(config.getBaseUrl()) || !StringUtils.hasText(config.getModelId())) {
            return null;
        }
        return config;
    }
}
