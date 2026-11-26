package com.schemaplexai.service.memory.rag;

import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.retrieve.RetrievalCandidate;
import com.schemaplexai.service.rag.retrieve.RetrievalOrchestrator;
import com.schemaplexai.service.rag.retrieve.RetrievalOutcome;
import com.schemaplexai.service.rag.retrieve.RetrievalQuery;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG ContentRetriever 工厂（v2）。
 *
 * <p>v1 里这个类自己拼 Milvus 查询 + 可选精排；v2 把它降级为<b>薄适配层</b>：
 * 所有召回策略（双路、RRF、门禁、过滤、预算）都在 {@link RetrievalOrchestrator} 内，
 * 这里只负责「LangChain4j ContentRetriever → 编排器」的协议转换。
 *
 * <p>这样做的原因：Agent 执行链路只认识 {@link ContentRetriever}，
 * 而检索策略会持续演进。保持适配层极薄，改策略时不用碰 Agent 代码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagContentRetrieverFactory {

    private final RagConfigService ragConfigService;
    private final ObjectProvider<RetrievalOrchestrator> orchestratorProvider;

    public ContentRetriever createRetriever(String tenantId) {
        return createRetriever(tenantId, List.of(), null);
    }

    public ContentRetriever createRetriever(String tenantId, Collection<String> contextIds) {
        return createRetriever(tenantId, contextIds, null);
    }

    public ContentRetriever createRetriever(String tenantId, int maxResults, double minScore) {
        return createRetriever(tenantId, List.of(), maxResults);
    }

    /**
     * @param tenantId   租户
     * @param contextIds 绑定的上下文（项目）范围
     * @param maxResults 覆盖最终 TopK；为 null 时用租户配置
     * @return 检索不可用时返回 null，调用方按"无 RAG"处理
     */
    public ContentRetriever createRetriever(String tenantId, Collection<String> contextIds, Integer maxResults) {
        if (!StringUtils.hasText(tenantId)) {
            return null;
        }
        RetrievalOrchestrator orchestrator = orchestratorProvider.getIfAvailable();
        if (orchestrator == null) {
            log.debug("检索编排器不可用，跳过 RAG 检索: tenantId={}", tenantId);
            return null;
        }
        RagRuntimeSettings settings = ragConfigService.resolveSettings(tenantId);
        if (settings == null || !settings.isEnabled()) {
            log.debug("RAG 未启用，跳过检索: tenantId={}", tenantId);
            return null;
        }

        List<String> scopedContextIds = contextIds == null
                ? List.of()
                : contextIds.stream().filter(StringUtils::hasText).distinct().toList();

        return query -> {
            if (query == null || !StringUtils.hasText(query.text())) {
                return List.of();
            }
            RetrievalOutcome outcome = orchestrator.retrieve(
                    new RetrievalQuery(tenantId, scopedContextIds, query.text(), maxResults, null, false));
            if (outcome.isEmpty()) {
                return List.of();
            }
            return outcome.chunks().stream()
                    .map(RagContentRetrieverFactory::toContent)
                    .toList();
        };
    }

    /** chunk → Content，保留来源元数据，供 Agent 侧做引用渲染与审计 */
    private static Content toContent(RetrievalCandidate candidate) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chunkId", candidate.chunkId());
        metadata.put("documentId", candidate.docId());
        metadata.put("fileName", candidate.fileName());
        metadata.put("section", candidate.section());
        metadata.put("docCategory", candidate.category());
        metadata.put("matchedBy", candidate.matchedBy());
        metadata.put("rrfScore", candidate.rrfScore());
        if (candidate.rerankScore() != null) {
            metadata.put("rerankScore", candidate.rerankScore());
        }
        TextSegment segment = TextSegment.from(candidate.text(),
                dev.langchain4j.data.document.Metadata.from(metadata));
        return Content.from(segment);
    }
}
