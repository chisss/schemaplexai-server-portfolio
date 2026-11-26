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
import com.schemaplexai.service.rag.RagDefaults;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.vector.impl.InProcessEmbeddingServiceImpl.BuiltinEmbeddingModel;
import com.schemaplexai.service.vector.impl.OnnxScoringServiceImpl;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.schemaplexai.common.util.SecurityUtil.getCurrentTenantId;

/**
 * RAG 配置服务实现（v2）。
 *
 * <p>v2 把配置拆成六组语义：向量模型、摄入解析、切分策略、索引召回、融合重排、生成合规。
 * {@link #resolveSettings(String)} 是唯一出口，摄入侧与检索侧都只认 {@link RagRuntimeSettings}，
 * 不再各自读实体，避免"UI 改了但管线没生效"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagConfigServiceImpl implements RagConfigService {

    private static final String DEFAULT_COLLECTION_NAME = "sf_context_items";
    private static final String SOURCE_BUILTIN = "builtin";
    private static final String SOURCE_API = "api";
    private static final String VECTOR_FIELD_NAME = "vector";
    private static final int COLLECTION_NAME_MAX_LENGTH = 64;
    private static final int DEFAULT_DIMENSION = 1536;

    private final RagConfigMapper ragConfigMapper;
    private final RagOperationLogMapper ragOperationLogMapper;
    private final AiModelMapper aiModelMapper;
    private final ObjectProvider<MilvusClientV2> milvusClientProvider;
    private final ConcurrentMap<String, String> collectionNameCache = new ConcurrentHashMap<>();

    @Override
    public RagConfigVO getCurrentTenantConfig() {
        String tenantId = getCurrentTenantId();
        RagConfig config = resolveRagConfig(tenantId);
        AiModel model = isApiEmbedding(config) ? resolveEmbeddingModel(tenantId, config) : null;
        return toVO(config, model, resolveOcrModel(config));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RagConfigVO updateCurrentTenantConfig(RagConfigUpdateRequest request) {
        String tenantId = getCurrentTenantId();
        RagConfig config = resolveRagConfig(tenantId);

        // ── 向量模型 ──
        if (request.getEnabled() != null) {
            config.setEnabled(request.getEnabled());
        }
        if (request.getEmbeddingSource() != null) {
            config.setEmbeddingSource(request.getEmbeddingSource());
        }
        if (request.getBuiltinEmbeddingModelId() != null) {
            config.setBuiltinEmbeddingModelId(request.getBuiltinEmbeddingModelId());
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

        // ── Reranker ──
        if (request.getRerankerEnabled() != null) {
            config.setRerankerEnabled(request.getRerankerEnabled());
        }
        if (request.getRerankerSource() != null) {
            config.setRerankerSource(request.getRerankerSource());
        }
        if (request.getRerankerTopN() != null) {
            config.setRerankerTopN(request.getRerankerTopN());
        }
        if (request.getRerankerMinScore() != null) {
            config.setRerankerMinScore(request.getRerankerMinScore());
        }

        // ── v2：摄入与解析 ──
        if (request.getMaxFileSizeMb() != null) {
            config.setMaxFileSizeMb(request.getMaxFileSizeMb());
        }
        if (request.getIngestCodeEnabled() != null) {
            config.setIngestCodeEnabled(request.getIngestCodeEnabled());
        }
        if (request.getIngestExcelEnabled() != null) {
            config.setIngestExcelEnabled(request.getIngestExcelEnabled());
        }
        if (request.getOcrMode() != null) {
            config.setOcrMode(request.getOcrMode());
        }
        if (request.getOcrModelId() != null) {
            if (StringUtils.hasText(request.getOcrModelId())
                    && aiModelMapper.selectById(request.getOcrModelId()) == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "OCR 多模态模型不存在");
            }
            config.setOcrModelId(request.getOcrModelId());
        }
        if (request.getOcrMaxConcurrency() != null) {
            config.setOcrMaxConcurrency(request.getOcrMaxConcurrency());
        }
        if (request.getOcrMinTextChars() != null) {
            config.setOcrMinTextChars(request.getOcrMinTextChars());
        }

        // ── v2：切分策略 ──
        if (request.getSectionSplitEnabled() != null) {
            config.setSectionSplitEnabled(request.getSectionSplitEnabled());
        }
        if (request.getCodeAstSplitEnabled() != null) {
            config.setCodeAstSplitEnabled(request.getCodeAstSplitEnabled());
        }
        if (request.getTableBlockSplitEnabled() != null) {
            config.setTableBlockSplitEnabled(request.getTableBlockSplitEnabled());
        }
        if (request.getChunkAnchorEnabled() != null) {
            config.setChunkAnchorEnabled(request.getChunkAnchorEnabled());
        }

        // ── v2：索引与召回 ──
        if (request.getBm25Enabled() != null) {
            config.setBm25Enabled(request.getBm25Enabled());
        }
        if (request.getBm25TopK() != null) {
            config.setBm25TopK(request.getBm25TopK());
        }
        if (request.getVectorTopK() != null) {
            config.setVectorTopK(request.getVectorTopK());
        }
        if (Boolean.FALSE.equals(request.getPrefilterRequired())) {
            // 前置过滤是硬约束，不接受关闭
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "租户/项目前置过滤为硬性安全约束，不允许关闭");
        }
        config.setPrefilterRequired(Boolean.TRUE);

        // ── v2：融合与重排 ──
        if (request.getRrfK() != null) {
            config.setRrfK(request.getRrfK());
        }
        if (request.getFusionTopN() != null) {
            config.setFusionTopN(request.getFusionTopN());
        }
        if (request.getRerankPolicy() != null) {
            config.setRerankPolicy(normalizeRerankPolicy(request.getRerankPolicy()));
        }
        if (request.getRerankTimeoutMs() != null) {
            config.setRerankTimeoutMs(request.getRerankTimeoutMs());
        }
        if (request.getMaxFilesPerContext() != null) {
            config.setMaxFilesPerContext(request.getMaxFilesPerContext());
        }
        if (request.getEntityOverlapFilterEnabled() != null) {
            config.setEntityOverlapFilterEnabled(request.getEntityOverlapFilterEnabled());
        }
        if (request.getContextTokenBudget() != null) {
            config.setContextTokenBudget(request.getContextTokenBudget());
        }
        if (request.getKeywordFastPathEnabled() != null) {
            config.setKeywordFastPathEnabled(request.getKeywordFastPathEnabled());
        }

        // ── v2：检索缓存 ──
        if (request.getRetrievalCacheEnabled() != null) {
            config.setRetrievalCacheEnabled(request.getRetrievalCacheEnabled());
        }
        if (request.getRetrievalCacheTtlSeconds() != null) {
            config.setRetrievalCacheTtlSeconds(request.getRetrievalCacheTtlSeconds());
        }

        // ── v2：生成与合规 ──
        if (request.getCitationRequired() != null) {
            config.setCitationRequired(request.getCitationRequired());
        }
        if (request.getHitlOnLowFaithfulness() != null) {
            config.setHitlOnLowFaithfulness(request.getHitlOnLowFaithfulness());
        }
        if (request.getFaithfulnessThreshold() != null) {
            config.setFaithfulnessThreshold(request.getFaithfulnessThreshold());
        }

        // ── v2：评测 ──
        if (request.getEvalRecallK() != null) {
            config.setEvalRecallK(request.getEvalRecallK());
        }

        validateConsistency(config);

        RagConfig existing = ragConfigMapper.selectById(tenantId);
        if (existing == null) {
            ragConfigMapper.insert(config);
        } else {
            config.setUpdatedAt(LocalDateTime.now());
            ragConfigMapper.updateById(config);
        }
        invalidateCollectionCache(tenantId);
        log.info("更新 RAG 配置成功: tenantId={}, rerankPolicy={}, bm25={}", tenantId,
                config.getRerankPolicy(), config.getBm25Enabled());
        RagConfig persisted = resolveRagConfig(tenantId);
        AiModel model = isApiEmbedding(persisted) ? resolveEmbeddingModel(tenantId, persisted) : null;
        return toVO(persisted, model, resolveOcrModel(persisted));
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
        String embeddingSource = normalizeSource(config.getEmbeddingSource());
        String rerankerSource = normalizeSource(config.getRerankerSource(), SOURCE_BUILTIN);

        if (SOURCE_BUILTIN.equals(embeddingSource)) {
            BuiltinEmbeddingModel builtinModel = BuiltinEmbeddingModel.fromModelId(config.getBuiltinEmbeddingModelId());
            String collectionName = resolveRuntimeCollectionName(tenantId, config, SOURCE_BUILTIN,
                    builtinModel.getModelId(), builtinModel.getDimension());
            return applyCommon(baseBuilder(tenantId, config)
                    .embeddingSource(SOURCE_BUILTIN)
                    .builtinEmbeddingModelId(builtinModel.getModelId())
                    .collectionName(collectionName)
                    .embeddingDimension(builtinModel.getDimension())
                    .maxQuotaTokens(0)
                    .rerankerSource(rerankerSource), config);
        }

        AiModel model = resolveEmbeddingModel(tenantId, config);
        if (model == null) {
            int embeddingDimension = defaultIfNull(config.getEmbeddingDimension(), DEFAULT_DIMENSION);
            String collectionName = resolveRuntimeCollectionName(tenantId, config, SOURCE_API,
                    "default", embeddingDimension);
            return applyCommon(baseBuilder(tenantId, config)
                    .embeddingSource(SOURCE_API)
                    .collectionName(collectionName)
                    .embeddingDimension(embeddingDimension)
                    .maxQuotaTokens(0)
                    .rerankerSource(rerankerSource), config);
        }

        AiModelConfig modelConfig = AiModelConfig.from(model);
        int embeddingDimension = resolveDimension(model, config);
        String collectionName = resolveRuntimeCollectionName(tenantId, config, SOURCE_API,
                model.getModelId(), embeddingDimension);
        return applyCommon(baseBuilder(tenantId, config)
                .embeddingSource(SOURCE_API)
                .vectorModelConfigId(model.getId())
                .vectorModelName(model.getName())
                .provider(normalizeProvider(model.getProvider()))
                .modelId(model.getModelId())
                .apiKey(modelConfig.getApiKey())
                .baseUrl(modelConfig.getBaseUrl())
                .collectionName(collectionName)
                .embeddingDimension(embeddingDimension)
                .maxQuotaTokens(model.getMaxQuotaTokens() != null ? model.getMaxQuotaTokens() : 0)
                .rerankerSource(rerankerSource), config);
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

    // ==================== 内部：settings 组装 ====================

    private RagRuntimeSettings.RagRuntimeSettingsBuilder baseBuilder(String tenantId, RagConfig config) {
        return RagRuntimeSettings.builder()
                .tenantId(tenantId)
                .enabled(Boolean.TRUE.equals(config.getEnabled()))
                .chunkSize(defaultIfNull(config.getChunkSize(), RagDefaults.CHUNK_SIZE))
                .chunkOverlap(defaultIfNull(config.getChunkOverlap(), RagDefaults.CHUNK_OVERLAP))
                .retrievalTopK(defaultIfNull(config.getRetrievalTopK(), RagDefaults.RETRIEVAL_TOP_K))
                .retrievalMinScore(config.getRetrievalMinScore() != null
                        ? config.getRetrievalMinScore() : RagDefaults.RETRIEVAL_MIN_SCORE)
                .textCleaningEnabled(config.getTextCleaningEnabled() == null || config.getTextCleaningEnabled())
                .rerankerEnabled(Boolean.TRUE.equals(config.getRerankerEnabled()))
                .rerankerTopN(defaultIfNull(config.getRerankerTopN(), RagDefaults.RERANK_TOP_N))
                .rerankerMinScore(config.getRerankerMinScore() != null
                        ? config.getRerankerMinScore() : RagDefaults.RERANK_MIN_SCORE);
    }

    /** v2 字段统一在此落默认值，杜绝下游各自兜底 */
    private RagRuntimeSettings applyCommon(RagRuntimeSettings.RagRuntimeSettingsBuilder builder, RagConfig config) {
        AiModel ocrModel = resolveOcrModel(config);
        return builder
                // 摄入与解析
                .maxFileSizeMb(defaultIfNull(config.getMaxFileSizeMb(), RagDefaults.MAX_FILE_SIZE_MB))
                .ingestCodeEnabled(config.getIngestCodeEnabled() == null || config.getIngestCodeEnabled())
                .ingestExcelEnabled(config.getIngestExcelEnabled() == null || config.getIngestExcelEnabled())
                .ocrMode(normalizeOcrMode(config.getOcrMode()))
                .ocrModelConfigId(config.getOcrModelId())
                .ocrModelName(ocrModel != null ? ocrModel.getName() : null)
                .ocrMaxConcurrency(defaultIfNull(config.getOcrMaxConcurrency(), RagDefaults.OCR_MAX_CONCURRENCY))
                .ocrMinTextChars(defaultIfNull(config.getOcrMinTextChars(), RagDefaults.OCR_MIN_TEXT_CHARS))
                // 切分策略
                .sectionSplitEnabled(config.getSectionSplitEnabled() == null || config.getSectionSplitEnabled())
                .codeAstSplitEnabled(config.getCodeAstSplitEnabled() == null || config.getCodeAstSplitEnabled())
                .tableBlockSplitEnabled(config.getTableBlockSplitEnabled() == null || config.getTableBlockSplitEnabled())
                .chunkAnchorEnabled(config.getChunkAnchorEnabled() == null || config.getChunkAnchorEnabled())
                // 索引与召回
                .bm25Enabled(config.getBm25Enabled() == null || config.getBm25Enabled())
                .bm25TopK(defaultIfNull(config.getBm25TopK(), RagDefaults.BM25_TOP_K))
                .vectorTopK(defaultIfNull(config.getVectorTopK(), RagDefaults.VECTOR_TOP_K))
                .prefilterRequired(Boolean.TRUE)
                // 融合与重排
                .rrfK(defaultIfNull(config.getRrfK(), RagDefaults.RRF_K))
                .fusionTopN(defaultIfNull(config.getFusionTopN(), RagDefaults.FUSION_TOP_N))
                .rerankPolicy(normalizeRerankPolicy(config.getRerankPolicy()))
                .rerankTimeoutMs(defaultIfNull(config.getRerankTimeoutMs(), RagDefaults.RERANK_TIMEOUT_MS))
                .maxFilesPerContext(defaultIfNull(config.getMaxFilesPerContext(), RagDefaults.MAX_FILES_PER_CONTEXT))
                .entityOverlapFilterEnabled(config.getEntityOverlapFilterEnabled() == null
                        || config.getEntityOverlapFilterEnabled())
                .contextTokenBudget(defaultIfNull(config.getContextTokenBudget(), RagDefaults.CONTEXT_TOKEN_BUDGET))
                .keywordFastPathEnabled(config.getKeywordFastPathEnabled() == null
                        || config.getKeywordFastPathEnabled())
                // 缓存
                .retrievalCacheEnabled(config.getRetrievalCacheEnabled() == null || config.getRetrievalCacheEnabled())
                .retrievalCacheTtlSeconds(defaultIfNull(config.getRetrievalCacheTtlSeconds(),
                        RagDefaults.CACHE_TTL_SECONDS))
                // 生成与合规
                .citationRequired(config.getCitationRequired() == null || config.getCitationRequired())
                .hitlOnLowFaithfulness(config.getHitlOnLowFaithfulness() == null
                        || config.getHitlOnLowFaithfulness())
                .faithfulnessThreshold(config.getFaithfulnessThreshold() != null
                        ? config.getFaithfulnessThreshold() : RagDefaults.FAITHFULNESS_THRESHOLD)
                // 评测
                .evalRecallK(defaultIfNull(config.getEvalRecallK(), RagDefaults.EVAL_RECALL_K))
                .build();
    }

    // ==================== 内部：校验 ====================

    /** 跨字段一致性校验：这些组合在 UI 被拦住，后端也必须拦，避免直接调 API 打穿配置 */
    private void validateConsistency(RagConfig config) {
        int chunkSize = defaultIfNull(config.getChunkSize(), RagDefaults.CHUNK_SIZE);
        int chunkOverlap = defaultIfNull(config.getChunkOverlap(), RagDefaults.CHUNK_OVERLAP);
        if (chunkOverlap >= chunkSize) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "分块重叠必须小于分块大小（overlap=" + chunkOverlap + ", size=" + chunkSize + "）");
        }
        int fusionTopN = defaultIfNull(config.getFusionTopN(), RagDefaults.FUSION_TOP_N);
        int retrievalTopK = defaultIfNull(config.getRetrievalTopK(), RagDefaults.RETRIEVAL_TOP_K);
        if (retrievalTopK > fusionTopN) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "最终 TopK 不能大于融合候选池（topK=" + retrievalTopK + ", fusionTopN=" + fusionTopN + "）");
        }
        int rerankerTopN = defaultIfNull(config.getRerankerTopN(), RagDefaults.RERANK_TOP_N);
        if (rerankerTopN > fusionTopN) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "Reranker TopN 不能大于融合候选池（rerankerTopN=" + rerankerTopN
                            + ", fusionTopN=" + fusionTopN + "）");
        }
    }

    private boolean isApiEmbedding(RagConfig config) {
        return config == null || !SOURCE_BUILTIN.equalsIgnoreCase(config.getEmbeddingSource());
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
        defaultConfig.setEmbeddingSource(SOURCE_API);
        defaultConfig.setCollectionName(DEFAULT_COLLECTION_NAME);
        defaultConfig.setChunkSize(RagDefaults.CHUNK_SIZE);
        defaultConfig.setChunkOverlap(RagDefaults.CHUNK_OVERLAP);
        defaultConfig.setRetrievalTopK(RagDefaults.RETRIEVAL_TOP_K);
        defaultConfig.setRetrievalMinScore(RagDefaults.RETRIEVAL_MIN_SCORE);
        defaultConfig.setTextCleaningEnabled(Boolean.TRUE);
        defaultConfig.setRerankerSource(SOURCE_BUILTIN);
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

    private AiModel resolveOcrModel(RagConfig config) {
        if (config == null || !StringUtils.hasText(config.getOcrModelId())) {
            return null;
        }
        try {
            return aiModelMapper.selectById(config.getOcrModelId());
        } catch (Exception e) {
            log.warn("读取 OCR 模型失败: id={}, error={}", config.getOcrModelId(), e.getMessage());
            return null;
        }
    }

    private RagConfigVO toVO(RagConfig config, AiModel model, AiModel ocrModel) {
        RagConfigVO vo = new RagConfigVO();
        vo.setTenantId(config.getTenantId());
        vo.setEnabled(config.getEnabled() == null || config.getEnabled());

        // 向量模型来源
        String source = normalizeSource(config.getEmbeddingSource());
        vo.setEmbeddingSource(source);
        if (SOURCE_BUILTIN.equals(source)) {
            BuiltinEmbeddingModel builtinModel = BuiltinEmbeddingModel.fromModelId(config.getBuiltinEmbeddingModelId());
            vo.setBuiltinEmbeddingModelId(builtinModel.getModelId());
            vo.setBuiltinEmbeddingModelName(builtinModel.getDescription());
            vo.setBuiltinEmbeddingDimension(builtinModel.getDimension());
            vo.setEmbeddingDimension(builtinModel.getDimension());
            vo.setCollectionName(resolveRuntimeCollectionName(config.getTenantId(), config, SOURCE_BUILTIN,
                    builtinModel.getModelId(), builtinModel.getDimension()));
        } else {
            vo.setVectorModelId(config.getVectorModelId());
            int embeddingDimension = resolveDimension(model, config);
            vo.setEmbeddingDimension(embeddingDimension);
            vo.setMaxQuotaTokens(model != null ? model.getMaxQuotaTokens() : null);
            if (model != null) {
                vo.setVectorModelId(model.getId());
                vo.setVectorModelName(model.getName());
                vo.setProvider(normalizeProvider(model.getProvider()));
                vo.setModelId(model.getModelId());
            }
            vo.setCollectionName(resolveRuntimeCollectionName(config.getTenantId(), config, SOURCE_API,
                    model != null ? model.getModelId() : "default", embeddingDimension));
        }

        vo.setChunkSize(defaultIfNull(config.getChunkSize(), RagDefaults.CHUNK_SIZE));
        vo.setChunkOverlap(defaultIfNull(config.getChunkOverlap(), RagDefaults.CHUNK_OVERLAP));
        vo.setRetrievalTopK(defaultIfNull(config.getRetrievalTopK(), RagDefaults.RETRIEVAL_TOP_K));
        vo.setRetrievalMinScore(config.getRetrievalMinScore() != null
                ? config.getRetrievalMinScore() : RagDefaults.RETRIEVAL_MIN_SCORE);
        vo.setTextCleaningEnabled(config.getTextCleaningEnabled() == null || config.getTextCleaningEnabled());

        // Reranker
        vo.setRerankerEnabled(Boolean.TRUE.equals(config.getRerankerEnabled()));
        String rSource = normalizeSource(config.getRerankerSource(), SOURCE_BUILTIN);
        vo.setRerankerSource(rSource);
        if (SOURCE_BUILTIN.equals(rSource)) {
            vo.setBuiltinScoringModelId(OnnxScoringServiceImpl.BUILTIN_MODEL_ID);
            vo.setBuiltinScoringModelName(OnnxScoringServiceImpl.BUILTIN_MODEL_NAME);
        }
        vo.setRerankerTopN(defaultIfNull(config.getRerankerTopN(), RagDefaults.RERANK_TOP_N));
        vo.setRerankerMinScore(config.getRerankerMinScore() != null
                ? config.getRerankerMinScore() : RagDefaults.RERANK_MIN_SCORE);

        // v2：摄入与解析
        vo.setMaxFileSizeMb(defaultIfNull(config.getMaxFileSizeMb(), RagDefaults.MAX_FILE_SIZE_MB));
        vo.setIngestCodeEnabled(config.getIngestCodeEnabled() == null || config.getIngestCodeEnabled());
        vo.setIngestExcelEnabled(config.getIngestExcelEnabled() == null || config.getIngestExcelEnabled());
        vo.setOcrMode(normalizeOcrMode(config.getOcrMode()));
        vo.setOcrModelId(config.getOcrModelId());
        vo.setOcrModelName(ocrModel != null ? ocrModel.getName() : null);
        vo.setOcrMaxConcurrency(defaultIfNull(config.getOcrMaxConcurrency(), RagDefaults.OCR_MAX_CONCURRENCY));
        vo.setOcrMinTextChars(defaultIfNull(config.getOcrMinTextChars(), RagDefaults.OCR_MIN_TEXT_CHARS));

        // v2：切分策略
        vo.setSectionSplitEnabled(config.getSectionSplitEnabled() == null || config.getSectionSplitEnabled());
        vo.setCodeAstSplitEnabled(config.getCodeAstSplitEnabled() == null || config.getCodeAstSplitEnabled());
        vo.setTableBlockSplitEnabled(config.getTableBlockSplitEnabled() == null || config.getTableBlockSplitEnabled());
        vo.setChunkAnchorEnabled(config.getChunkAnchorEnabled() == null || config.getChunkAnchorEnabled());

        // v2：索引与召回
        vo.setBm25Enabled(config.getBm25Enabled() == null || config.getBm25Enabled());
        vo.setBm25TopK(defaultIfNull(config.getBm25TopK(), RagDefaults.BM25_TOP_K));
        vo.setVectorTopK(defaultIfNull(config.getVectorTopK(), RagDefaults.VECTOR_TOP_K));
        vo.setPrefilterRequired(Boolean.TRUE);

        // v2：融合与重排
        vo.setRrfK(defaultIfNull(config.getRrfK(), RagDefaults.RRF_K));
        vo.setFusionTopN(defaultIfNull(config.getFusionTopN(), RagDefaults.FUSION_TOP_N));
        vo.setRerankPolicy(normalizeRerankPolicy(config.getRerankPolicy()));
        vo.setRerankTimeoutMs(defaultIfNull(config.getRerankTimeoutMs(), RagDefaults.RERANK_TIMEOUT_MS));
        vo.setMaxFilesPerContext(defaultIfNull(config.getMaxFilesPerContext(), RagDefaults.MAX_FILES_PER_CONTEXT));
        vo.setEntityOverlapFilterEnabled(config.getEntityOverlapFilterEnabled() == null
                || config.getEntityOverlapFilterEnabled());
        vo.setContextTokenBudget(defaultIfNull(config.getContextTokenBudget(), RagDefaults.CONTEXT_TOKEN_BUDGET));
        vo.setKeywordFastPathEnabled(config.getKeywordFastPathEnabled() == null
                || config.getKeywordFastPathEnabled());

        // v2：检索缓存
        vo.setRetrievalCacheEnabled(config.getRetrievalCacheEnabled() == null || config.getRetrievalCacheEnabled());
        vo.setRetrievalCacheTtlSeconds(defaultIfNull(config.getRetrievalCacheTtlSeconds(),
                RagDefaults.CACHE_TTL_SECONDS));

        // v2：生成与合规
        vo.setCitationRequired(config.getCitationRequired() == null || config.getCitationRequired());
        vo.setHitlOnLowFaithfulness(config.getHitlOnLowFaithfulness() == null
                || config.getHitlOnLowFaithfulness());
        vo.setFaithfulnessThreshold(config.getFaithfulnessThreshold() != null
                ? config.getFaithfulnessThreshold() : RagDefaults.FAITHFULNESS_THRESHOLD);

        // v2：评测
        vo.setEvalRecallK(defaultIfNull(config.getEvalRecallK(), RagDefaults.EVAL_RECALL_K));

        vo.setUpdatedAt(config.getUpdatedAt());
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

    private String resolveRuntimeCollectionName(String tenantId,
                                                RagConfig config,
                                                String embeddingSource,
                                                String modelIdentity,
                                                int embeddingDimension) {
        String requestedCollection = normalizeCollectionName(config != null ? config.getCollectionName() : null);
        String cacheKey = String.join("|",
                StringUtils.hasText(tenantId) ? tenantId : "default",
                requestedCollection,
                StringUtils.hasText(embeddingSource) ? embeddingSource : SOURCE_API,
                StringUtils.hasText(modelIdentity) ? modelIdentity : "default",
                String.valueOf(embeddingDimension));
        return collectionNameCache.computeIfAbsent(cacheKey, ignored -> {
            Integer actualDimension = resolveExistingCollectionDimension(requestedCollection);
            if (actualDimension == null || actualDimension == embeddingDimension) {
                return requestedCollection;
            }
            String managedCollection = buildManagedCollectionName(embeddingSource, modelIdentity, embeddingDimension);
            log.warn("检测到 RAG 集合维度不兼容，自动切换集合: tenantId={}, requestedCollection={}, actualDimension={}, expectedDimension={}, effectiveCollection={}",
                    tenantId, requestedCollection, actualDimension, embeddingDimension, managedCollection);
            return managedCollection;
        });
    }

    private Integer resolveExistingCollectionDimension(String collectionName) {
        MilvusClientV2 milvusClient = milvusClientProvider.getIfAvailable();
        if (milvusClient == null || !StringUtils.hasText(collectionName)) {
            return null;
        }
        try {
            boolean exists = milvusClient.hasCollection(HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            if (!exists) {
                return null;
            }
            var response = milvusClient.describeCollection(DescribeCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            if (response == null || response.getCollectionSchema() == null) {
                return null;
            }
            var vectorField = response.getCollectionSchema().getField(VECTOR_FIELD_NAME);
            return vectorField != null ? vectorField.getDimension() : null;
        } catch (Exception e) {
            log.warn("读取 RAG 集合维度失败，沿用原集合: collectionName={}, error={}", collectionName, e.getMessage());
            return null;
        }
    }

    private String buildManagedCollectionName(String embeddingSource, String modelIdentity, int embeddingDimension) {
        String sourceToken = sanitizeCollectionToken(embeddingSource);
        String modelToken = sanitizeCollectionToken(modelIdentity);
        String candidate = DEFAULT_COLLECTION_NAME + "_" + sourceToken + "_" + modelToken + "_" + embeddingDimension;
        if (candidate.length() <= COLLECTION_NAME_MAX_LENGTH) {
            return candidate;
        }
        String compactToken = Integer.toHexString(candidate.hashCode());
        return DEFAULT_COLLECTION_NAME + "_" + sourceToken + "_" + compactToken + "_" + embeddingDimension;
    }

    private String sanitizeCollectionToken(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "default";
        }
        String normalized = raw.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return StringUtils.hasText(normalized) ? normalized : "default";
    }

    private void invalidateCollectionCache(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            collectionNameCache.clear();
            return;
        }
        String cachePrefix = tenantId + "|";
        collectionNameCache.keySet().removeIf(key -> key.startsWith(cachePrefix));
    }

    private String normalizeProvider(String provider) {
        return StringUtils.hasText(provider) ? provider.trim().toLowerCase(Locale.ROOT) : "openai";
    }

    private String normalizeSource(String source) {
        return normalizeSource(source, SOURCE_API);
    }

    private String normalizeSource(String source, String defaultSource) {
        return StringUtils.hasText(source) ? source.trim().toLowerCase(Locale.ROOT) : defaultSource;
    }

    /** 非法/空策略收敛到 auto，避免脏数据导致"要么全开要么全关" */
    private String normalizeRerankPolicy(String policy) {
        if (!StringUtils.hasText(policy)) {
            return RagDefaults.RERANK_POLICY_AUTO;
        }
        String normalized = policy.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case RagDefaults.RERANK_POLICY_ALWAYS,
                 RagDefaults.RERANK_POLICY_AUTO,
                 RagDefaults.RERANK_POLICY_NEVER -> normalized;
            default -> RagDefaults.RERANK_POLICY_AUTO;
        };
    }

    private String normalizeOcrMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return RagDefaults.OCR_MODE_MULTIMODAL;
        }
        String normalized = mode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case RagDefaults.OCR_MODE_MULTIMODAL,
                 RagDefaults.OCR_MODE_API,
                 RagDefaults.OCR_MODE_DISABLED -> normalized;
            default -> RagDefaults.OCR_MODE_MULTIMODAL;
        };
    }

    private int defaultIfNull(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }
}
