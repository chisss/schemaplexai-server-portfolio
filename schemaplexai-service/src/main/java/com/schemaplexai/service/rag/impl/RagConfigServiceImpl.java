package com.schemaplexai.service.rag.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.RagConfigMapper;
import com.schemaplexai.dao.mapper.RagOperationLogMapper;
import com.schemaplexai.model.dto.system.RagConfigUpdateRequest;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.RagConfig;
import com.schemaplexai.model.entity.RagOperationLog;
import com.schemaplexai.model.vo.system.RagConfigVO;
import com.schemaplexai.model.vo.system.RagOperationLogVO;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.schemaplexai.common.util.SecurityUtil.getCurrentTenantId;

/**
 * RAG 配置服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagConfigServiceImpl implements RagConfigService {

    private static final String DEFAULT_COLLECTION_NAME = "sf_context_items";
    private static final int DEFAULT_CHUNK_SIZE = 1000;
    private static final int DEFAULT_CHUNK_OVERLAP = 200;
    private static final int DEFAULT_RETRIEVAL_TOP_K = 5;
    private static final double DEFAULT_RETRIEVAL_MIN_SCORE = 0.6D;
    private static final int DEFAULT_DIMENSION = 1536;

    private final RagConfigMapper ragConfigMapper;
    private final RagOperationLogMapper ragOperationLogMapper;
    private final AiModelMapper aiModelMapper;

    @Override
    public RagConfigVO getCurrentTenantConfig() {
        String tenantId = getCurrentTenantId();
        RagConfig config = resolveRagConfig(tenantId);
        return toVO(config, resolveEmbeddingModel(tenantId, config));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RagConfigVO updateCurrentTenantConfig(RagConfigUpdateRequest request) {
        String tenantId = getCurrentTenantId();
        RagConfig config = resolveRagConfig(tenantId);
        if (request.getEnabled() != null) {
            config.setEnabled(request.getEnabled());
        }
        if (request.getVectorModelId() != null) {
            if (StringUtils.hasText(request.getVectorModelId())) {
                AiModel model = aiModelMapper.selectById(request.getVectorModelId());
                if (model == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "向量模型不存在");
                }
                validateEmbeddingUseCase(model);
            }
            config.setVectorModelId(request.getVectorModelId());
        }
        if (request.getCollectionName() != null) {
            config.setCollectionName(request.getCollectionName());
        }
        if (request.getChunkSize() != null) {
            config.setChunkSize(request.getChunkSize());
        }
        if (request.getChunkOverlap() != null) {
            config.setChunkOverlap(request.getChunkOverlap());
        }
        if (request.getRetrievalTopK() != null) {
            config.setRetrievalTopK(request.getRetrievalTopK());
        }
        if (request.getRetrievalMinScore() != null) {
            config.setRetrievalMinScore(request.getRetrievalMinScore());
        }
        if (request.getEmbeddingDimension() != null) {
            config.setEmbeddingDimension(request.getEmbeddingDimension());
        }
        if (request.getTextCleaningEnabled() != null) {
            config.setTextCleaningEnabled(request.getTextCleaningEnabled());
        }

        RagConfig existing = ragConfigMapper.selectById(tenantId);
        if (existing == null) {
            ragConfigMapper.insert(config);
        } else {
            config.setUpdatedAt(LocalDateTime.now());
            ragConfigMapper.updateById(config);
        }
        log.info("更新 RAG 配置成功: tenantId={}", tenantId);
        RagConfig persisted = resolveRagConfig(tenantId);
        return toVO(persisted, resolveEmbeddingModel(tenantId, persisted));
    }

    @Override
    public List<RagOperationLogVO> listCurrentTenantLogs(String contextId, String sourceType, Integer limit) {
        int maxRows = limit != null && limit > 0 ? Math.min(limit, 200) : 50;
        return ragOperationLogMapper.selectRecentByTenantAndContext(getCurrentTenantId(), contextId, sourceType, maxRows)
                .stream()
                .map(this::toLogVO)
                .toList();
    }

    @Override
    public RagRuntimeSettings resolveSettings(String tenantId) {
        RagConfig config = resolveRagConfig(tenantId);
        AiModel model = resolveEmbeddingModel(tenantId, config);
        if (model == null) {
            return RagRuntimeSettings.builder()
                    .tenantId(tenantId)
                    .enabled(Boolean.TRUE.equals(config.getEnabled()))
                    .collectionName(normalizeCollectionName(config.getCollectionName()))
                    .chunkSize(defaultIfNull(config.getChunkSize(), DEFAULT_CHUNK_SIZE))
                    .chunkOverlap(defaultIfNull(config.getChunkOverlap(), DEFAULT_CHUNK_OVERLAP))
                    .retrievalTopK(defaultIfNull(config.getRetrievalTopK(), DEFAULT_RETRIEVAL_TOP_K))
                    .retrievalMinScore(config.getRetrievalMinScore() != null ? config.getRetrievalMinScore() : DEFAULT_RETRIEVAL_MIN_SCORE)
                    .embeddingDimension(defaultIfNull(config.getEmbeddingDimension(), DEFAULT_DIMENSION))
                    .maxQuotaTokens(0)
                    .textCleaningEnabled(config.getTextCleaningEnabled() == null || config.getTextCleaningEnabled())
                    .build();
        }

        AiModelConfig modelConfig = AiModelConfig.from(model);
        int embeddingDimension = resolveDimension(model, config);
        return RagRuntimeSettings.builder()
                .tenantId(tenantId)
                .enabled(Boolean.TRUE.equals(config.getEnabled()))
                .vectorModelConfigId(model.getId())
                .vectorModelName(model.getName())
                .provider(normalizeProvider(model.getProvider()))
                .modelId(model.getModelId())
                .apiKey(modelConfig.getApiKey())
                .baseUrl(modelConfig.getBaseUrl())
                .collectionName(normalizeCollectionName(config.getCollectionName()))
                .chunkSize(defaultIfNull(config.getChunkSize(), DEFAULT_CHUNK_SIZE))
                .chunkOverlap(defaultIfNull(config.getChunkOverlap(), DEFAULT_CHUNK_OVERLAP))
                .retrievalTopK(defaultIfNull(config.getRetrievalTopK(), DEFAULT_RETRIEVAL_TOP_K))
                .retrievalMinScore(config.getRetrievalMinScore() != null ? config.getRetrievalMinScore() : DEFAULT_RETRIEVAL_MIN_SCORE)
                .embeddingDimension(embeddingDimension)
                .maxQuotaTokens(model.getMaxQuotaTokens() != null ? model.getMaxQuotaTokens() : 0)
                .textCleaningEnabled(config.getTextCleaningEnabled() == null || config.getTextCleaningEnabled())
                .build();
    }

    @Override
    public void recordOperation(RagOperationLog operationLog) {
        if (operationLog == null) {
            return;
        }
        try {
            ragOperationLogMapper.insert(operationLog);
        } catch (Exception e) {
            log.warn("写入 RAG 操作日志失败: {}", e.getMessage());
        }
    }

    private RagConfig resolveRagConfig(String tenantId) {
        RagConfig config = null;
        if (StringUtils.hasText(tenantId)) {
            config = ragConfigMapper.selectById(tenantId);
        }
        if (config != null) {
            return config;
        }
        RagConfig defaultConfig = new RagConfig();
        defaultConfig.setTenantId(tenantId);
        defaultConfig.setEnabled(Boolean.TRUE);
        defaultConfig.setCollectionName(DEFAULT_COLLECTION_NAME);
        defaultConfig.setChunkSize(DEFAULT_CHUNK_SIZE);
        defaultConfig.setChunkOverlap(DEFAULT_CHUNK_OVERLAP);
        defaultConfig.setRetrievalTopK(DEFAULT_RETRIEVAL_TOP_K);
        defaultConfig.setRetrievalMinScore(DEFAULT_RETRIEVAL_MIN_SCORE);
        defaultConfig.setTextCleaningEnabled(Boolean.TRUE);
        return defaultConfig;
    }

    private AiModel resolveEmbeddingModel(String tenantId, RagConfig config) {
        if (config != null && StringUtils.hasText(config.getVectorModelId())) {
            AiModel model = aiModelMapper.selectById(config.getVectorModelId());
            if (model != null && CommonConstant.STATUS_ACTIVE.equalsIgnoreCase(model.getStatus())) {
                return model;
            }
        }

        return aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                .and(w -> w.eq(AiModel::getUseCase, "embedding")
                        .or().like(AiModel::getUseCase, "向量"))
                .orderByAsc(AiModel::getCreatedAt)
                .last("LIMIT 1"));
    }

    private RagConfigVO toVO(RagConfig config, AiModel model) {
        RagConfigVO vo = new RagConfigVO();
        vo.setTenantId(config.getTenantId());
        vo.setEnabled(config.getEnabled() == null || config.getEnabled());
        vo.setVectorModelId(config.getVectorModelId());
        vo.setCollectionName(normalizeCollectionName(config.getCollectionName()));
        vo.setChunkSize(defaultIfNull(config.getChunkSize(), DEFAULT_CHUNK_SIZE));
        vo.setChunkOverlap(defaultIfNull(config.getChunkOverlap(), DEFAULT_CHUNK_OVERLAP));
        vo.setRetrievalTopK(defaultIfNull(config.getRetrievalTopK(), DEFAULT_RETRIEVAL_TOP_K));
        vo.setRetrievalMinScore(config.getRetrievalMinScore() != null ? config.getRetrievalMinScore() : DEFAULT_RETRIEVAL_MIN_SCORE);
        vo.setEmbeddingDimension(resolveDimension(model, config));
        vo.setMaxQuotaTokens(model != null ? model.getMaxQuotaTokens() : null);
        vo.setTextCleaningEnabled(config.getTextCleaningEnabled() == null || config.getTextCleaningEnabled());
        vo.setUpdatedAt(config.getUpdatedAt());
        if (model != null) {
            vo.setVectorModelId(model.getId());
            vo.setVectorModelName(model.getName());
            vo.setProvider(normalizeProvider(model.getProvider()));
            vo.setModelId(model.getModelId());
        }
        return vo;
    }

    private RagOperationLogVO toLogVO(RagOperationLog entity) {
        RagOperationLogVO vo = new RagOperationLogVO();
        vo.setId(entity.getId());
        vo.setOperationType(entity.getOperationType());
        vo.setSourceType(entity.getSourceType());
        vo.setSourceId(entity.getSourceId());
        vo.setContextId(entity.getContextId());
        vo.setModelConfigId(entity.getModelConfigId());
        vo.setModelName(entity.getModelName());
        vo.setProvider(entity.getProvider());
        vo.setCollectionName(entity.getCollectionName());
        vo.setStatus(entity.getStatus());
        vo.setChunkCount(entity.getChunkCount());
        vo.setRetrievedCount(entity.getRetrievedCount());
        vo.setVectorDimension(entity.getVectorDimension());
        vo.setRequestChars(entity.getRequestChars());
        vo.setDurationMs(entity.getDurationMs());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setMetadata(entity.getMetadata());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    private void validateEmbeddingUseCase(AiModel model) {
        if (model == null) {
            return;
        }
        if (!"embedding".equalsIgnoreCase(model.getUseCase()) && !StringUtils.hasText(model.getUseCase())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "所选模型不是向量模型");
        }
        if (StringUtils.hasText(model.getUseCase())
                && !model.getUseCase().toLowerCase(Locale.ROOT).contains("embedding")
                && !model.getUseCase().contains("向量")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "所选模型不是向量模型");
        }
    }

    private int resolveDimension(AiModel model, RagConfig config) {
        if (config != null && config.getEmbeddingDimension() != null && config.getEmbeddingDimension() > 0) {
            return config.getEmbeddingDimension();
        }
        if (model != null && model.getDefaultParams() != null) {
            Object dimensions = firstPresent(model.getDefaultParams(), "dimensions", "dimension");
            if (dimensions instanceof Number number) {
                return number.intValue();
            }
        }
        if (model != null && StringUtils.hasText(model.getModelId())) {
            String normalizedModelId = model.getModelId().toLowerCase(Locale.ROOT);
            if (normalizedModelId.contains("doubao-embedding-text-240715")) {
                return 2560;
            }
            if (normalizedModelId.contains("doubao-embedding-text-240515")) {
                return 2048;
            }
            if (normalizedModelId.contains("doubao-embedding-vision-251215")) {
                return 2048;
            }
            if (normalizedModelId.startsWith("text-embedding-3-large")) {
                return 3072;
            }
            if (normalizedModelId.startsWith("text-embedding-3-small")) {
                return 1536;
            }
        }
        return DEFAULT_DIMENSION;
    }

    private Object firstPresent(Map<String, Object> params, String... keys) {
        if (params == null) {
            return null;
        }
        for (String key : keys) {
            Object value = params.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String normalizeCollectionName(String collectionName) {
        return StringUtils.hasText(collectionName) ? collectionName.trim() : DEFAULT_COLLECTION_NAME;
    }

    private String normalizeProvider(String provider) {
        return StringUtils.hasText(provider) ? provider.trim().toLowerCase(Locale.ROOT) : "openai";
    }

    private int defaultIfNull(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }
}
