package com.schemaplexai.service.vector.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.vector.EmbeddingService;
import com.schemaplexai.service.vector.impl.InProcessEmbeddingServiceImpl.BuiltinEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容 Embedding 嵌入实现（支持 OpenAI / Doubao）
 *
 * <p>激活条件: {@code ai.embedding.provider=openai}（默认激活）
 * <p>若 API Key 为空，自动降级到 {@link InProcessEmbeddingServiceImpl}（本地 ONNX 模型）
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "ai.embedding", name = "provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiEmbeddingServiceImpl implements EmbeddingService {

    private static final int DIMENSION = 1536;
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final AiModelMapper aiModelMapper;
    private final RagConfigService ragConfigService;
    private final InProcessEmbeddingServiceImpl inProcessFallback;
    private final ConcurrentHashMap<String, EmbeddingModel> modelCache = new ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    public OpenAiEmbeddingServiceImpl(AiModelMapper aiModelMapper, RagConfigService ragConfigService) {
        this(aiModelMapper, ragConfigService, new InProcessEmbeddingServiceImpl());
    }

    OpenAiEmbeddingServiceImpl(AiModelMapper aiModelMapper,
                               RagConfigService ragConfigService,
                               InProcessEmbeddingServiceImpl inProcessFallback) {
        this.aiModelMapper = aiModelMapper;
        this.ragConfigService = ragConfigService;
        this.inProcessFallback = inProcessFallback;
    }

    @Override
    public float[] embed(String tenantId, String text) {
        RagRuntimeSettings settings = ragConfigService.resolveSettings(tenantId);
        if (settings != null && settings.isBuiltinEmbedding()) {
            BuiltinEmbeddingModel builtinModel = BuiltinEmbeddingModel.fromModelId(settings.getBuiltinEmbeddingModelId());
            return inProcessFallback.embed(text, builtinModel);
        }
        AiModelConfig config = resolveEmbeddingModelConfig(tenantId);
        boolean strictMode = settings != null
                && (settings.isEnabled() || StringUtils.hasText(settings.getVectorModelConfigId()));
        if (config == null
                || !StringUtils.hasText(config.getApiKey())
                || !StringUtils.hasText(config.getBaseUrl())
                || !StringUtils.hasText(config.getModelId())) {
            String message = String.format("未找到可用的 Embedding 模型配置: tenantId=%s", tenantId);
            if (strictMode) {
                throw new IllegalStateException(message);
            }
            log.warn("未找到可用的 Embedding 模型配置(apiKey/baseUrl/modelId)，降级到本地内嵌模型: tenantId={}", tenantId);
            return inProcessFallback.embed(tenantId, text);
        }
        try {
            if (isDoubaoMultimodalEmbedding(config)) {
                return callDoubaoMultimodalEmbedding(config, text);
            }
            EmbeddingModel embeddingModel = modelCache.computeIfAbsent(config.cacheKey(), k -> buildModel(config));
            Response<Embedding> response = embeddingModel.embed(text);
            return response.content().vector();
        } catch (Exception e) {
            if (strictMode) {
                throw new IllegalStateException(String.format("Embedding API 调用失败: tenantId=%s, modelId=%s, error=%s",
                        tenantId, config.getModelId(), e.getMessage()), e);
            }
            log.warn("Embedding API 调用失败，降级到本地内嵌模型: tenantId={}, error={}", tenantId, e.getMessage());
            return inProcessFallback.embed(tenantId, text);
        }
    }

    @Override
    public int dimension(String tenantId) {
        RagRuntimeSettings settings = ragConfigService.resolveSettings(tenantId);
        if (settings != null) {
            if (settings.isBuiltinEmbedding()) {
                return BuiltinEmbeddingModel.fromModelId(settings.getBuiltinEmbeddingModelId()).getDimension();
            }
            if (settings.getEmbeddingDimension() > 0) {
                return settings.getEmbeddingDimension();
            }
        }
        return DIMENSION;
    }

    private EmbeddingModel buildModel(AiModelConfig config) {
        log.info("创建 OpenAiEmbeddingModel 实例: provider={}, modelId={}", config.getProvider(), config.getModelId());
        String baseUrl = buildEmbeddingBaseUrl(config.getBaseUrl(), config.getProvider());
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
    private String buildEmbeddingBaseUrl(String baseUrl, String provider) {
        if (!StringUtils.hasText(baseUrl)) {
            return "https://api.openai.com/v1";
        }
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (trimmed.endsWith("/embeddings")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/embeddings".length());
        }
        String normalizedProvider = provider != null ? provider.toLowerCase(Locale.ROOT) : "";
        if ("doubao".equals(normalizedProvider) || trimmed.contains("/api/v3")) {
            return trimmed;
        }
        if (trimmed.endsWith("/v1")) {
            return trimmed;
        }
        return trimmed + "/v1";
    }

    private boolean isDoubaoMultimodalEmbedding(AiModelConfig config) {
        if (config == null) {
            return false;
        }
        String provider = config.getProvider() != null ? config.getProvider().toLowerCase(Locale.ROOT) : "";
        String baseUrl = config.getBaseUrl() != null ? config.getBaseUrl().toLowerCase(Locale.ROOT) : "";
        String modelId = config.getModelId() != null ? config.getModelId().toLowerCase(Locale.ROOT) : "";
        return "doubao".equals(provider)
                && (baseUrl.endsWith("/embeddings/multimodal") || modelId.contains("embedding-vision"));
    }

    private float[] callDoubaoMultimodalEmbedding(AiModelConfig config, String text) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(Math.max(config.getTimeoutSeconds(), 30), TimeUnit.SECONDS)
                .build();
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", config.getModelId(),
                "input", java.util.List.of(Map.of("type", "text", "text", text))
        ));
        Request request = new Request.Builder()
                .url(config.getBaseUrl())
                .post(RequestBody.create(requestBody, JSON_TYPE))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .build();
        try (okhttp3.Response response = client.newCall(request).execute()) {
            String body = readResponseBody(response.body());
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP_" + response.code() + ": " + body);
            }
            return parseEmbeddingVector(body);
        }
    }

    private String readResponseBody(ResponseBody responseBody) throws IOException {
        return responseBody != null ? responseBody.string() : "";
    }

    private float[] parseEmbeddingVector(String responseBody) throws IOException {
        com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(responseBody);
        com.fasterxml.jackson.databind.JsonNode embeddingNode = null;
        if (root.path("data").isArray() && root.path("data").size() > 0) {
            embeddingNode = root.path("data").get(0).path("embedding");
        } else if (root.path("data").has("embedding")) {
            embeddingNode = root.path("data").path("embedding");
        }
        if (embeddingNode == null || !embeddingNode.isArray() || embeddingNode.isEmpty()) {
            throw new IllegalStateException("Embedding 响应中未找到向量数据");
        }
        float[] vector = new float[embeddingNode.size()];
        for (int i = 0; i < embeddingNode.size(); i++) {
            vector[i] = embeddingNode.get(i).floatValue();
        }
        return vector;
    }

    /**
     * 从 sf_ai_model 解析可用的 Embedding 配置
     * 选择顺序:
     * 1) 租户 RAG 配置绑定的向量模型
     * 2) active + provider in(openai,doubao) + useCase 包含 embedding/向量
     * 3) active + provider in(openai,doubao) + modelId 以 text-embedding/doubao-embedding 开头
     * 4) active + provider in(openai,doubao) 第一条
     */
    private AiModelConfig resolveEmbeddingModelConfig(String tenantId) {
        RagRuntimeSettings settings = ragConfigService.resolveSettings(tenantId);
        if (settings != null
                && StringUtils.hasText(settings.getApiKey())
                && StringUtils.hasText(settings.getBaseUrl())
                && StringUtils.hasText(settings.getModelId())) {
            return AiModelConfig.builder()
                    .apiKey(settings.getApiKey())
                    .baseUrl(settings.getBaseUrl())
                    .modelId(settings.getModelId())
                    .provider(settings.getProvider())
                    .maxTokens(1024)
                    .maxQuotaTokens(settings.getMaxQuotaTokens() > 0 ? settings.getMaxQuotaTokens() : null)
                    .timeoutSeconds(60)
                    .build();
        }

        AiModel model = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                .and(w -> w.eq(AiModel::getProvider, "openai").or().eq(AiModel::getProvider, "doubao"))
                .and(w -> w.like(AiModel::getUseCase, "embedding")
                        .or().like(AiModel::getUseCase, "向量"))
                .orderByAsc(AiModel::getCreatedAt)
                .last("LIMIT 1"));
        if (model == null) {
            model = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                    .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                    .and(w -> w.eq(AiModel::getProvider, "openai").or().eq(AiModel::getProvider, "doubao"))
                    .and(w -> w.likeRight(AiModel::getModelId, "text-embedding")
                            .or().likeRight(AiModel::getModelId, "doubao-embedding"))
                    .orderByAsc(AiModel::getCreatedAt)
                    .last("LIMIT 1"));
        }
        if (model == null) {
            model = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                    .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                    .and(w -> w.eq(AiModel::getProvider, "openai").or().eq(AiModel::getProvider, "doubao"))
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
