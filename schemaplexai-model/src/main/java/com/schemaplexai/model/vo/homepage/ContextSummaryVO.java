package com.schemaplexai.model.vo.homepage;

import lombok.Data;

/**
 * 情境摘要
 */
@Data
public class ContextSummaryVO {

    private int pendingApprovalCount;
    private int blockedWorkflowCount;
    private int runningWorkflowCount;
    private int securityEventCount;
    private int qualityDeviationCount;

    /** AI 生成的自然语言摘要 */
    private String summaryText;
}
