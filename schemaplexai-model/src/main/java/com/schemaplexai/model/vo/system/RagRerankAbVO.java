package com.schemaplexai.model.vo.system;

import lombok.Data;

/**
 * 精排 A/B 对比报告。
 *
 * <p>回答一个具体的决策问题："这个租户/这个场景，到底值不值得开精排？"
 * 因此报告同时给出质量增量（Recall/MRR）与成本增量（token/延迟），而不是只报质量。
 */
@Data
public class RagRerankAbVO {

    private Integer k;
    private Integer caseCount;

    /** 基线：仅 RRF */
    private RagRetrievalEvalVO rrfOnly;

    /** 实验组：RRF + Cross-Encoder */
    private RagRetrievalEvalVO withRerank;

    /** 实验组中精排真正执行过的 case 数；为 0 表示精排模型不可用，delta 不可信 */
    private Integer rerankEffectiveCases;

    private Delta delta;

    /** 实验组 - 基线（正数代表实验组更高/更贵） */
    @Data
    public static class Delta {
        private Double recallAtK;
        private Double mrr;
        private Double avgContextTokens;
        private Double avgLatencyMs;
    }
}
