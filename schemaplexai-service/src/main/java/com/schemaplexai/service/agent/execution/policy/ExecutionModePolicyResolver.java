package com.schemaplexai.service.agent.execution.policy;

import com.schemaplexai.common.enums.ExecutionModeEnum;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 执行模式策略解析器
 */
@Component
public class ExecutionModePolicyResolver {

    public ExecutionModePolicy resolve(AgentExecutionContext context, Agent agent) {
        ExecutionModeEnum mode = ExecutionModeEnum.fromCode(context != null ? context.getExecutionMode() : null);
        return switch (mode) {
            case PLAN -> buildPlanPolicy();
            case SUGGEST -> buildSuggestPolicy();
            default -> buildAutoPolicy();
        };
    }

    private ExecutionModePolicy buildAutoPolicy() {
        return ExecutionModePolicy.builder()
                .mode(ExecutionModeEnum.AUTO)
                .allowReadTools(true)
                .allowWriteTools(true)
                .requireApprovalForWrite(true)
                .requireApprovalForDestructive(true)
                .allowProgressiveTrust(true)
                .suggestOnly(false)
                .applyToNonSystemAgent(true)
                .directlyExecutableIoTypes(Set.of(ToolIoTypeEnum.READ))
                .build();
    }

    private ExecutionModePolicy buildPlanPolicy() {
        return ExecutionModePolicy.builder()
                .mode(ExecutionModeEnum.PLAN)
                .allowReadTools(true)
                .allowWriteTools(true)
                .requireApprovalForWrite(true)
                .requireApprovalForDestructive(true)
                .allowProgressiveTrust(true)
                .suggestOnly(false)
                .applyToNonSystemAgent(true)
                .directlyExecutableIoTypes(Set.of(ToolIoTypeEnum.READ))
                .build();
    }

    private ExecutionModePolicy buildSuggestPolicy() {
        return ExecutionModePolicy.builder()
                .mode(ExecutionModeEnum.SUGGEST)
                .allowReadTools(false)
                .allowWriteTools(false)
                .requireApprovalForWrite(true)
                .requireApprovalForDestructive(true)
                .allowProgressiveTrust(false)
                .suggestOnly(true)
                .applyToNonSystemAgent(true)
                .directlyExecutableIoTypes(Set.of())
                .build();
    }
}
