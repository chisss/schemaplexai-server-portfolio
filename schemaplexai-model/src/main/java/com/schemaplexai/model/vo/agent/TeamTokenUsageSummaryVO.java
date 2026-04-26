package com.schemaplexai.model.vo.agent;

import lombok.Data;

/**
 * Team token 去重汇总
 */
@Data
public class TeamTokenUsageSummaryVO {

    private String parentExecutionId;

    private Long childExecutionCount;

    private Long tokenInput;

    private Long tokenOutput;

    private Long totalTokens;
}
