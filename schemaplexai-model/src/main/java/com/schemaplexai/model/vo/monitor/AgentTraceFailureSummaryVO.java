package com.schemaplexai.model.vo.monitor;

import lombok.Data;

import java.util.List;

/**
 * Agent Trace 失败摘要
 */
@Data
public class AgentTraceFailureSummaryVO {

    private String traceId;

    private Long blockingCount;

    private Long recoverableCount;

    private List<AgentTraceFailureCategoryVO> categories;
}
