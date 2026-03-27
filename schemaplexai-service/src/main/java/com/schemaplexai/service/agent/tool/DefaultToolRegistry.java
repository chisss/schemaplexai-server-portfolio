package com.schemaplexai.service.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.tool.executor.ToolExecutor;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.agent.tool.model.ToolDefinition;
import com.schemaplexai.service.agent.tool.model.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultToolRegistry implements ToolRegistry {

    private final ObjectMapper objectMapper;
    private final AgentToolBindingMapper agentToolBindingMapper;
    private final List<ToolExecutor> toolExecutors;

    @Override
    public List<ToolDefinition> listEnabledTools(String tenantId, String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return List.of();
        }
        var bindings = agentToolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getTenantId, tenantId)
                        .eq(AgentToolBinding::getAgentId, agentId)
                        .eq(AgentToolBinding::getEnabled, true)
                        .orderByAsc(AgentToolBinding::getPriority)
                        .orderByAsc(AgentToolBinding::getCreatedAt)
        );

        List<ToolDefinition> tools = new ArrayList<>();
        for (AgentToolBinding binding : bindings) {
            tools.add(ToolDefinition.builder()
                    .code(binding.getToolCode())
                    .name(binding.getToolCode())
                    .description("Agent 绑定工具: " + binding.getToolCode())
                    .inputSchema(objectMapper.createObjectNode())
                    .sourceType(binding.getSourceType())
                    .userVisible(true)
                    .build());
        }
        return tools;
    }

    @Override
    public List<ToolResult> executeAll(String tenantId, String agentId, List<ToolCall> toolCalls) {
        List<ToolResult> results = new ArrayList<>();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return results;
        }

        var bindings = agentToolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getTenantId, tenantId)
                        .eq(AgentToolBinding::getAgentId, agentId)
                        .eq(AgentToolBinding::getEnabled, true)
        );

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

            String sourceType = StringUtils.hasText(binding.getSourceType())
                    ? binding.getSourceType().trim().toLowerCase()
                    : "builtin";
            ToolExecutor executor = executorMap.get(sourceType);
            if (executor == null) {
                results.add(failureResult(toolCall, "未找到工具执行器: sourceType=" + sourceType));
                continue;
            }

            try {
                results.add(executor.execute(tenantId, agentId, binding, toolCall));
            } catch (Exception exception) {
                log.error("工具执行异常: agentId={}, toolCode={}", agentId, toolCall.getToolCode(), exception);
                results.add(failureResult(toolCall, "工具执行异常: " + exception.getMessage()));
            }
        }
        return results;
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
}
