package com.schemaplexai.service.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.entity.BuiltinTool;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.agent.execution.SandboxGuard;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.tool.executor.ToolExecutor;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.agent.tool.model.ToolDefinition;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultToolRegistry implements ToolRegistry {

    private final ObjectMapper objectMapper;
    private final AgentToolBindingMapper agentToolBindingMapper;
    private final BuiltinToolMapper builtinToolMapper;
    private final List<ToolExecutor> toolExecutors;
    private final SecurityRuntimeGuardService securityRuntimeGuardService;
    private final SandboxGuard sandboxGuard;

    @Override
    public List<ToolDefinition> listEnabledTools(String tenantId, String agentId) {
        return listEnabledTools(tenantId, agentId, null, null);
    }

    @Override
    public List<ToolDefinition> listEnabledTools(String tenantId, String agentId,
                                                 List<AgentToolBinding> overrideBindings,
                                                 SandboxPolicy sandboxPolicy) {
        if (!StringUtils.hasText(agentId)) {
            return List.of();
        }
        var bindings = resolveBindings(tenantId, agentId, overrideBindings);

        List<ToolDefinition> tools = new ArrayList<>();
        for (AgentToolBinding binding : bindings) {
            BuiltinTool builtinTool = resolveBuiltinTool(binding);
            tools.add(ToolDefinition.builder()
                    .code(binding.getToolCode())
                    .name(builtinTool != null && StringUtils.hasText(builtinTool.getName()) ? builtinTool.getName() : binding.getToolCode())
                    .description(resolveDescription(binding, builtinTool))
                    .inputSchema(resolveInputSchema(builtinTool))
                    .sourceType(binding.getSourceType())
                    .userVisible(true)
                    .build());
        }
        return tools;
    }

    @Override
    public List<ToolResult> executeAll(String tenantId, String agentId, List<ToolCall> toolCalls) {
        return executeAll(tenantId, agentId, toolCalls, null, null);
    }

    @Override
    public List<ToolResult> executeAll(String tenantId, String agentId, List<ToolCall> toolCalls,
                                       List<AgentToolBinding> overrideBindings,
                                       SandboxPolicy sandboxPolicy) {
        List<ToolResult> results = new ArrayList<>();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return results;
        }

        var bindings = resolveBindings(tenantId, agentId, overrideBindings);

        var bindingMap = new LinkedHashMap<String, AgentToolBinding>();
        for (AgentToolBinding binding : bindings) {
            bindingMap.putIfAbsent(binding.getToolCode(), binding);
        }

        var executorMap = new LinkedHashMap<String, ToolExecutor>();
        for (ToolExecutor executor : toolExecutors) {
            executorMap.putIfAbsent(executor.sourceType().toLowerCase(), executor);
        }

        for (ToolCall toolCall : toolCalls) {
            if (toolCall == null || !StringUtils.hasText(toolCall.getToolCode())) {
                results.add(failureResult(toolCall, "工具调用参数缺失"));
                continue;
            }

            AgentToolBinding binding = bindingMap.get(toolCall.getToolCode());
            if (binding == null) {
                results.add(failureResult(toolCall, "Agent 未绑定该工具: " + toolCall.getToolCode()));
                continue;
            }
            String sandboxValidationError = sandboxGuard.validateTool(sandboxPolicy, binding, toolCall);
            if (StringUtils.hasText(sandboxValidationError)) {
                results.add(failureResult(toolCall, sandboxValidationError));
                continue;
            }

            String sourceType = StringUtils.hasText(binding.getSourceType())
                    ? binding.getSourceType().trim().toLowerCase()
                    : SourceTypeEnum.BUILTIN.getCode();
            ToolExecutor executor = executorMap.get(sourceType);
            if (executor == null) {
                results.add(failureResult(toolCall, "未找到工具执行器: sourceType=" + sourceType));
                continue;
            }

            try {
                SecurityCheckDecisionVO preDecision = securityRuntimeGuardService.evaluate(
                        buildToolCheckRequest(SecurityComplianceConstant.CHECK_SCENE_TOOL_PRE, agentId, toolCall, null),
                        null
                );
                if (requiresInterrupt(preDecision)) {
                    results.add(interruptedResult(toolCall, preDecision));
                    continue;
                }

                ToolResult result = executor.execute(tenantId, agentId, binding, toolCall, sandboxPolicy);
                SecurityCheckDecisionVO postDecision = securityRuntimeGuardService.evaluate(
                        buildToolCheckRequest(SecurityComplianceConstant.CHECK_SCENE_TOOL_POST, agentId, toolCall, result),
                        null
                );
                if (requiresInterrupt(postDecision)) {
                    results.add(interruptedResult(toolCall, postDecision));
                    continue;
                }
                results.add(result);
            } catch (Exception exception) {
                log.error("工具执行异常: agentId={}, toolCode={}", agentId, toolCall.getToolCode(), exception);
                results.add(failureResult(toolCall, "工具执行异常: " + exception.getMessage()));
            }
        }
        return results;
    }

    private List<AgentToolBinding> resolveBindings(String tenantId, String agentId, List<AgentToolBinding> overrideBindings) {
        if (!CollectionUtils.isEmpty(overrideBindings)) {
            return overrideBindings.stream()
                    .filter(binding -> Boolean.TRUE.equals(binding.getEnabled()))
                    .sorted((left, right) -> Integer.compare(
                            left.getPriority() != null ? left.getPriority() : 100,
                            right.getPriority() != null ? right.getPriority() : 100))
                    .toList();
        }
        return agentToolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getTenantId, tenantId)
                        .eq(AgentToolBinding::getAgentId, agentId)
                        .eq(AgentToolBinding::getEnabled, true)
                        .orderByAsc(AgentToolBinding::getPriority)
                        .orderByAsc(AgentToolBinding::getCreatedAt)
        );
    }

    private ToolResult failureResult(ToolCall toolCall, String message) {
        return ToolResult.builder()
                .callId(toolCall != null ? toolCall.getCallId() : null)
                .toolCode(toolCall != null ? toolCall.getToolCode() : null)
                .success(false)
                .result(objectMapper.nullNode())
                .errorMessage(message)
                .build();
    }

    private boolean requiresInterrupt(SecurityCheckDecisionVO decision) {
        if (decision == null || !StringUtils.hasText(decision.getDecision())) {
            return false;
        }
        return SecurityComplianceConstant.DECISION_BLOCK.equals(decision.getDecision())
                || SecurityComplianceConstant.DECISION_PAUSE.equals(decision.getDecision());
    }

    private ToolResult interruptedResult(ToolCall toolCall, SecurityCheckDecisionVO decision) {
        return ToolResult.builder()
                .callId(toolCall != null ? toolCall.getCallId() : null)
                .toolCode(toolCall != null ? toolCall.getToolCode() : null)
                .success(false)
                .result(objectMapper.nullNode())
                .errorMessage(decision != null ? decision.getMessage() : null)
                .controlAction(decision != null ? decision.getDecision() : null)
                .build();
    }

    private BuiltinTool resolveBuiltinTool(AgentToolBinding binding) {
        String sourceType = StringUtils.hasText(binding.getSourceType())
                ? binding.getSourceType().trim().toLowerCase()
                : SourceTypeEnum.BUILTIN.getCode();
        if (!SourceTypeEnum.BUILTIN.getCode().equals(sourceType)) {
            return null;
        }
        return builtinToolMapper.selectOne(new LambdaQueryWrapper<BuiltinTool>()
                .eq(BuiltinTool::getCode, binding.getToolCode())
                .eq(BuiltinTool::getEnabled, true)
                .last("LIMIT 1"));
    }

    private SecurityRuntimeCheckRequest buildToolCheckRequest(String scene, String agentId, ToolCall toolCall, ToolResult toolResult) {
        var request = new SecurityRuntimeCheckRequest();
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

    private String resolveDescription(AgentToolBinding binding, BuiltinTool builtinTool) {
        if (builtinTool != null && StringUtils.hasText(builtinTool.getDescription())) {
            return builtinTool.getDescription();
        }
        return "Agent 绑定工具: " + binding.getToolCode();
    }

    private com.fasterxml.jackson.databind.JsonNode resolveInputSchema(BuiltinTool builtinTool) {
        if (builtinTool == null || !StringUtils.hasText(builtinTool.getInputSchema())) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(builtinTool.getInputSchema());
        } catch (Exception exception) {
            log.warn("解析内置工具 Schema 失败: toolCode={}, error={}", builtinTool.getCode(), exception.getMessage());
            return objectMapper.createObjectNode();
        }
    }
}
