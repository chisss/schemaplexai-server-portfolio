package com.schemaplexai.service.rag.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索指标口径：Recall@K 与 MRR。
 */
class RetrievalMetricsTest {

    @Test
    @DisplayName("Recall@K：期望文档全部命中的比例")
    void shouldComputeRecallAtK() {
        List<String> expected = List.of("doc-1", "doc-2");
        List<String> retrieved = List.of("doc-1", "doc-9", "doc-2");

        assertThat(RetrievalMetrics.recallAtK(expected, retrieved, 3)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.recallAtK(expected, retrieved, 1)).isEqualTo(0.5);
        assertThat(RetrievalMetrics.recallAtK(expected, retrieved, 0)).isZero();
    }

    @Test
    @DisplayName("期望文档为空时视为不惩罚（返回 1）")
    void shouldNotPenalizeWhenNoExpected() {
        assertThat(RetrievalMetrics.recallAtK(List.of(), List.of("doc-1"), 5)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("未命中时 Recall 为 0")
    void shouldReturnZeroWhenNothingHit() {
        assertThat(RetrievalMetrics.recallAtK(List.of("doc-1"), List.of("doc-2", "doc-3"), 5)).isZero();
    }

    @Test
    @DisplayName("MRR：第一条命中的倒数排名")
    void shouldComputeReciprocalRank() {
        assertThat(RetrievalMetrics.reciprocalRank(List.of("doc-2"), List.of("doc-1", "doc-2"))).isEqualTo(0.5);
        assertThat(RetrievalMetrics.reciprocalRank(List.of("doc-1"), List.of("doc-1"))).isEqualTo(1.0);
        assertThat(RetrievalMetrics.reciprocalRank(List.of("doc-9"), List.of("doc-1"))).isZero();
    }

    @Test
    @DisplayName("汇总：均值、上下文成本、装配率")
    void shouldAggregateResults() {
        List<RetrievalMetrics.CaseResult> results = List.of(
                new RetrievalMetrics.CaseResult("q1", 1.0, 1.0, 3, 1000, 0.5, 120),
                new RetrievalMetrics.CaseResult("q2", 0.0, 0.0, 2, 500, 0.25, 80));

        RetrievalMetrics.Aggregate aggregate = RetrievalMetrics.aggregate(results, 20);

        assertThat(aggregate.caseCount()).isEqualTo(2);
        assertThat(aggregate.meanRecallAtK()).isEqualTo(0.5);
        assertThat(aggregate.meanMrr()).isEqualTo(0.5);
        assertThat(aggregate.avgContextTokens()).isEqualTo(750.0);
        assertThat(aggregate.avgKeptRatio()).isEqualTo(0.375);
    }

    @Test
    @DisplayName("空结果集不抛异常")
    void shouldHandleEmptyResults() {
        RetrievalMetrics.Aggregate aggregate = RetrievalMetrics.aggregate(List.of(), 20);

        assertThat(aggregate.caseCount()).isZero();
        assertThat(aggregate.meanRecallAtK()).isZero();
    }
}
