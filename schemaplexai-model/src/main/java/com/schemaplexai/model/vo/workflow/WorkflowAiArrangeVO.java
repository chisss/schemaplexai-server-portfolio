package com.schemaplexai.model.vo.workflow;

import lombok.Data;

import java.util.Map;

/**
 * AI工作流编排结果VO
 */
@Data
public class WorkflowAiArrangeVO {

    /** AI建议的工作流定义（ReactFlow 格式） */
    private Map<String, Object> suggestedDefinition;

    /** AI编排说明 */
    private String explanation;

    /** 执行ID（异步编排时使用） */
    private String executionId;

    /** 状态: pending/completed/failed */
    private String status;
}
