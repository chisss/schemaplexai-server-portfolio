package com.schemaplexai.service.agent.runtime.team;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.vo.agent.TeamTokenUsageSummaryVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Team token 去重汇总服务实现
 */
@Service
@RequiredArgsConstructor
public class TeamTokenUsageServiceImpl implements TeamTokenUsageService {

    private final AgentExecutionMapper agentExecutionMapper;

    @Override
    public TeamTokenUsageSummaryVO summarizeChildren(String parentExecutionId) {
        TeamTokenUsageSummaryVO summary = new TeamTokenUsageSummaryVO();
        summary.setParentExecutionId(parentExecutionId);
        if (!StringUtils.hasText(parentExecutionId)) {
            summary.setChildExecutionCount(0L);
            summary.setTokenInput(0L);
            summary.setTokenOutput(0L);
            summary.setTotalTokens(0L);
            return summary;
        }
        List<AgentExecution> children = agentExecutionMapper.selectList(new LambdaQueryWrapper<AgentExecution>()
                .eq(AgentExecution::getParentExecutionId, parentExecutionId));
        long input = children.stream().mapToLong(item -> item.getTokenInput() == null ? 0L : item.getTokenInput()).sum();
        long output = children.stream().mapToLong(item -> item.getTokenOutput() == null ? 0L : item.getTokenOutput()).sum();
        summary.setChildExecutionCount((long) children.size());
        summary.setTokenInput(input);
        summary.setTokenOutput(output);
        summary.setTotalTokens(input + output);
        return summary;
    }
}
