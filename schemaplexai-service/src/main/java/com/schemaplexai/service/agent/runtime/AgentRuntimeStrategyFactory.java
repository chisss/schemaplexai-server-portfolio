package com.schemaplexai.service.agent.runtime;

import com.schemaplexai.common.enums.AgentTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 运行时策略工厂
 */
@Component
@RequiredArgsConstructor
public class AgentRuntimeStrategyFactory {

    private final List<AgentRuntimeStrategy> strategies;

    public AgentRuntimeStrategy getStrategy(String agentType) {
        AgentTypeEnum typeEnum = AgentTypeEnum.fromCode(agentType);
        return strategies.stream()
                .filter(strategy -> strategy.supportType() == typeEnum)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.BAD_REQUEST, "不支持的 Agent 运行类型: " + agentType));
    }
}
