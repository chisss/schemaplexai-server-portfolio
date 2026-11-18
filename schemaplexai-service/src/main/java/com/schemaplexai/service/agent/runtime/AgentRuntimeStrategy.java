package com.schemaplexai.service.agent.runtime;

import com.schemaplexai.common.enums.AgentTypeEnum;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;

import java.util.concurrent.CompletableFuture;

/**
 * Agent 运行时策略
 */
public interface AgentRuntimeStrategy {

    AgentTypeEnum supportType();

    CompletableFuture<AgentExecutionResult> execute(Agent agent, AgentExecution execution, AgentExecutionContext context);

    /**
     * 恢复执行：context 由编排器统一构建（含 sandboxPolicy/executionMode 恢复），
     * 策略实现不得重建上下文，避免恢复路径丢失执行策略。
     */
    CompletableFuture<AgentExecutionResult> resume(Agent agent, AgentExecution execution,
                                                   AgentExecutionContext context, AgentExecutionInputDTO input);
}
