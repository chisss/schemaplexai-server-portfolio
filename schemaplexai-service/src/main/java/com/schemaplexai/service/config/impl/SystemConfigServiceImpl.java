package com.schemaplexai.service.config.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.ModelProviderEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.AiModelRouteMapper;
import com.schemaplexai.dao.mapper.TeamTemplateMapper;
import com.schemaplexai.model.dto.system.AiModelCreateRequest;
import com.schemaplexai.model.dto.system.AiModelRouteCreateRequest;
import com.schemaplexai.model.dto.system.AiModelRouteUpdateRequest;
import com.schemaplexai.model.dto.system.AiModelUpdateRequest;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.AiModelRoute;
import com.schemaplexai.model.entity.TeamTemplate;
import com.schemaplexai.model.vo.system.AiModelRouteVO;
import com.schemaplexai.model.vo.system.ConnectivityTestResultVO;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.config.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 系统配置服务实现 - AI模型配置管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {

    private static final int DOUBAO_EMBEDDING_VISION_251215_MAX_QUOTA = 500_000;

    private final AiModelMapper aiModelMapper;
    private final AiModelRouteMapper aiModelRouteMapper;
    private final TeamTemplateMapper teamTemplateMapper;
    private final EntityValidator entityValidator;

    private static final String CONNECTIVITY_PROMPT = "Reply with exactly: CONNECTIVITY_OK";
    private static final String EMBEDDING_CONNECTIVITY_INPUT = "SchemaPlexAI embedding connectivity check";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    // ==================== AI模型 CRUD ====================

    @Override
    public List<AiModel> listAiModels() {
        return aiModelMapper.selectList(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                        .orderByAsc(AiModel::getCreatedAt)
        );
    }

    @Override
    public AiModel getAiModelById(String id) {
        AiModel model = aiModelMapper.selectById(id);
        if (model == null) {
            throw new BusinessException(ResultCode.CONFIG_NOT_FOUND);
        }
        return model;
    }

    @Override
    public List<AiModel> listAllAiModels() {
        return aiModelMapper.selectList(
                new LambdaQueryWrapper<AiModel>()
                        .orderByAsc(AiModel::getCreatedAt)
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModel createAiModel(AiModelCreateRequest request) {
        // 校验名称唯一性
        entityValidator.checkUnique(aiModelMapper, AiModel::getName,
                request.getName(), ResultCode.MODEL_NAME_DUPLICATE);
        validateModelQuota(request.getProvider(), request.getUseCase(), request.getModelId(), request.getMaxQuotaTokens());

        AiModel model = new AiModel();
        model.setName(request.getName());
        model.setProvider(request.getProvider());
        model.setProviderCode(request.getProviderCode());
        model.setUseCase(request.getUseCase());
        model.setModelId(request.getModelId());
        // apiKey 使用 Base64 编码存储（后续替换为加密）
        model.setApiKeyEncrypted(Base64.getEncoder().encodeToString(
                request.getApiKey().getBytes()));
        model.setBaseUrl(request.getBaseUrl());
        model.setDefaultParams(request.getDefaultParams());
        model.setInputPrice(request.getInputPrice());
        model.setOutputPrice(request.getOutputPrice());
        // 设置默认值
        model.setTimeoutSeconds(request.getTimeoutSeconds() != null
                ? request.getTimeoutSeconds() : 30);
        model.setRetryCount(request.getRetryCount() != null
                ? request.getRetryCount() : 3);
        model.setRetryIntervalSeconds(request.getRetryIntervalSeconds() != null
                ? request.getRetryIntervalSeconds() : 5);
        model.setMaxTokens(request.getMaxTokens() != null
                ? request.getMaxTokens() : 4096);
        model.setMaxQuotaTokens(request.getMaxQuotaTokens());
        model.setStatus(CommonConstant.STATUS_ACTIVE);

        aiModelMapper.insert(model);
        log.info("创建AI模型成功: modelId={}, name={}", model.getId(), model.getName());
        return model;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModel updateAiModel(String id, AiModelUpdateRequest request) {
        AiModel model = entityValidator.requireExists(aiModelMapper, id, ResultCode.CONFIG_NOT_FOUND);

        // 名称变更时校验唯一性
        if (StringUtils.hasText(request.getName()) && !request.getName().equals(model.getName())) {
            entityValidator.checkUnique(aiModelMapper, AiModel::getName,
                    request.getName(), ResultCode.MODEL_NAME_DUPLICATE);
        }

        AiModel updateEntity = new AiModel();
        updateEntity.setId(id);
        String resolvedProvider = StringUtils.hasText(request.getProvider()) ? request.getProvider() : model.getProvider();
        String resolvedUseCase = request.getUseCase() != null ? request.getUseCase() : model.getUseCase();
        String resolvedModelId = StringUtils.hasText(request.getModelId()) ? request.getModelId() : model.getModelId();
        Integer resolvedMaxQuotaTokens = request.getMaxQuotaTokens() != null ? request.getMaxQuotaTokens() : model.getMaxQuotaTokens();
        validateModelQuota(resolvedProvider, resolvedUseCase, resolvedModelId, resolvedMaxQuotaTokens);
        if (StringUtils.hasText(request.getName())) {
            updateEntity.setName(request.getName());
        }
        if (StringUtils.hasText(request.getProvider())) {
            updateEntity.setProvider(request.getProvider());
        }
        if (StringUtils.hasText(request.getProviderCode())) {
            updateEntity.setProviderCode(request.getProviderCode());
        }
        if (request.getUseCase() != null) {
            updateEntity.setUseCase(request.getUseCase());
        }
        if (StringUtils.hasText(request.getModelId())) {
            updateEntity.setModelId(request.getModelId());
        }
        // apiKey 不传则不更新
        if (StringUtils.hasText(request.getApiKey())) {
            updateEntity.setApiKeyEncrypted(Base64.getEncoder().encodeToString(
                    request.getApiKey().getBytes()));
        }
        if (request.getBaseUrl() != null) {
            updateEntity.setBaseUrl(request.getBaseUrl());
        }
        if (request.getDefaultParams() != null) {
            updateEntity.setDefaultParams(request.getDefaultParams());
        }
        if (request.getInputPrice() != null) {
            updateEntity.setInputPrice(request.getInputPrice());
        }
        if (request.getOutputPrice() != null) {
            updateEntity.setOutputPrice(request.getOutputPrice());
        }
        if (request.getTimeoutSeconds() != null) {
            updateEntity.setTimeoutSeconds(request.getTimeoutSeconds());
        }
        if (request.getRetryCount() != null) {
            updateEntity.setRetryCount(request.getRetryCount());
        }
        if (request.getRetryIntervalSeconds() != null) {
            updateEntity.setRetryIntervalSeconds(request.getRetryIntervalSeconds());
        }
        if (request.getMaxTokens() != null) {
            updateEntity.setMaxTokens(request.getMaxTokens());
        }
        if (request.getMaxQuotaTokens() != null) {
            updateEntity.setMaxQuotaTokens(request.getMaxQuotaTokens());
        }
        if (StringUtils.hasText(request.getStatus())) {
            updateEntity.setStatus(request.getStatus());
        }

        aiModelMapper.updateById(updateEntity);
        log.info("更新AI模型成功: modelId={}", id);
        return aiModelMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAiModel(String id) {
        entityValidator.requireExists(aiModelMapper, id, ResultCode.CONFIG_NOT_FOUND);
        aiModelMapper.deleteById(id);
        log.info("删除AI模型成功: modelId={}", id);
    }

    // ==================== 路由规则 CRUD ====================

    @Override
    public List<AiModelRouteVO> listRoutes() {
        List<AiModelRoute> routes = aiModelRouteMapper.selectList(
                new LambdaQueryWrapper<AiModelRoute>()
                        .orderByAsc(AiModelRoute::getPriority)
        );
        // 批量获取模型名称映射
        Map<String, String> modelNameMap = buildModelNameMap();
        return routes.stream()
                .map(route -> convertToRouteVO(route, modelNameMap))
                .toList();
    }

    @Override
    public AiModelRouteVO getRouteById(String id) {
        AiModelRoute route = entityValidator.requireExists(
                aiModelRouteMapper, id, ResultCode.ROUTE_NOT_FOUND);
        Map<String, String> modelNameMap = buildModelNameMap();
        return convertToRouteVO(route, modelNameMap);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModelRouteVO createRoute(AiModelRouteCreateRequest request) {
        // 校验路由名称唯一性
        entityValidator.checkUnique(aiModelRouteMapper, AiModelRoute::getName,
                request.getName(), ResultCode.ROUTE_NAME_DUPLICATE);
        validateRouteModelsUseCase(request.getPrimaryModelId(), request.getSecondaryModelId(), request.getTertiaryModelId());

        AiModelRoute route = new AiModelRoute();
        route.setName(request.getName());
        route.setPriority(request.getPriority() != null ? request.getPriority() : 100);
        route.setMatchDimension(request.getMatchDimension());
        route.setMatchCondition(request.getMatchCondition());
        route.setPrimaryModelId(request.getPrimaryModelId());
        route.setSecondaryModelId(request.getSecondaryModelId());
        route.setTertiaryModelId(request.getTertiaryModelId());
        route.setPrimaryTriggerCondition(request.getPrimaryTriggerCondition());
        route.setSecondaryTriggerCondition(request.getSecondaryTriggerCondition());
        route.setDescription(request.getDescription());
        route.setStatus(CommonConstant.STATUS_ACTIVE);

        aiModelRouteMapper.insert(route);
        log.info("创建路由规则成功: routeId={}, name={}", route.getId(), route.getName());

        Map<String, String> modelNameMap = buildModelNameMap();
        return convertToRouteVO(route, modelNameMap);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModelRouteVO updateRoute(String id, AiModelRouteUpdateRequest request) {
        AiModelRoute route = entityValidator.requireExists(
                aiModelRouteMapper, id, ResultCode.ROUTE_NOT_FOUND);

        // 名称变更时校验唯一性
        if (StringUtils.hasText(request.getName()) && !request.getName().equals(route.getName())) {
            entityValidator.checkUnique(aiModelRouteMapper, AiModelRoute::getName,
                    request.getName(), ResultCode.ROUTE_NAME_DUPLICATE);
        }

        AiModelRoute updateEntity = new AiModelRoute();
        updateEntity.setId(id);
        if (StringUtils.hasText(request.getName())) {
            updateEntity.setName(request.getName());
        }
        if (request.getPriority() != null) {
            updateEntity.setPriority(request.getPriority());
        }
        if (StringUtils.hasText(request.getMatchDimension())) {
            updateEntity.setMatchDimension(request.getMatchDimension());
        }
        if (request.getMatchCondition() != null) {
            updateEntity.setMatchCondition(request.getMatchCondition());
        }
        if (StringUtils.hasText(request.getPrimaryModelId())) {
            updateEntity.setPrimaryModelId(request.getPrimaryModelId());
        }
        if (request.getSecondaryModelId() != null) {
            updateEntity.setSecondaryModelId(request.getSecondaryModelId());
        }
        if (request.getTertiaryModelId() != null) {
            updateEntity.setTertiaryModelId(request.getTertiaryModelId());
        }
        if (request.getPrimaryTriggerCondition() != null) {
            updateEntity.setPrimaryTriggerCondition(request.getPrimaryTriggerCondition());
        }
        if (request.getSecondaryTriggerCondition() != null) {
            updateEntity.setSecondaryTriggerCondition(request.getSecondaryTriggerCondition());
        }
        if (request.getDescription() != null) {
            updateEntity.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getStatus())) {
            updateEntity.setStatus(request.getStatus());
        }

        validateRouteModelsUseCase(
                StringUtils.hasText(request.getPrimaryModelId()) ? request.getPrimaryModelId() : route.getPrimaryModelId(),
                request.getSecondaryModelId() != null ? request.getSecondaryModelId() : route.getSecondaryModelId(),
                request.getTertiaryModelId() != null ? request.getTertiaryModelId() : route.getTertiaryModelId()
        );

        aiModelRouteMapper.updateById(updateEntity);
        log.info("更新路由规则成功: routeId={}", id);

        AiModelRoute updated = aiModelRouteMapper.selectById(id);
        Map<String, String> modelNameMap = buildModelNameMap();
        return convertToRouteVO(updated, modelNameMap);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRoute(String id) {
        entityValidator.requireExists(aiModelRouteMapper, id, ResultCode.ROUTE_NOT_FOUND);
        aiModelRouteMapper.deleteById(id);
        log.info("删除路由规则成功: routeId={}", id);
    }

    // ==================== 团队模板 ====================

    @Override
    public List<TeamTemplate> listTeamTemplates() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        LambdaQueryWrapper<TeamTemplate> wrapper = new LambdaQueryWrapper<TeamTemplate>()
                .eq(TeamTemplate::getStatus, CommonConstant.STATUS_ACTIVE);

        if (StringUtils.hasText(tenantId)) {
            wrapper.and(w -> w.isNull(TeamTemplate::getTenantId).or().eq(TeamTemplate::getTenantId, tenantId));
        }

        return teamTemplateMapper.selectList(
                wrapper.orderByAsc(TeamTemplate::getCreatedAt)
        );
    }

    @Override
    public TeamTemplate getTeamTemplateByCode(String code) {
        String tenantId = SecurityUtil.getCurrentTenantId();
        LambdaQueryWrapper<TeamTemplate> wrapper = new LambdaQueryWrapper<TeamTemplate>()
                .eq(TeamTemplate::getCode, code)
                .eq(TeamTemplate::getStatus, CommonConstant.STATUS_ACTIVE);

        if (StringUtils.hasText(tenantId)) {
            wrapper.and(w -> w.isNull(TeamTemplate::getTenantId).or().eq(TeamTemplate::getTenantId, tenantId));
        }

        TeamTemplate template = teamTemplateMapper.selectOne(
                wrapper
        );
        if (template == null) {
            throw new BusinessException(ResultCode.TEMPLATE_NOT_FOUND);
        }
        return template;
    }

    // ==================== 连通性测试 ====================

    @Override
    public ConnectivityTestResultVO testConnectivity(String modelId) {
        AiModel model = entityValidator.requireExists(aiModelMapper, modelId, ResultCode.CONFIG_NOT_FOUND);
        String provider = model.getProvider() != null ? model.getProvider().toLowerCase(Locale.ROOT) : "";
        String protocol = AiModelConfig.resolveProtocol(model);
        String apiKey = decodeApiKey(model.getApiKeyEncrypted());
        LocalDateTime testedAt = LocalDateTime.now();
        long start = System.currentTimeMillis();

        ConnectivityTestResultVO result;
        try {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();

            Request httpRequest = buildHttpRequest(model, provider, protocol, apiKey);
            try (Response response = client.newCall(httpRequest).execute()) {
                long latencyMs = System.currentTimeMillis() - start;
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    String errorMsg = response.code() + " " + response.message() + ": " + responseBody;
                    String errorCode = response.code() == 401 || response.code() == 403
                            ? "AUTH_ERROR" : "HTTP_" + response.code();
                    result = ConnectivityTestResultVO.builder()
                            .status("failed").latencyMs(latencyMs)
                            .errorCode(errorCode).errorMessage(trimError(errorMsg))
                            .testedAt(testedAt).build();
                } else {
                    result = parseConnectivityResult(responseBody, provider, protocol, model, latencyMs, testedAt);
                }
            }
        } catch (IOException ex) {
            long latencyMs = System.currentTimeMillis() - start;
            boolean isTimeout = isTimeoutException(ex);
            result = ConnectivityTestResultVO.builder()
                    .status(isTimeout ? "timeout" : "failed")
                    .latencyMs(latencyMs)
                    .errorCode(isTimeout ? "TIMEOUT" : "NETWORK_ERROR")
                    .errorMessage(trimError(ex.getMessage()))
                    .testedAt(testedAt).build();
        } catch (Exception ex) {
            long latencyMs = System.currentTimeMillis() - start;
            log.error("连通性测试异常: modelId={}", modelId, ex);
            result = ConnectivityTestResultVO.builder()
                    .status("failed").latencyMs(latencyMs)
                    .errorCode("INTERNAL_ERROR").errorMessage(trimError(ex.getMessage()))
                    .testedAt(testedAt).build();
        }

        saveConnectivityResult(modelId, result);
        return result;
    }

    private Request buildHttpRequest(AiModel model, String provider, String protocol, String apiKey) throws Exception {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("API Key不能为空");
        }
        String url = buildConnectivityUrl(model, provider, protocol, apiKey);
        String body = buildConnectivityPayload(model, provider, protocol);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON_TYPE))
                .header("Content-Type", "application/json");
        if (AiModelConfig.PROTOCOL_ANTHROPIC.equals(protocol)) {
            builder.header("x-api-key", apiKey).header("anthropic-version", "2026-01-01");
        } else if (!AiModelConfig.PROTOCOL_GEMINI.equals(protocol)) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private String buildConnectivityUrl(AiModel model, String provider, String protocol, String apiKey) {
        String baseUrl = AiModelConfig.normalizeBaseUrl(
                StringUtils.hasText(model.getBaseUrl()) ? model.getBaseUrl() : defaultBaseUrl(provider),
                provider,
                protocol
        );
        if (isEmbeddingModel(model)) {
            return buildEmbeddingConnectivityUrl(provider, baseUrl);
        }
        if (AiModelConfig.PROTOCOL_ANTHROPIC.equals(protocol)) {
            return baseUrl + "/messages";
        }
        if (AiModelConfig.PROTOCOL_GEMINI.equals(protocol)) {
            return baseUrl + "/v1beta/models/" + model.getModelId()
                    + ":generateContent?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        }
        return baseUrl + "/chat/completions";
    }

    private String defaultBaseUrl(String provider) {
        return switch (provider) {
            case "anthropic", "claude" -> "https://api.anthropic.com";
            case "gemini" -> "https://generativelanguage.googleapis.com";
            case "kimi"   -> "https://api.moonshot.cn";
            case "doubao" -> "https://ark.cn-beijing.volces.com/api/v3";
            default       -> "https://api.openai.com";
        };
    }

    private String buildConnectivityPayload(AiModel model, String provider, String protocol) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        if (isEmbeddingModel(model)) {
            if (isDoubaoMultimodalEmbedding(model, provider)) {
                return om.writeValueAsString(Map.of(
                        "model", model.getModelId(),
                        "input", List.of(Map.of("type", "text", "text", EMBEDDING_CONNECTIVITY_INPUT))
                ));
            }
            return om.writeValueAsString(Map.of(
                    "model", model.getModelId(),
                    "input", EMBEDDING_CONNECTIVITY_INPUT
            ));
        }
        if (AiModelConfig.PROTOCOL_ANTHROPIC.equals(protocol)) {
            return om.writeValueAsString(Map.of(
                    "model", model.getModelId(),
                    "max_tokens", 16,
                    "messages", List.of(Map.of("role", "user", "content", CONNECTIVITY_PROMPT))
            ));
        }
        if (AiModelConfig.PROTOCOL_GEMINI.equals(protocol)) {
            return om.writeValueAsString(Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", CONNECTIVITY_PROMPT))))
            ));
        }
        return om.writeValueAsString(Map.of(
                "model", model.getModelId(),
                "temperature", 0,
                "max_tokens", 16,
                "messages", List.of(Map.of("role", "user", "content", CONNECTIVITY_PROMPT))
        ));
    }

    private ConnectivityTestResultVO parseConnectivityResult(String responseBody, String provider, String protocol,
                                                             AiModel model, long latencyMs,
                                                             LocalDateTime testedAt) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = om.readTree(responseBody);
        String fallbackModel = model.getModelId();
        String modelName = fallbackModel;
        Integer tokenInput = null;
        Integer tokenOutput = null;
        if (isEmbeddingModel(model)) {
            modelName = root.path("model").asText(fallbackModel);
            com.fasterxml.jackson.databind.JsonNode usage = root.path("usage");
            tokenInput = readFirstInt(usage, "prompt_tokens", "input_tokens", "total_tokens");
        } else if (AiModelConfig.PROTOCOL_ANTHROPIC.equals(protocol)) {
            modelName = root.path("model").asText(fallbackModel);
            com.fasterxml.jackson.databind.JsonNode usage = root.path("usage");
            tokenInput = readInt(usage, "input_tokens");
            tokenOutput = readInt(usage, "output_tokens");
        } else if (AiModelConfig.PROTOCOL_GEMINI.equals(protocol)) {
            modelName = root.path("modelVersion").asText(fallbackModel);
            com.fasterxml.jackson.databind.JsonNode usage = root.path("usageMetadata");
            tokenInput = readInt(usage, "promptTokenCount");
            tokenOutput = readInt(usage, "candidatesTokenCount");
        } else {
            modelName = root.path("model").asText(fallbackModel);
            com.fasterxml.jackson.databind.JsonNode usage = root.path("usage");
            tokenInput = readInt(usage, "prompt_tokens");
            tokenOutput = readInt(usage, "completion_tokens");
        }
        return ConnectivityTestResultVO.builder()
                .status("success").latencyMs(latencyMs)
                .modelName(modelName).tokenInput(tokenInput).tokenOutput(tokenOutput)
                .testedAt(testedAt).build();
    }

    private Integer readFirstInt(com.fasterxml.jackson.databind.JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            Integer value = readInt(node, fieldName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void validateRouteModelsUseCase(String primaryModelId, String secondaryModelId, String tertiaryModelId) {
        List<String> modelIds = java.util.stream.Stream.of(primaryModelId, secondaryModelId, tertiaryModelId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (modelIds.isEmpty()) {
            return;
        }
        List<AiModel> models = aiModelMapper.selectBatchIds(modelIds);
        if (models.size() != modelIds.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "路由规则引用了不存在的模型");
        }
        List<String> normalizedUseCases = models.stream()
                .map(this::normalizeUseCase)
                .distinct()
                .toList();
        if (normalizedUseCases.size() > 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "路由规则仅允许同一 useCase 的模型组成降级链路");
        }
    }

    private String normalizeUseCase(AiModel model) {
        if (model == null || !StringUtils.hasText(model.getUseCase())) {
            return "__blank__";
        }
        return model.getUseCase().trim().toLowerCase(Locale.ROOT);
    }

    private boolean isEmbeddingModel(AiModel model) {
        return model != null && (isEmbeddingUseCase(model.getUseCase())
                || (StringUtils.hasText(model.getModelId())
                && model.getModelId().toLowerCase(Locale.ROOT).contains("embedding")));
    }

    private boolean isDoubaoMultimodalEmbedding(AiModel model, String provider) {
        if (model == null) {
            return false;
        }
        String normalizedProvider = provider != null ? provider.toLowerCase(Locale.ROOT) : "";
        String baseUrl = model.getBaseUrl() != null ? model.getBaseUrl().toLowerCase(Locale.ROOT) : "";
        String modelId = model.getModelId() != null ? model.getModelId().toLowerCase(Locale.ROOT) : "";
        return "doubao".equals(normalizedProvider)
                && (baseUrl.endsWith("/embeddings/multimodal") || modelId.contains("embedding-vision"));
    }

    private boolean isEmbeddingUseCase(String useCase) {
        return StringUtils.hasText(useCase)
                && (useCase.toLowerCase(Locale.ROOT).contains("embedding") || useCase.contains("向量"));
    }

    private String buildEmbeddingConnectivityUrl(String provider, String baseUrl) {
        if (baseUrl.endsWith("/embeddings/multimodal")) {
            return baseUrl;
        }
        if (baseUrl.endsWith("/embeddings")) {
            return baseUrl;
        }
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl.substring(0, baseUrl.length() - "/chat/completions".length()) + "/embeddings";
        }
        if (ModelProviderEnum.DOUBAO.getCode().equals(provider) || baseUrl.contains("/api/v3")) {
            if (baseUrl.contains("/api/v3")) {
                return baseUrl + "/embeddings";
            }
            return baseUrl + "/api/v3/embeddings";
        }
        if (baseUrl.endsWith("/v1")) {
            return baseUrl + "/embeddings";
        }
        return baseUrl + "/v1/embeddings";
    }

    private Integer readInt(com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return null;
        com.fasterxml.jackson.databind.JsonNode f = node.get(field);
        return (f != null && !f.isNull() && f.canConvertToInt()) ? f.asInt() : null;
    }

    private void validateModelQuota(String provider, String useCase, String modelId, Integer maxQuotaTokens) {
        if (maxQuotaTokens == null) {
            return;
        }
        if (maxQuotaTokens <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "模型最大限额必须大于 0");
        }
        String normalizedProvider = provider != null ? provider.trim().toLowerCase(Locale.ROOT) : "";
        String normalizedUseCase = useCase != null ? useCase.trim().toLowerCase(Locale.ROOT) : "";
        String normalizedModelId = modelId != null ? modelId.trim().toLowerCase(Locale.ROOT) : "";
        boolean isEmbedding = normalizedUseCase.contains("embedding")
                || normalizedUseCase.contains("向量")
                || normalizedModelId.contains("embedding");
        if ("doubao".equals(normalizedProvider)
                && isEmbedding
                && normalizedModelId.contains("doubao-embedding-vision-251215")
                && maxQuotaTokens > DOUBAO_EMBEDDING_VISION_251215_MAX_QUOTA) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "Doubao doubao-embedding-vision-251215 最大限额不能超过 500000 tokens");
        }
    }

    private void saveConnectivityResult(String modelId, ConnectivityTestResultVO result) {
        LambdaUpdateWrapper<AiModel> updateWrapper = new LambdaUpdateWrapper<AiModel>()
                .eq(AiModel::getId, modelId)
                .set(AiModel::getLastTestAt, result.getTestedAt())
                .set(AiModel::getLastTestStatus, result.getStatus())
                .set(AiModel::getLastTestLatency, result.getLatencyMs() != null ? result.getLatencyMs().intValue() : null)
                .set(AiModel::getLastTestModel, StringUtils.hasText(result.getModelName()) ? result.getModelName() : null)
                .set(AiModel::getLastTestError, "success".equals(result.getStatus()) ? null : trimError(result.getErrorMessage()));
        aiModelMapper.update(null, updateWrapper);
    }

    private String decodeApiKey(String encoded) {
        if (!StringUtils.hasText(encoded)) return "";
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private String trimError(String msg) {
        if (!StringUtils.hasText(msg)) return null;
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }

    private boolean isTimeoutException(IOException ex) {
        String msg = ex.getMessage();
        return msg != null && msg.toLowerCase(Locale.ROOT).contains("timeout");
    }

    // ==================== 内部方法 ====================

    /**
     * 构建模型ID → 模型名称的映射
     */
    private Map<String, String> buildModelNameMap() {
        List<AiModel> allModels = aiModelMapper.selectList(null);
        return allModels.stream()
                .collect(Collectors.toMap(AiModel::getId, AiModel::getName,
                        (existing, replacement) -> existing));
    }

    /**
     * 将路由实体转换为VO，填充模型名称
     */
    private AiModelRouteVO convertToRouteVO(AiModelRoute route, Map<String, String> modelNameMap) {
        AiModelRouteVO vo = new AiModelRouteVO();
        vo.setId(route.getId());
        vo.setName(route.getName());
        vo.setPriority(route.getPriority());
        vo.setMatchDimension(route.getMatchDimension());
        vo.setMatchCondition(route.getMatchCondition());
        vo.setPrimaryModelId(route.getPrimaryModelId());
        vo.setPrimaryModelName(modelNameMap.getOrDefault(route.getPrimaryModelId(), null));
        vo.setSecondaryModelId(route.getSecondaryModelId());
        vo.setSecondaryModelName(modelNameMap.getOrDefault(route.getSecondaryModelId(), null));
        vo.setTertiaryModelId(route.getTertiaryModelId());
        vo.setTertiaryModelName(modelNameMap.getOrDefault(route.getTertiaryModelId(), null));
        vo.setPrimaryTriggerCondition(route.getPrimaryTriggerCondition());
        vo.setSecondaryTriggerCondition(route.getSecondaryTriggerCondition());
        vo.setDescription(route.getDescription());
        vo.setStatus(route.getStatus());
        vo.setCreatedAt(route.getCreatedAt());
        vo.setUpdatedAt(route.getUpdatedAt());
        return vo;
    }
}
