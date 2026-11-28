package com.schemaplexai.service.agent.tool.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.agent.execution.SandboxGuard;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.tool.ToolSecuritySupport;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * 统一工具安全装饰器（Agent 主链可信执行入口）。
 *
 * <p>所有工具执行（本地/Skill/MCP）必须经过本装饰器：
 * ① 沙箱前置校验（策略缺失 fail-closed，由 {@link SandboxGuard} 裁决）
 * ② 运行时合规前置检查（block/pause 直接拦截）
 * ③ 委托执行
 * ④ 运行时合规后置检查（拦截结果以 controlAction 供审批机制消费）。</p>
 */
@Slf4j
public class GuardedToolExecutor implements dev.langchain4j.service.tool.ToolExecutor {

    private final dev.langchain4j.service.tool.ToolExecutor delegate;
    private final ObjectMapper objectMapper;
    private final String tenantId;
    private final String agentId;
    private final String executionId;
    private final AgentToolBinding binding;
    private final String sourceType;
    private final SandboxPolicy sandboxPolicy;
    private final SandboxGuard sandboxGuard;
    private final SecurityRuntimeGuardService securityRuntimeGuardService;

    public GuardedToolExecutor(dev.langchain4j.service.tool.ToolExecutor delegate,
                               ObjectMapper objectMapper,
                               String tenantId,
                               String agentId,
                               AgentToolBinding binding,
                               String sourceType,
                               SandboxPolicy sandboxPolicy,
                               SandboxGuard sandboxGuard,
                               SecurityRuntimeGuardService securityRuntimeGuardService) {
        this(delegate, objectMapper, tenantId, agentId, binding, sourceType, sandboxPolicy, sandboxGuard,
                securityRuntimeGuardService, null);
    }

    GuardedToolExecutor(dev.langchain4j.service.tool.ToolExecutor delegate,
                        ObjectMapper objectMapper, String tenantId, String agentId,
                        AgentToolBinding binding, String sourceType,
                        SandboxPolicy sandboxPolicy, SandboxGuard sandboxGuard,
                        SecurityRuntimeGuardService securityRuntimeGuardService, String executionId) {
        this.delegate = delegate;
        this.objectMapper = objectMapper;
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.executionId = executionId;
        this.binding = binding;
        this.sourceType = sourceType;
        this.sandboxPolicy = sandboxPolicy;
        this.sandboxGuard = sandboxGuard;
        this.securityRuntimeGuardService = securityRuntimeGuardService;
    }

    boolean isDesktopTool() {
        return delegate instanceof LangChain4jBindingToolExecutor executor && executor.isDesktopTool();
    }

