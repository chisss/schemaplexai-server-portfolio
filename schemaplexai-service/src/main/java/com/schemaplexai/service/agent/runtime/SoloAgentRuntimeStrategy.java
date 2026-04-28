package com.schemaplexai.service.agent.runtime;

import com.schemaplexai.common.enums.AgentTypeEnum;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.execution.SandboxPolicyResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.CompletableFuture;

/**
 * Solo Agent 运行时策略
 */
@Component
@RequiredArgsConstructor
public class SoloAgentRuntimeStrategy implements AgentRuntimeStrategy {

    private final AgentExecutionEngine agentExecutionEngine;
    private final SandboxPolicyResolver sandboxPolicyResolver;

    @Override
    public AgentTypeEnum supportType() {
        return AgentTypeEnum.SOLO;
    }

    @Override
    public CompletableFuture<AgentExecutionResult> execute(Agent agent, AgentExecution execution, AgentExecutionContext context) {
        return agentExecutionEngine.execute(context);
    }

    @Override
    public CompletableFuture<AgentExecutionResult> resume(Agent agent, AgentExecution execution, AgentExecutionInputDTO input) {
        AgentExecutionContext context = AgentExecutionContext.builder()
                .executionId(execution.getId())
                .agentId(execution.getAgentId())
                .tenantId(execution.getTenantId())
                .userId(execution.getCreatedBy())
                .inputPrompt(execution.getInputPrompt())
                .model(execution.getAiModel())
                .agentModelType(agent.getAiModelType())
                .agentModelGroupId(agent.getAiModelGroupId())
                .inputContext(execution.getInputContext())
                .conversationId(execution.getConversationId())
                .runtimeEngine(execution.getRuntimeEngine())
                .executionMode(StringUtils.hasText(execution.getExecutionMode()) ? execution.getExecutionMode() : "auto")
                .systemAgent(Boolean.TRUE.equals(agent.getIsSystemAgent()))
                .temporaryChat(Boolean.TRUE.equals(input.getTemporaryChat()))
                .memoryWriteEnabled(input.getMemoryWriteEnabled())
                .build();
        context.setSandboxPolicy(sandboxPolicyResolver.resolve(agent, context));
        return agentExecutionEngine.resume(context, input);
    }
}
