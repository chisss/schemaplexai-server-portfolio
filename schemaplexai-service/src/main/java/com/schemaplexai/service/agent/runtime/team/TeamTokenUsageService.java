package com.schemaplexai.service.agent.runtime.team;

import com.schemaplexai.model.vo.agent.TeamTokenUsageSummaryVO;

/**
 * Team token 去重汇总服务
 */
public interface TeamTokenUsageService {

    TeamTokenUsageSummaryVO summarizeChildren(String parentExecutionId);
}
