package com.schemaplexai.service.rag.retrieve;

import com.schemaplexai.service.rag.RagDefaults;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rerank 门禁：策略优先级、成本/延迟硬约束、高风险与复杂查询的收益判定。
 */
class RerankGateTest {

    private final RerankGate gate = new RerankGate(new RrfFusion());

    @Test
    @DisplayName("策略 never 时永不开精排")
    void shouldNeverRerankWhenPolicyNever() {
        RerankGate.RerankDecision decision = gate.decide(
                settings(true, "never"), query("现金贷额度是多少", null, false),
                flatCandidates(), RerankGate.RerankSignals.normal());

        assertThat(decision.rerank()).isFalse();
        assertThat(decision.reason()).isEqualTo("RERANK_DISABLED");
    }

    @Test
    @DisplayName("策略 always 时无条件精排")
    void shouldAlwaysRerankWhenPolicyAlways() {
        RerankGate.RerankDecision decision = gate.decide(
                settings(true, "always"), query("额度", null, false),
                flatCandidates(), RerankGate.RerankSignals.normal());

        assertThat(decision.rerank()).isTrue();
        assertThat(decision.reason()).isEqualTo("POLICY_ALWAYS");
    }

    @Test
    @DisplayName("精排模型不可用时不排队等，直接跳过")
    void shouldSkipWhenScorerUnavailable() {
        RerankGate.RerankDecision decision = gate.decide(
                settings(true, "always"), query("额度", null, false),
                flatCandidates(), RerankGate.RerankSignals.unavailable());

        assertThat(decision.rerank()).isFalse();
        assertThat(decision.reason()).isEqualTo("SCORER_UNAVAILABLE");
    }

    @Test
    @DisplayName("预算告警优先于一切质量收益：先省钱")
    void shouldSkipWhenBudgetPressure() {
        RerankGate.RerankDecision decision = gate.decide(
                settings(true, "auto"), query("授信合同条款对比", "授信", true),
                flatCandidates(), new RerankGate.RerankSignals(true, true, false));

        assertThat(decision.rerank()).isFalse();
        assertThat(decision.reason()).isEqualTo("BUDGET_PRESSURE");
    }

    @Test
    @DisplayName("上游超时也不再加精排，避免延迟叠加")
    void shouldSkipWhenUpstreamTimeout() {
        RerankGate.RerankDecision decision = gate.decide(
                settings(true, "auto"), query("授信合同条款", "授信", false),
                flatCandidates(), new RerankGate.RerankSignals(true, false, true));

        assertThat(decision.rerank()).isFalse();
        assertThat(decision.reason()).isEqualTo("UPSTREAM_TIMEOUT");
    }

    @Test
    @DisplayName("高风险业务在 auto 下开精排")
    void shouldRerankForHighRiskBusiness() {
        RerankGate.RerankDecision decision = gate.decide(
                settings(true, "auto"), query("额度", "理赔", false),
                flatCandidates(), RerankGate.RerankSignals.normal());

        assertThat(decision.rerank()).isTrue();
        assertThat(decision.reason()).isEqualTo("HIGH_RISK_BUSINESS");
    }

    @Test
    @DisplayName("复杂多约束查询在 auto 下开精排")
    void shouldRerankForComplexQuery() {
        RerankGate.RerankDecision decision = gate.decide(
                settings(true, "auto"), query("提前还款是否收取违约金，以及逾期罚息如何计算", null, false),
                List.of(), RerankGate.RerankSignals.normal());

        // 复杂查询判定先于候选空判断？空候选直接跳过
        assertThat(decision.reason()).isEqualTo("EMPTY_CANDIDATES");

        RerankGate.RerankDecision withCandidates = gate.decide(
                settings(true, "auto"), query("提前还款是否收取违约金，以及逾期罚息如何计算", null, false),
                flatCandidates(), RerankGate.RerankSignals.normal());
        assertThat(withCandidates.rerank()).isTrue();
        assertThat(withCandidates.reason()).isEqualTo("COMPLEX_QUERY");
    }

    @Test
    @DisplayName("FAQ 式短查询且分布不扁平时不开精排，省算力")
    void shouldSkipSimpleFaqQuery() {
        List<RetrievalCandidate> peaked = List.of(
                new RetrievalCandidate("c1", "d1", "faq.pdf", "正文", "document", "如何还款",
                        null, 1, null, 0.9, 0.032, null, "bm25+vector"),
                new RetrievalCandidate("c2", "d2", "faq.pdf", "正文", "document", "还款方式",
                        null, 2, null, 0.5, 0.016, null, "vector"),
                new RetrievalCandidate("c3", "d3", "faq.pdf", "正文", "document", "还款日",
                        null, 3, null, 0.4, 0.012, null, "vector"),
                new RetrievalCandidate("c4", "d4", "faq.pdf", "正文", "document", "还款渠道",
                        null, 4, null, 0.3, 0.010, null, "vector"),
                new RetrievalCandidate("c5", "d5", "faq.pdf", "正文", "document", "还款失败",
                        null, 5, null, 0.2, 0.008, null, "vector"));

        RerankGate.RerankDecision decision = gate.decide(
                settings(true, "auto"), query("还款日是哪天", null, false),
                peaked, RerankGate.RerankSignals.normal());

        assertThat(decision.rerank()).isFalse();
        assertThat(decision.reason()).isEqualTo("SIMPLE_QUERY");
    }

    @Test
    @DisplayName("租户强制开关在 auto 下优先生效")
    void shouldRespectForcedRerank() {
        RerankGate.RerankDecision decision = gate.decide(
                settings(true, "auto"), query("怎么还款", null, true),
                flatCandidates(), RerankGate.RerankSignals.normal());

        assertThat(decision.rerank()).isTrue();
        assertThat(decision.reason()).isEqualTo("TENANT_FORCED");
    }

    @Test
    @DisplayName("业务标签识别覆盖中英文")
    void shouldRecognizeBizTags() {
        assertThat(gate.isHighRiskBusiness("授信审批")).isTrue();
        assertThat(gate.isHighRiskBusiness("contract-review")).isTrue();
        assertThat(gate.isHighRiskBusiness("faq")).isFalse();
        assertThat(gate.isHighRiskBusiness(null)).isFalse();
    }

    private RagRuntimeSettings settings(boolean rerankerEnabled, String policy) {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .rerankerEnabled(rerankerEnabled)
                .rerankPolicy(policy)
                .rerankerTopN(RagDefaults.RERANK_TOP_N)
                .build();
    }

    private RetrievalQuery query(String text, String businessTag, boolean force) {
        return new RetrievalQuery("tenant-1", List.of("proj-A"), text, null, businessTag, force);
    }

    /** 构造分布扁平的候选（RRF 分差极小） */
    private List<RetrievalCandidate> flatCandidates() {
        List<RetrievalCandidate> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            list.add(new RetrievalCandidate("c" + i, "d" + i, "f" + i + ".pdf", "正文", "document",
                    "文本" + i, i + 1, null, 1.0, null,
                    1.0 / (60 + i + 1), null, "bm25"));
        }
        return list;
    }
}
