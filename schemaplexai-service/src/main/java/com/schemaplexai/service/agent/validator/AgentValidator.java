package com.schemaplexai.service.agent.validator;

import com.schemaplexai.common.enums.AgentStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.service.common.EntityValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Agent校验器 — 封装状态校验和业务规则
 */
@Component
@RequiredArgsConstructor
public class AgentValidator {

    private final AgentMapper agentMapper;
    private final EntityValidator entityValidator;

    /**
     * 校验Agent名称唯一性
     */
    public void validateNameUnique(String name) {
        entityValidator.checkUnique(agentMapper, Agent::getName, name,
                ResultCode.AGENT_NAME_DUPLICATE);
    }

    /**
     * 校验Agent状态为active
     */
    public void validateActive(Agent agent) {
        if (!AgentStatusEnum.ACTIVE.getCode().equals(agent.getStatus())) {
            throw new BusinessException(ResultCode.AGENT_INACTIVE);
        }
    }

    /**
     * 校验Agent不处于running状态
     */
    public void validateNotBusy(Agent agent) {
        if (AgentStatusEnum.RUNNING.getCode().equals(agent.getStatus())) {
            throw new BusinessException(ResultCode.AGENT_BUSY);
        }
    }
}
