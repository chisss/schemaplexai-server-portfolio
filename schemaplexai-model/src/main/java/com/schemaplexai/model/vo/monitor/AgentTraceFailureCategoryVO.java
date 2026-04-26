package com.schemaplexai.model.vo.monitor;

import lombok.Data;

/**
 * Agent Trace 失败分类聚合
 */
@Data
public class AgentTraceFailureCategoryVO {

    private String category;

    private Long count;

    private Boolean recoverable;

    private String sampleReason;
}
