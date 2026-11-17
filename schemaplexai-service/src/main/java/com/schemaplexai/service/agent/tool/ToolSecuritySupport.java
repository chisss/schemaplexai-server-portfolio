package com.schemaplexai.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 工具安全守卫共享逻辑（DefaultToolRegistry 与 GuardedToolExecutor 复用，消除双实现）
 */
public final class ToolSecuritySupport {

    private ToolSecuritySupport() {
    }

    /**
     * 决策是否需要中断执行（block / pause）
     */
    public static boolean requiresInterrupt(SecurityCheckDecisionVO decision) {
        if (decision == null || !StringUtils.hasText(decision.getDecision())) {
            return false;
        }
        return SecurityComplianceConstant.DECISION_BLOCK.equals(decision.getDecision())
                || SecurityComplianceConstant.DECISION_PAUSE.equals(decision.getDecision());
    }

    /**
     * 构建被拦截/暂停的工具结果（controlAction 供上层审批机制消费）
     */
    public static ToolResult interruptedResult(ToolCall toolCall, SecurityCheckDecisionVO decision, ObjectMapper objectMapper) {
        return ToolResult.builder()
                .callId(toolCall != null ? toolCall.getCallId() : null)
                .toolCode(toolCall != null ? toolCall.getToolCode() : null)
                .success(false)
                .result(objectMapper.nullNode())
                .errorMessage(decision != null ? decision.getMessage() : null)
                .controlAction(decision != null ? decision.getDecision() : null)
                .build();
    }

    /**
     * 构建运行时安全检查请求
     */
    public static SecurityRuntimeCheckRequest buildCheckRequest(String scene, String tenantId, String agentId,
                                                                ToolCall toolCall, ToolResult toolResult,
                                                                ObjectMapper objectMapper) {
        SecurityRuntimeCheckRequest request = new SecurityRuntimeCheckRequest();
        request.setTenantId(tenantId);
        request.setScene(scene);
        request.setDomainCode(SecurityComplianceConstant.DOMAIN_RUNTIME);
        request.setResourceType(SecurityComplianceConstant.RESOURCE_TYPE_AGENT);
        request.setResourceId(agentId);
        request.setAgentId(agentId);
        request.setToolCode(toolCall != null ? toolCall.getToolCode() : null);
        if (toolCall != null && toolCall.getArguments() != null && !toolCall.getArguments().isNull()) {
            request.setArguments(objectMapper.convertValue(toolCall.getArguments(), Map.class));
        }
        if (toolResult != null) {
            request.setContent(String.valueOf(toolResult.getResult()));
        }
        return request;
    }
}
