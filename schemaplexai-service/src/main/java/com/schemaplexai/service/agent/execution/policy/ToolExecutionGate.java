package com.schemaplexai.service.agent.execution.policy;

import com.schemaplexai.common.enums.ExecutionModeEnum;
import com.schemaplexai.common.enums.ToolExecutionDecisionEnum;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.service.agent.tool.ToolApprovalAmendmentService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 工具执行网关
 */
@Component
@RequiredArgsConstructor
public class ToolExecutionGate {

    private final ToolApprovalAmendmentService amendmentService;

    public ToolExecutionGateDecision evaluate(ToolExecutionGateRequest request) {
        ExecutionModePolicy policy = request.getPolicy() != null ? request.getPolicy() : defaultAutoPolicy();
        ToolIoTypeEnum ioType = request.getIoType() != null ? request.getIoType() : ToolIoTypeEnum.READ_WRITE;
        if (!request.isSystemAgent() && !policy.isApplyToNonSystemAgent()) {
            return execute("非系统 Agent 未启用执行模式网关");
        }
        if (policy.isSuggestOnly()) {
            return decision(ToolExecutionDecisionEnum.SUGGEST_ONLY, "建议模式仅展示候选操作，不执行真实工具");
        }
        if (!ioType.isWrite()) {
            return policy.isAllowReadTools()
                    ? execute("只读工具允许直接执行")
                    : decision(ToolExecutionDecisionEnum.DENY, "当前执行模式不允许读取工具");
        }
        if (!policy.isAllowWriteTools()) {
            return decision(ToolExecutionDecisionEnum.DENY, "当前执行模式不允许写入工具");
        }
        ToolExecutionRequest toolRequest = request.getToolRequest();
        String toolCode = toolRequest != null ? toolRequest.name() : null;
        String arguments = toolRequest != null ? toolRequest.arguments() : null;
        if (policy.isAllowProgressiveTrust()
                && amendmentService.isAutoApproved(request.getTenantId(), request.getAgentId(), toolCode, arguments)) {
            return execute("命中渐进信任规则，允许直接执行");
        }
        if (policy.isRequireApprovalForWrite()) {
            return decision(ToolExecutionDecisionEnum.PAUSE_APPROVAL, "写入工具需要用户审批");
        }
        return execute("当前策略允许写入工具直接执行");
    }

    private ToolExecutionGateDecision execute(String reason) {
        return decision(ToolExecutionDecisionEnum.EXECUTE, reason);
    }

    private ToolExecutionGateDecision decision(ToolExecutionDecisionEnum decision, String reason) {
        return ToolExecutionGateDecision.builder()
                .decision(decision)
                .reason(reason)
                .build();
    }

    private ExecutionModePolicy defaultAutoPolicy() {
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
}
