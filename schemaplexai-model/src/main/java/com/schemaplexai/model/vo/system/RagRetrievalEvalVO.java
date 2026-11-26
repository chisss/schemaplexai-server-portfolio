package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.util.List;

/**
 * 检索评测报告。
 */
@Data
public class RagRetrievalEvalVO {

    private Integer k;
    private Integer caseCount;
    /** 平均召回率：粗召回的底线指标 */
    private Double meanRecallAtK;
    /** 平均倒数排名：排序质量 */
    private Double meanMrr;
    /** 平均上下文 token：算力成本 */
    private Double avgContextTokens;
    /** 平均装配率：进入上下文条数 / 融合候选数 */
    private Double avgKeptRatio;
    /** 平均检索延迟（含召回与精排） */
    private Double avgLatencyMs;

    private List<CaseResult> cases;

    @Data
    public static class CaseResult {
        private String query;
        private Double recallAtK;
        private Double mrr;
        private Integer retrieved;
        private Integer contextTokens;
        private Double keptRatio;
        private Long latencyMs;
        /** 该 case 是否真正执行了精排（精排模型不可用/超时会为 false） */
        private Boolean rerankApplied;
    }
}
