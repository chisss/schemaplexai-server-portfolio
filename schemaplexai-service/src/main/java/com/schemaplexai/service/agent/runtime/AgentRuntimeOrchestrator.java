package com.schemaplexai.service.agent.runtime;

import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import com.schemaplexai.common.enums.AgentTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.execution.SandboxPolicyResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Agent 运行时统一编排入口
 */
@Service
@RequiredArgsConstructor
public class AgentRuntimeOrchestrator {

    private final AgentMapper agentMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentRuntimeStrategyFactory strategyFactory;
    private final SandboxPolicyResolver sandboxPolicyResolver;

    public CompletableFuture<AgentExecutionResult> execute(AgentExecutionContext context) {
        AgentExecution execution = requireExecution(context.getExecutionId());
        Agent agent = requireAgent(context.getAgentId());
        AgentRuntimeEngineEnum runtimeEngine = resolveRuntimeEngine(agent);
        context.setRuntimeEngine(runtimeEngine.getCode());
        SandboxPolicy sandboxPolicy = sandboxPolicyResolver.resolve(agent, context);
        context.setSandboxPolicy(sandboxPolicy);

        AgentExecution update = new AgentExecution();
        update.setId(execution.getId());
        update.setRuntimeEngine(runtimeEngine.getCode());
        update.setSandboxPolicySnapshot(sandboxPolicyResolver.toSnapshot(sandboxPolicy));
        agentExecutionMapper.updateById(update);

        return strategyFactory.getStrategy(agent.getAgentType()).execute(agent, execution, context);
    }

    public CompletableFuture<AgentExecutionResult> resume(String executionId, AgentExecutionInputDTO input) {
        AgentExecution execution = requireExecution(executionId);
        Agent agent = requireAgent(execution.getAgentId());
        AgentRuntimeEngineEnum runtimeEngine = resolveRuntimeEngine(agent);
        AgentExecutionContext context = AgentExecutionContext.builder()
                .executionId(execution.getId())
                .agentId(execution.getAgentId())
                .tenantId(execution.getTenantId())
                .inputPrompt(execution.getInputPrompt())
                .model(execution.getAiModel())
                .agentModelType(agent.getAiModelType())
                .agentModelGroupId(agent.getAiModelGroupId())
                .inputContext(execution.getInputContext())
                .conversationId(execution.getConversationId())
                .runtimeEngine(runtimeEngine.getCode())
                .build();
        SandboxPolicy sandboxPolicy = sandboxPolicyResolver.resolve(agent, context);
        AgentExecution update = new AgentExecution();
        update.setId(execution.getId());
        update.setRuntimeEngine(runtimeEngine.getCode());
        update.setSandboxPolicySnapshot(sandboxPolicyResolver.toSnapshot(sandboxPolicy));
        agentExecutionMapper.updateById(update);
        return strategyFactory.getStrategy(agent.getAgentType()).resume(agent, execution, input);
    }

    private AgentRuntimeEngineEnum resolveRuntimeEngine(Agent agent) {
        return AgentTypeEnum.TEAM == AgentTypeEnum.fromCode(agent.getAgentType())
                ? AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J
                : AgentRuntimeEngineEnum.SOLO_LANGCHAIN4J;
    }

    private Agent requireAgent(String agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ResultCode.AGENT_NOT_FOUND);
        }
        return agent;
    }

    private AgentExecution requireExecution(String executionId) {
        AgentExecution execution = agentExecutionMapper.selectById(executionId);
        if (execution == null) {
            throw new BusinessException(ResultCode.AGENT_EXECUTION_NOT_FOUND);
        }
        return execution;
    }
}
