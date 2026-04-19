package com.schemaplexai.service.vector;

import java.util.List;

/**
 * 向量评分（Reranker）服务接口
 *
 * <p>对向量检索召回的候选文本进行二阶段精排（Cross-Encoder Scoring），
 * 提升最终返回结果的相关性。
 */
public interface ScoringService {

    /**
     * 对候选段落进行精排
     *
     * @param query    用户查询文本
     * @param passages 一阶段向量检索召回的候选段落列表
     * @param topN     精排后返回的最大数量
     * @return 精排后的段落列表（按评分降序），最多返回 topN 条
     */
    List<ScoredPassage> rerank(String query, List<String> passages, int topN);

    /**
     * 当前是否可用（模型已加载且配置启用）
     */
    boolean isAvailable();

    /**
     * 精排结果
     */
    record ScoredPassage(String text, double score) implements Comparable<ScoredPassage> {
        @Override
        public int compareTo(ScoredPassage other) {
            return Double.compare(other.score, this.score); // 降序
        }
    }
}
