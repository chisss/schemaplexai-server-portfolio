package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.util.List;

/**
 * Agent 上下文预算快照
 */
@Data
public class AgentContextBudgetSnapshotVO {

    private String model;

    private Long estimatedInputTokens;

    private Long maxTokenBudget;

    private Boolean overBudget;

    private Boolean compacted;

    private Long tokensSavedByCompaction;

    private List<AgentContextBudgetLayerVO> layers;
}
