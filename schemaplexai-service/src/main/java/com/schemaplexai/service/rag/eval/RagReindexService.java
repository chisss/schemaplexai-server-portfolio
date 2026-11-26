package com.schemaplexai.service.rag.eval;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.enums.KnowledgeDocumentStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.KnowledgeDocumentMapper;
import com.schemaplexai.model.entity.KnowledgeDocument;
import com.schemaplexai.service.memory.rag.DocumentIngestionService;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.retrieve.RetrievalCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 存量文档重建索引。
 *
 * <p>解决的问题很具体：v2 的 chunk 划分、锚点、双索引都与 v1 不同，存量文档不重跑就享受不到新能力；
 * 而线上没有"重新摄入"的入口时，运维只能手工改数据库状态，危险且不可审计。
 *
 * <p>三条安全边界：
 * <ol>
 *   <li><b>dry-run 优先</b>：先给数量，再决定要不要真跑；</li>
 *   <li><b>单批上限</b>：一次最多 {@link #MAX_BATCH} 条，避免把摄入队列打满、把 Embedding 配额一次烧光；</li>
 *   <li><b>入队失败回滚状态</b>：线程池拒绝时把状态改回原值，绝不留下永远 pending 的僵尸文档。</li>
 * </ol>
 */
@Slf4j
@Service
public class RagReindexService {

    /** 单次最多重建的文档数 */
    static final int MAX_BATCH = 200;

    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentIngestionService documentIngestionService;
    private final RagConfigService ragConfigService;
    private final RetrievalCache retrievalCache;
    private final Executor ingestionExecutor;

    public RagReindexService(KnowledgeDocumentMapper documentMapper,
                             DocumentIngestionService documentIngestionService,
                             RagConfigService ragConfigService,
                             RetrievalCache retrievalCache,
                             @Qualifier("knowledgeIngestionExecutor") Executor ingestionExecutor) {
        this.documentMapper = documentMapper;
        this.documentIngestionService = documentIngestionService;
        this.ragConfigService = ragConfigService;
        this.retrievalCache = retrievalCache;
        this.ingestionExecutor = ingestionExecutor;
    }

    /**
     * 按租户（可选上下文）重建索引。
     *
     * @param tenantId  租户
     * @param contextId 上下文/项目范围，为空表示租户全量
     * @param dryRun    只统计不改状态
     * @param limit     本批最多处理多少条（受 {@link #MAX_BATCH} 约束）
     */
    public ReindexReport reindex(String tenantId, String contextId, boolean dryRun, Integer limit) {
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "缺少租户信息");
        }
        RagRuntimeSettings settings = ragConfigService.resolveSettings(tenantId);
        if (!settings.isEnabled()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "RAG 未启用，无法重建索引");
        }

        int batch = limit == null || limit <= 0 ? MAX_BATCH : Math.min(limit, MAX_BATCH);
        LambdaQueryWrapper<KnowledgeDocument> query = new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getTenantId, tenantId)
                .eq(KnowledgeDocument::getDeleted, 0)
                .ne(KnowledgeDocument::getStatus, KnowledgeDocumentStatusEnum.PROCESSING.getCode())
                .orderByAsc(KnowledgeDocument::getUpdatedAt)
                .last("LIMIT " + batch);
        if (StringUtils.hasText(contextId)) {
            query.eq(KnowledgeDocument::getContextId, contextId);
        }

        List<KnowledgeDocument> documents = documentMapper.selectList(query);
        if (documents.isEmpty()) {
            return new ReindexReport(0, 0, 0, 0, dryRun, List.of());
        }

        if (dryRun) {
            return new ReindexReport(documents.size(), 0, 0, 0, true, List.of("DRY_RUN"));
        }

        int queued = 0;
        int rejected = 0;
        List<String> failures = new ArrayList<>();
        for (KnowledgeDocument document : documents) {
            String previousStatus = document.getStatus();
            try {
                markStatus(document.getId(), KnowledgeDocumentStatusEnum.PENDING.getCode(), null);
                ingestionExecutor.execute(() -> {
                    try {
                        documentIngestionService.ingestDocument(document.getId());
                    } catch (Exception e) {
                        log.error("重建索引执行失败: documentId={}, error={}", document.getId(), e.getMessage(), e);
                    }
                });
                queued++;
            } catch (RejectedExecutionException e) {
                rejected++;
                // 入队失败必须回滚状态，否则文档会永远停在 pending
                markStatus(document.getId(), previousStatus,
                        "重建索引排队失败（摄入队列已满），状态已恢复");
                if (failures.size() < 5) {
                    failures.add(document.getId());
                }
            } catch (Exception e) {
                rejected++;
                markStatus(document.getId(), previousStatus, "重建索引失败: " + e.getMessage());
                if (failures.size() < 5) {
                    failures.add(document.getId());
                }
            }
        }

        if (StringUtils.hasText(contextId)) {
            retrievalCache.invalidateProject(tenantId, contextId);
        }
        log.info("重建索引提交完成: tenantId={}, contextId={}, scanned={}, queued={}, rejected={}",
                tenantId, contextId, documents.size(), queued, rejected);
        return new ReindexReport(documents.size(), queued, rejected, documents.size() - queued - rejected,
                false, failures);
    }

    private void markStatus(String documentId, String status, String errorMessage) {
        LambdaUpdateWrapper<KnowledgeDocument> update = new LambdaUpdateWrapper<>();
        update.eq(KnowledgeDocument::getId, documentId)
                .set(KnowledgeDocument::getStatus, status);
        if (errorMessage != null) {
            update.set(KnowledgeDocument::getErrorMessage, errorMessage);
        }
        documentMapper.update(null, update);
    }

    /**
     * 重建结果。
     *
     * @param scanned   本批扫描到的文档数
     * @param queued    成功入队数
     * @param rejected  入队失败数（状态已回滚）
     * @param skipped   预留：状态不满足条件的数量
     * @param dryRun    是否为预演
     * @param notes     备注（DRY_RUN 或失败文档 ID 样本）
     */
    public record ReindexReport(int scanned, int queued, int rejected, int skipped,
                                boolean dryRun, List<String> notes) {
    }
}
