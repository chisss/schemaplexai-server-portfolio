package com.schemaplexai.service.rag.retrieve;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Rerank 动态门禁。
 *
 * <p>Cross-Encoder 是整套方案里最贵的一环：对 Top20 逐条做 cross-attention 推理，
 * 每次查询都开会让 P95 延迟与算力成本同时失控。因此这里实现"按需开启"：
 *
 * <table>
 *   <tr><th>策略</th><th>行为</th><th>适用</th></tr>
 *   <tr><td>always</td><td>每次精排</td><td>高风险业务强制</td></tr>
 *   <tr><td>auto</td><td>按信号判定</td><td>默认</td></tr>
 *   <tr><td>never</td><td>只用 RRF</td><td>延迟敏感/预算告警</td></tr>
 * </table>
 *
 * <p>auto 的判定优先级：预算/上游超时（省钱省时）→ 租户强制 → 高风险业务 → 复杂查询 →
 * RRF 分数扁平（两路无共识）。前两条是"硬约束"，后面是"质量收益"。
 */
@Component
public class RerankGate {

    /** 扁平分差阈值（相对差） */
    static final double FLAT_GAP_THRESHOLD = 0.15D;

    /** 高风控业务标签：授信/合同/理赔等，误答代价高，值得精排 */
    private static final List<String> HIGH_RISK_TAGS = List.of(
            "授信", "信贷", "合同", "理赔", "风控", "合规", "审批", "额度", "定价",
            "credit", "contract", "claim", "risk", "compliance");

    /** 复杂推理/多约束查询的标志词 */
    private static final List<String> COMPLEX_MARKERS = List.of(
            "为什么", "如何", "怎么", "对比", "区别", "差异", "是否", "影响", "原因",
            "分别", "计算", "规则", "同时", "并且", "以及", "相较", "比较");

    private final RrfFusion rrfFusion;

    public RerankGate(RrfFusion rrfFusion) {
        this.rrfFusion = rrfFusion;
    }

    /**
     * @param settings 租户配置
     * @param query    检索请求（业务标签/强制开关）
     * @param fused    RRF 融合后的候选
     * @param signals  运行期信号（精排可用性、预算压力、上游超时）
     */
    public RerankDecision decide(RagRuntimeSettings settings,
                                 RetrievalQuery query,
                                 List<RetrievalCandidate> fused,
                                 RerankSignals signals) {
        if (signals == null || !signals.scorerAvailable()) {
            return RerankDecision.skip("SCORER_UNAVAILABLE");
        }
        if (!settings.isRerankActive()) {
            return RerankDecision.skip("RERANK_DISABLED");
        }
        if (fused == null || fused.isEmpty()) {
            return RerankDecision.skip("EMPTY_CANDIDATES");
        }
        if (settings.isRerankAlways()) {
            return RerankDecision.enable("POLICY_ALWAYS");
        }
        if (settings.getRerankPolicy() != null
                && "never".equalsIgnoreCase(settings.getRerankPolicy())) {
            return RerankDecision.skip("POLICY_NEVER");
        }

        // 以下是 auto：先看硬约束（省钱/省时），再看质量收益
        if (signals.budgetPressure()) {
            return RerankDecision.skip("BUDGET_PRESSURE");
        }
        if (signals.upstreamTimeout()) {
            return RerankDecision.skip("UPSTREAM_TIMEOUT");
        }
        if (query != null && query.forceRerank()) {
            return RerankDecision.enable("TENANT_FORCED");
        }
        if (query != null && isHighRiskBusiness(query.businessTag())) {
            return RerankDecision.enable("HIGH_RISK_BUSINESS");
        }
        if (query != null && isComplexQuery(query.queryText())) {
            return RerankDecision.enable("COMPLEX_QUERY");
        }
        if (rrfFusion.isFlatDistribution(fused, FLAT_GAP_THRESHOLD)) {
            return RerankDecision.enable("FLAT_DISTRIBUTION");
        }
        return RerankDecision.skip("SIMPLE_QUERY");
    }

    boolean isHighRiskBusiness(String businessTag) {
        if (!StringUtils.hasText(businessTag)) {
            return false;
        }
        String normalized = businessTag.toLowerCase(Locale.ROOT);
        return HIGH_RISK_TAGS.stream().anyMatch(normalized::contains);
    }

    boolean isComplexQuery(String queryText) {
        if (!StringUtils.hasText(queryText)) {
            return false;
        }
        String normalized = queryText.toLowerCase(Locale.ROOT);
        if (COMPLEX_MARKERS.stream().anyMatch(normalized::contains)) {
            return true;
        }
        // 长查询通常带多个约束条件，直接精排更稳
        if (queryText.length() >= 24) {
            return true;
        }
        // 多子句：逗号/分号/问号出现 2 次以上
        long clauses = queryText.chars()
                .filter(c -> c == '，' || c == ',' || c == '；' || c == ';' || c == '？' || c == '?')
                .count();
        return clauses >= 2;
    }

    /**
     * 运行期信号。
     *
     * @param scorerAvailable 精排模型是否加载成功
     * @param budgetPressure  租户 token 预算已告警
     * @param upstreamTimeout 上游召回是否已发生超时（此时更要抢延迟）
     */
    public record RerankSignals(boolean scorerAvailable, boolean budgetPressure, boolean upstreamTimeout) {

        public static RerankSignals normal() {
            return new RerankSignals(true, false, false);
        }

        public static RerankSignals unavailable() {
            return new RerankSignals(false, false, false);
        }
    }

    /** 门禁决策：是否精排 + 原因（原因要进埋点，便于解释"这次为什么慢/为什么没精排"） */
    public record RerankDecision(boolean rerank, String reason) {

        public static RerankDecision enable(String reason) {
            return new RerankDecision(true, reason);
        }

        public static RerankDecision skip(String reason) {
            return new RerankDecision(false, reason);
        }
    }
}
