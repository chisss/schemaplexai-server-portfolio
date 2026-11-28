package com.schemaplexai.service.security;

import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.agent.execution.AgentAcceptance;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 输出安全终态守卫。
 *
 * <p>统一 Solo、Team 与工作流节点的输出检查，并把服务缺失、检查异常和未知决策
 * 归一为 {@link Decision#ERROR}。调用方不得把 ERROR 当成 ALLOW。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentOutputSecurityGuard {

    private final ObjectProvider<SecurityRuntimeGuardService> securityRuntimeGuardServiceProvider;

    public OutputGuardResult evaluate(OutputGuardContext context) {
        if (context == null || !StringUtils.hasText(context.content())) {
            return OutputGuardResult.error("输出安全检查失败: 终稿内容为空");
        }
        SecurityRuntimeGuardService guard;
        try {
            guard = securityRuntimeGuardServiceProvider.getIfAvailable();
        } catch (RuntimeException exception) {
            log.error("获取输出安全 Guard 失败: executionId={}", context.executionId(), exception);
            return OutputGuardResult.error("输出安全检查服务不可用");
        }
        if (guard == null) {
            return OutputGuardResult.error("输出安全检查服务未配置");
        }

        try {
            SecurityCheckDecisionVO policyDecision = guard.evaluate(buildRequest(context), null);
            if (policyDecision == null || !StringUtils.hasText(policyDecision.getDecision())) {
                return OutputGuardResult.error("输出安全检查未返回有效决策");
            }
            String decision = policyDecision.getDecision().trim().toLowerCase();
            String message = StringUtils.hasText(policyDecision.getMessage())
                    ? policyDecision.getMessage()
                    : defaultMessage(decision);
            return switch (decision) {
                case SecurityComplianceConstant.DECISION_ALLOW ->
                        new OutputGuardResult(Decision.ALLOW, message, policyDecision);
                case SecurityComplianceConstant.DECISION_WARN ->
                        new OutputGuardResult(Decision.WARN, message, policyDecision);
                case SecurityComplianceConstant.DECISION_BLOCK ->
                        new OutputGuardResult(Decision.BLOCK, message, policyDecision);
                case SecurityComplianceConstant.DECISION_PAUSE ->
                        new OutputGuardResult(Decision.PAUSE, message, policyDecision);
                default -> OutputGuardResult.error("输出安全检查返回未知决策: " + decision);
            };
        } catch (RuntimeException exception) {
            log.error("输出安全检查异常，按失败关闭处理: executionId={}, resourceType={}",
                    context.executionId(), context.resourceType(), exception);
            return OutputGuardResult.error("输出安全检查异常: " + safeMessage(exception));
        }
    }

    private SecurityRuntimeCheckRequest buildRequest(OutputGuardContext context) {
        SecurityRuntimeCheckRequest request = new SecurityRuntimeCheckRequest();
        request.setTenantId(context.tenantId());
        request.setScene(SecurityComplianceConstant.CHECK_SCENE_OUTPUT);
        request.setDomainCode(SecurityComplianceConstant.DOMAIN_RUNTIME);
        request.setResourceType(context.resourceType());
        request.setResourceId(context.resourceId());
        request.setResourceName(context.resourceName());
        request.setAgentId(context.agentId());
        request.setWorkflowInstanceId(context.workflowInstanceId());
        request.setWorkflowNodeId(context.workflowNodeId());
        request.setWorkspaceId(context.workspaceId());
        request.setContent(context.content());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("guardPhase", "terminal");
        metadata.put("contentSha256", AgentAcceptance.outputSha256(context.content()));
        if (StringUtils.hasText(context.displayContent())) {
            metadata.put("displayContentSha256", AgentAcceptance.outputSha256(context.displayContent()));
        }
        if (context.metadata() != null && !context.metadata().isEmpty()) {
            metadata.putAll(context.metadata());
        }
        request.setContext(metadata);
        return request;
    }

    private String defaultMessage(String decision) {
        return switch (decision) {
            case SecurityComplianceConstant.DECISION_BLOCK -> "输出内容命中安全策略，已被阻断";
            case SecurityComplianceConstant.DECISION_PAUSE -> "输出内容需要人工复核";
            case SecurityComplianceConstant.DECISION_WARN -> "输出内容存在安全警告";
            default -> "输出安全检查通过";
        };
    }

    private String safeMessage(Throwable throwable) {
        return throwable != null && StringUtils.hasText(throwable.getMessage())
                ? throwable.getMessage()
                : throwable != null ? throwable.getClass().getSimpleName() : "unknown";
    }

    public record OutputGuardContext(
            String tenantId,
            String agentId,
            String executionId,
            String resourceType,
            String resourceId,
            String resourceName,
            String workflowInstanceId,
            String workflowNodeId,
            String workspaceId,
            String content,
            String displayContent,
            Map<String, Object> metadata
    ) {
        public OutputGuardContext {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record OutputGuardResult(Decision decision, String message,
                                    SecurityCheckDecisionVO policyDecision) {
        public static OutputGuardResult error(String message) {
            return new OutputGuardResult(Decision.ERROR, message, null);
        }

        public boolean allowed() {
            return decision == Decision.ALLOW || decision == Decision.WARN;
        }

        public boolean blocked() {
            return decision == Decision.BLOCK || decision == Decision.ERROR;
        }

        public boolean paused() {
            return decision == Decision.PAUSE;
        }
    }

    public enum Decision {
        ALLOW,
        WARN,
        BLOCK,
        PAUSE,
        ERROR
    }
}
