package com.schemaplexai.service.rag.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索层指标（设计稿第四章的"召回层指标"）。
 *
 * <p>只算两个最基础、最不容易被自欺的指标：
 * <ul>
 *   <li><b>Recall@K</b>：标准答案里的片段，有多少进了前 K。粗召回是底线，
 *       召回不到的东西，Reranker 再强也救不回来；</li>
 *   <li><b>MRR</b>：第一条正确答案排在第几位。用于观察排序质量，
 *       尤其适合金融问答"用户只看第一条"的场景。</li>
 * </ul>
 *
 * <p>刻意不做 NDCG：需要人工标注相关性等级，本平台暂无该标注能力，
 * 硬上只会得到一个没人信的数字。
 */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /**
     * Recall@K。
     *
     * @param expectedIds  标准答案的文档/片段 ID
     * @param retrievedIds 实际召回的 ID（按排名）
     * @param k            截断位
     * @return 0~1；expectedIds 为空时返回 1（无标准答案视为不惩罚）
     */
    public static double recallAtK(List<String> expectedIds, List<String> retrievedIds, int k) {
        if (expectedIds == null || expectedIds.isEmpty()) {
            return 1.0D;
        }
        if (retrievedIds == null || retrievedIds.isEmpty() || k <= 0) {
            return 0.0D;
        }
        Set<String> expected = new HashSet<>(expectedIds);
        Set<String> hit = new HashSet<>();
        int limit = Math.min(k, retrievedIds.size());
        for (int i = 0; i < limit; i++) {
            String id = retrievedIds.get(i);
            if (expected.contains(id)) {
                hit.add(id);
            }
        }
        return hit.size() / (double) expected.size();
    }

    /**
     * MRR（Mean Reciprocal Rank 的单条版本）。
     *
     * @return 第一条命中的倒数排名；无命中返回 0
     */
    public static double reciprocalRank(List<String> expectedIds, List<String> retrievedIds) {
        if (expectedIds == null || expectedIds.isEmpty() || retrievedIds == null) {
            return 0.0D;
        }
        Set<String> expected = new HashSet<>(expectedIds);
        for (int i = 0; i < retrievedIds.size(); i++) {
            if (expected.contains(retrievedIds.get(i))) {
                return 1.0D / (i + 1);
            }
        }
        return 0.0D;
    }

    /**
     * 单条评测结果。
     *
     * @param contextTokens   装配后上下文 token 数（成本视角）
     * @param keptRatio       进入上下文条数 / 融合候选数（装配率）
     */
    public record CaseResult(String query,
                             double recallAtK,
                             double mrr,
                             int retrieved,
                             int contextTokens,
                             double keptRatio,
                             long latencyMs) {
    }

    /**
     * 汇总报告。
     *
     * @param meanRecallAtK   平均召回率：质量下限
     * @param meanMrr         平均首条命中位置：排序质量
     * @param avgContextTokens 平均上下文体积：算力成本
     * @param avgKeptRatio    平均装配率：过滤链是否砍得过狠
     */
    public record Aggregate(int caseCount,
                            int k,
                            double meanRecallAtK,
                            double meanMrr,
                            double avgContextTokens,
                            double avgKeptRatio) {
    }

    public static Aggregate aggregate(List<CaseResult> results, int k) {
        if (results == null || results.isEmpty()) {
            return new Aggregate(0, k, 0, 0, 0, 0);
        }
        double recall = results.stream().mapToDouble(CaseResult::recallAtK).average().orElse(0);
        double mrr = results.stream().mapToDouble(CaseResult::mrr).average().orElse(0);
        double tokens = results.stream().mapToInt(CaseResult::contextTokens).average().orElse(0);
        double keptRatio = results.stream().mapToDouble(CaseResult::keptRatio).average().orElse(0);
        return new Aggregate(results.size(), k, round(recall), round(mrr), round(tokens), round(keptRatio));
    }

    private static double round(double value) {
        return Math.round(value * 10000) / 10000.0D;
    }
}
