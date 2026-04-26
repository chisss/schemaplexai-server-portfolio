package com.schemaplexai.service.agent.tool.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 现有平台 ToolExecutor 到 LangChain4j ToolExecutor 的桥接器
 */
@Slf4j
@RequiredArgsConstructor
public class LangChain4jBindingToolExecutor implements dev.langchain4j.service.tool.ToolExecutor {

    private final ObjectMapper objectMapper;
    private final com.schemaplexai.service.agent.tool.executor.ToolExecutor delegate;
    private final String tenantId;
    private final String agentId;
    private final AgentToolBinding binding;
    private final SandboxPolicy sandboxPolicy;

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, dev.langchain4j.invocation.InvocationContext invocationContext) {
        String actualToolCode = binding != null && binding.getToolCode() != null ? binding.getToolCode() : request.name();
        ToolCall toolCall = ToolCall.builder()
                .callId(request.id())
                .toolCode(actualToolCode)
                .arguments(parseArguments(request.arguments()))
                .build();
        ToolResult result = delegate.execute(tenantId, agentId, binding, toolCall, sandboxPolicy);
        return ToolExecutionResult.builder()
                .isError(result == null || !result.isSuccess())
                .result(result)
                .resultText(serialize(result))
                .build();
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        return executeWithContext(request, null).resultText();
    }

    private JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(arguments);
        } catch (Exception exception) {
            log.warn("工具参数 JSON 解析失败: toolName={}, error={}", binding != null ? binding.getToolCode() : null, exception.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private String serialize(ToolResult result) {
        if (result == null) {
            return "{\"success\":false,\"errorMessage\":\"工具执行结果为空\"}";
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception exception) {
            return result.getErrorMessage() != null ? result.getErrorMessage() : "{}";
        }
    }
}
