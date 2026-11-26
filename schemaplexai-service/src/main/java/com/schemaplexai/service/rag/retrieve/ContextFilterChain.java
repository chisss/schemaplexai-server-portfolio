package com.schemaplexai.service.rag.retrieve;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.vector.EmbeddingTokenEstimator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文过滤链（缓解指代跑偏 + 控制上下文体积）。
 *
 * <p>三道闸，顺序不能换：
 * <ol>
 *   <li><b>实体交集</b>：query 与 chunk 说的是不是同一件事，先剔掉明显无关的；</li>
 *   <li><b>文件数量</b>：最多 N 个不同文件进上下文。跨文档实体混淆是指代跑偏的主因，
 *       10 个文件的片段拼在一起，模型很容易把 A 合同的利率安到 B 合同上；</li>
 *   <li><b>Token 硬上限</b>：按排序累计，超出预算就停，绝不"多塞几条总没坏处"。</li>
 * </ol>
 */
@Component
public class ContextFilterChain {

    private final EntityMatcher entityMatcher;

    public ContextFilterChain(EntityMatcher entityMatcher) {
        this.entityMatcher = entityMatcher;
    }

    public FilterOutcome apply(List<RetrievalCandidate> ranked,
                               RetrievalQuery query,
                               RagRuntimeSettings settings) {
        if (ranked == null || ranked.isEmpty()) {
            return new FilterOutcome(List.of(), 0, 0, 0, 0);
        }

        boolean entityFilterEnabled = settings.isEntityOverlapFilterEnabled();
        int maxFiles = Math.max(1, settings.getMaxFilesPerContext());
        int tokenBudget = Math.max(1, settings.getContextTokenBudget());
        String queryText = query == null ? null : query.queryText();

        List<RetrievalCandidate> kept = new ArrayList<>();
        Set<String> files = new LinkedHashSet<>();
        int droppedByEntity = 0;
        int droppedByFileLimit = 0;
        int droppedByTokenBudget = 0;
        int totalTokens = 0;

        for (RetrievalCandidate candidate : ranked) {
            if (entityFilterEnabled && !entityMatcher.hasOverlap(queryText, candidate.text())) {
                droppedByEntity++;
                continue;
            }
            String fileKey = candidate.fileName() == null ? candidate.docId() : candidate.fileName();
            if (!files.contains(fileKey)) {
                if (files.size() >= maxFiles) {
                    droppedByFileLimit++;
                    continue;
                }
                files.add(fileKey);
            }

            int tokens = EmbeddingTokenEstimator.estimate(candidate.text());
            if (totalTokens + tokens > tokenBudget && !kept.isEmpty()) {
                droppedByTokenBudget++;
                // 预算已满，后面的更小排名只会更差，直接停
                break;
            }
            kept.add(candidate);
            totalTokens += tokens;
        }

        return new FilterOutcome(List.copyOf(kept), droppedByEntity, droppedByFileLimit,
                droppedByTokenBudget, totalTokens);
    }

    /**
     * @param kept                 进入上下文的片段（已按最终分数排序）
     * @param droppedByEntity      实体无交集被剔除数
     * @param droppedByFileLimit   超文件数上限被剔除数
     * @param droppedByTokenBudget 超 Token 预算被剔除数
     * @param totalTokens          实际占用 token
     */
    public record FilterOutcome(List<RetrievalCandidate> kept,
                                int droppedByEntity,
                                int droppedByFileLimit,
                                int droppedByTokenBudget,
                                int totalTokens) {

        public int droppedTotal() {
            return droppedByEntity + droppedByFileLimit + droppedByTokenBudget;
        }
    }
}
