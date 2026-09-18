package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.query.QueryCandidateRole;
import com.schemaplexai.service.semantic.domain.model.query.QueryInterpretationStatus;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticQueryInterpretationServiceTest {

    private final SemanticQueryInterpretationService service = new SemanticQueryInterpretationService();

    @Test
    void recognizesMetricDimensionTimeAndFilterFromChineseQuestion() {
        var result = service.interpret(
                "最近 30 天各城市已完成订单金额趋势",
                "version-1",
                "source-1",
                List.of(
                        candidate("urn:order:amount", QueryCandidateRole.METRIC, "订单金额", "sum"),
                        candidate("urn:order:city", QueryCandidateRole.DIMENSION, "城市", null),
                        candidate("urn:order:completedAt", QueryCandidateRole.TIME, "完成日期", null),
                        candidate("urn:order:status", QueryCandidateRole.FILTER, "订单状态", null)));

        assertThat(result.status()).isEqualTo(QueryInterpretationStatus.READY);
        assertThat(result.intent().metrics()).hasSize(1);
        assertThat(result.intent().dimensions()).extracting("iri").containsExactly("urn:order:city");
        assertThat(result.intent().filters().get(0).value()).isEqualTo("completed");
        assertThat(result.intent().timeRange().relativeDays()).isEqualTo(30);
    }

    @Test
    void doesNotSilentlyChooseAmbiguousMetric() {
        var result = service.interpret(
                "订单金额",
                "version-1",
                "source-1",
                List.of(
                        candidate("urn:order:amount", QueryCandidateRole.METRIC, "订单金额", "sum"),
                        candidate("urn:order:paidAmount", QueryCandidateRole.METRIC, "订单金额", "sum")));

        assertThat(result.status()).isEqualTo(QueryInterpretationStatus.CLARIFICATION);
        assertThat(result.clarifications()).extracting("code").contains("METRIC_AMBIGUOUS");
        assertThat(result.intent()).isNull();
    }

    @Test
    void ignoresUnmappedCandidateForExecution() {
        var result = service.interpret(
                "订单金额",
                "version-1",
                "source-1",
                List.of(new SemanticQueryCandidate(
                        "urn:order:amount", QueryCandidateRole.METRIC, List.of("订单金额"),
                        "source-2", "orders", "amount", "column")));

        assertThat(result.status()).isEqualTo(QueryInterpretationStatus.CLARIFICATION);
        assertThat(result.reasons()).contains("未识别到已映射指标");
    }

    private SemanticQueryCandidate candidate(
            String iri,
            QueryCandidateRole role,
            String alias,
            String aggregation) {
        return new SemanticQueryCandidate(
                iri, role, List.of(alias), "source-1", "orders", iri.substring(iri.lastIndexOf(':') + 1), "column", aggregation);
    }
}