    public String sourceType() {
        return sourceType;
    }

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext invocationContext) {
        String actualToolCode = binding != null && StringUtils.hasText(binding.getToolCode())
                ? binding.getToolCode()
                : request.name();
        ParsedArguments parsedArguments;
        try {
            parsedArguments = parseArguments(request.arguments(), actualToolCode);
        } catch (IllegalArgumentException exception) {
            ToolCall rejected = ToolCall.builder()
                    .callId(request.id())
                    .toolCode(actualToolCode)
                    .arguments(objectMapper.createObjectNode())
                    .build();
            return blockedResult(rejected, exception.getMessage(), null);
        }
        ToolExecutionRequest canonicalRequest = ToolExecutionRequest.builder()
                .id(request.id())
                .name(request.name())
                .arguments(parsedArguments.canonicalJson())
                .build();
        ToolCall toolCall = ToolCall.builder()
                .callId(request.id())
                .toolCode(actualToolCode)
                .arguments(parsedArguments.node())
                .build();

        // ① 沙箱前置校验（null 策略 fail-closed）
        String sandboxError = sandboxGuard.validateTool(sandboxPolicy, sourceType, toolCall, tenantId);
        if (StringUtils.hasText(sandboxError)) {
            log.warn("工具被沙箱守卫拦截: toolCode={}, error={}", toolCall.getToolCode(), sandboxError);
            return blockedResult(toolCall, sandboxError, null);
        }

        // ② 运行时合规前置检查
        if (securityRuntimeGuardService != null) {
            SecurityCheckDecisionVO preDecision = securityRuntimeGuardService.evaluate(
                    buildCheckRequest(SecurityComplianceConstant.CHECK_SCENE_TOOL_PRE, toolCall, null),
                    null
            );
            if (ToolSecuritySupport.requiresInterrupt(preDecision)) {
                log.warn("工具被合规前置检查拦截: toolCode={}, decision={}",
                        toolCall.getToolCode(), preDecision.getDecision());
                return blockedResult(toolCall, preDecision.getMessage(), preDecision.getDecision());
            }
        }

        // ③ 委托执行
        ToolExecutionResult result = delegate.executeWithContext(canonicalRequest,
                invocationContext != null ? invocationContext : fallbackContext(null));

        // ④ 运行时合规后置检查
        if (securityRuntimeGuardService != null) {
            SecurityCheckDecisionVO postDecision = securityRuntimeGuardService.evaluate(
                    buildCheckRequest(SecurityComplianceConstant.CHECK_SCENE_TOOL_POST, toolCall,
                            toPlatformResult(canonicalRequest, result)),
                    null
            );
            if (ToolSecuritySupport.requiresInterrupt(postDecision)) {
                log.warn("工具被合规后置检查拦截: toolCode={}, decision={}",
                        toolCall.getToolCode(), postDecision.getDecision());
                return blockedResult(toolCall, postDecision.getMessage(), postDecision.getDecision());
            }
        }
        return result;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        return executeWithContext(request, fallbackContext(memoryId)).resultText();
    }

    private InvocationContext fallbackContext(Object memoryId) {
        InvocationParameters parameters = new InvocationParameters();
        if (tenantId != null) parameters.put("tenantId", tenantId);
        if (agentId != null) parameters.put("agentId", agentId);
        if (executionId != null) parameters.put("executionId", executionId);
        return InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName(GuardedToolExecutor.class.getName())
                .methodName("execute")
                .chatMemoryId(memoryId)
                .invocationParameters(parameters)
                .timestampNow()
                .build();
    }

    private SecurityRuntimeCheckRequest buildCheckRequest(String scene, ToolCall call, ToolResult result) {
        SecurityRuntimeCheckRequest request =
                ToolSecuritySupport.buildCheckRequest(scene, tenantId, agentId, call, result, objectMapper);
        if (isDesktopTool() && StringUtils.hasText(executionId)) {
            request.setResourceType(SecurityComplianceConstant.RESOURCE_TYPE_AGENT_EXECUTION);
            request.setResourceId(executionId);
        }
        return request;
    }

    private ToolExecutionResult blockedResult(ToolCall toolCall, String message, String controlAction) {
        ToolResult platform = ToolResult.builder()
                .callId(toolCall.getCallId())
                .toolCode(toolCall.getToolCode())
                .success(false)
                .result(objectMapper.nullNode())
                .errorMessage(StringUtils.hasText(message) ? message : "工具执行被安全策略拦截")
                .controlAction(controlAction)
                .build();
        return ToolExecutionResult.builder()
                .isError(true)
                .result(platform)
                .resultText(serialize(platform))
                .build();
    }

    private ToolResult toPlatformResult(ToolExecutionRequest request, ToolExecutionResult executionResult) {
        if (executionResult == null) {
            return null;
        }
        if (executionResult.result() instanceof ToolResult toolResult) {
            return toolResult;
        }
        JsonNode payload = objectMapper.nullNode();
        if (StringUtils.hasText(executionResult.resultText())) {
            payload = objectMapper.valueToTree(executionResult.resultText());
        }
        return ToolResult.builder()
                .callId(request != null ? request.id() : null)
                .toolCode(request != null ? request.name() : null)
                .success(!executionResult.isError())
                .result(payload)
                .errorMessage(executionResult.isError() ? executionResult.resultText() : null)
                .build();
    }

    private ParsedArguments parseArguments(String arguments, String toolCode) {
        if (arguments == null || arguments.isBlank()) {
            return new ParsedArguments(objectMapper.createObjectNode(), "{}");
        }
        try {
            JsonNode parsed = objectMapper.readTree(arguments);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("工具参数必须是 JSON 对象: " + toolCode);
            }
            return new ParsedArguments(parsed, objectMapper.writeValueAsString(parsed));
        } catch (Exception exception) {
            throw new IllegalArgumentException("工具参数 JSON 非法，已拒绝执行: " + toolCode, exception);
        }
    }

    private record ParsedArguments(JsonNode node, String canonicalJson) {
    }

    private String serialize(ToolResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception exception) {
            return "{\"success\":false,\"errorMessage\":\"工具执行结果序列化失败\"}";
        }
    }
}
