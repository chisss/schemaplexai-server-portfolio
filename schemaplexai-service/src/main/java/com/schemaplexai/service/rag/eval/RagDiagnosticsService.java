package com.schemaplexai.service.rag.eval;

import com.schemaplexai.dao.mapper.RagConfigMapper;
import com.schemaplexai.model.entity.RagConfig;
import com.schemaplexai.model.vo.system.RagDiagnosticsVO;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.index.Bm25IndexService;
import com.schemaplexai.service.rag.retrieve.RetrievalCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RAG 运行诊断。
 *
 * <p>只读、无副作用：把"配置值"和"实测值"并排放出来。
 * 典型用途：
 * <ul>
 *   <li>改完配置确认 BM25 是否真的可用、chunk 数是否涨了；</li>
 *   <li>排查"为什么没走精排"——看 rerankActive 与 policy；</li>
 *   <li>排查"为什么答案还是旧的"——看 retrievalVersion 是否变了。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagDiagnosticsService {

    private final RagConfigService ragConfigService;
    private final Bm25IndexService bm25IndexService;
    private final RetrievalCache retrievalCache;
    private final RagConfigMapper ragConfigMapper;

    public RagDiagnosticsVO diagnose(String tenantId) {
        RagRuntimeSettings settings = ragConfigService.resolveSettings(tenantId);
        RagDiagnosticsVO vo = new RagDiagnosticsVO();

        vo.setTenantId(tenantId);
        vo.setEnabled(settings.isEnabled());
        vo.setEmbeddingSource(settings.getEmbeddingSource());
        vo.setVectorModelName(settings.getVectorModelName());
        vo.setCollectionName(settings.getCollectionName());
        vo.setEmbeddingDimension(settings.getEmbeddingDimension());
        vo.setVectorTopK(settings.getVectorTopK());

        vo.setBm25Enabled(settings.isBm25Enabled());
        vo.setBm25Available(bm25IndexService.isAvailable());
        vo.setBm25ChunkCount(bm25IndexService.chunkCount(tenantId));
        vo.setBm25TopK(settings.getBm25TopK());

        vo.setChunkSize(settings.getChunkSize());
        vo.setChunkOverlap(settings.getChunkOverlap());
        vo.setSectionSplitEnabled(settings.isSectionSplitEnabled());
        vo.setCodeAstSplitEnabled(settings.isCodeAstSplitEnabled());
        vo.setTableBlockSplitEnabled(settings.isTableBlockSplitEnabled());
        vo.setChunkAnchorEnabled(settings.isChunkAnchorEnabled());

        vo.setFusionTopN(settings.getFusionTopN());
        vo.setRrfK(settings.getRrfK());
        vo.setRetrievalTopK(settings.getRetrievalTopK());
        vo.setRerankEnabled(settings.isRerankerEnabled());
        vo.setRerankPolicy(settings.getRerankPolicy());
        vo.setRerankActive(settings.isRerankActive());
        vo.setRerankerTopN(settings.getRerankerTopN());
        vo.setRerankTimeoutMs(settings.getRerankTimeoutMs());

        vo.setMaxFilesPerContext(settings.getMaxFilesPerContext());
        vo.setEntityOverlapFilterEnabled(settings.isEntityOverlapFilterEnabled());
        vo.setContextTokenBudget(settings.getContextTokenBudget());

        vo.setRetrievalCacheEnabled(settings.isRetrievalCacheEnabled());
        vo.setRetrievalCacheTtlSeconds(settings.getRetrievalCacheTtlSeconds());
        vo.setRetrievalCacheEntries(retrievalCache.size());

        vo.setOcrMode(settings.getOcrMode());
        vo.setOcrModelName(settings.getOcrModelName());
        vo.setOcrMaxConcurrency(settings.getOcrMaxConcurrency());
        vo.setMaxFileSizeMb(settings.getMaxFileSizeMb());

        vo.setCitationRequired(settings.isCitationRequired());
        vo.setHitlOnLowFaithfulness(settings.isHitlOnLowFaithfulness());
        vo.setFaithfulnessThreshold(settings.getFaithfulnessThreshold());
        vo.setEvalRecallK(settings.getEvalRecallK());

        vo.setRetrievalVersion(settings.retrievalVersion());
        vo.setUpdatedAt(resolveUpdatedAt(tenantId));
        return vo;
    }

    private java.time.LocalDateTime resolveUpdatedAt(String tenantId) {
        try {
            RagConfig config = ragConfigMapper.selectById(tenantId);
            return config == null ? null : config.getUpdatedAt();
        } catch (Exception e) {
            log.debug("读取 RAG 配置更新时间失败: tenantId={}, error={}", tenantId, e.getMessage());
            return null;
        }
    }
}
