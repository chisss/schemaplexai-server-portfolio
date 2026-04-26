package com.schemaplexai.model.vo.agent;

import lombok.Data;

/**
 * Agent 上下文预算层级
 */
@Data
public class AgentContextBudgetLayerVO {

    private String name;

    private Integer chars;

    private Long estimatedTokens;
}
