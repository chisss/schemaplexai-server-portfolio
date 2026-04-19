package com.schemaplexai.service.agent.tool.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.dao.mapper.ApiGatewayMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.ApiGateway;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.gateway.ApiGatewayExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiGatewayToolExecutor implements ToolExecutor {

    private final ApiGatewayMapper apiGatewayMapper;
    private final ApiGatewayExecutor apiGatewayExecutor;
    private final ObjectMapper objectMapper;

    @Override
    public String sourceType() {
        return SourceTypeEnum.API_GATEWAY.getCode();
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall) {
        try {
            ApiGateway gateway = apiGatewayMapper.selectById(binding.getSourceRefId());
            if (gateway == null) {
                return ToolResult.builder()
                        .callId(toolCall.getCallId())
                        .toolCode(toolCall.getToolCode())
                        .success(false)
                        .errorMessage("API网关不存在: " + binding.getSourceRefId())
                        .build();
            }

            String body = null;
            if (toolCall.getArguments() != null && toolCall.getArguments().has("body")) {
                body = toolCall.getArguments().get("body").asText();
            }

            var result = apiGatewayExecutor.execute(gateway, null, null, body);

            var resultNode = objectMapper.createObjectNode();
            resultNode.put("success", result.getSuccess());
            resultNode.put("statusCode", result.getStatusCode() != null ? result.getStatusCode() : 0);
            resultNode.put("durationMs", result.getDurationMs() != null ? result.getDurationMs() : 0);
            if (result.getResponseBody() != null) {
                try {
                    resultNode.set("data", objectMapper.readTree(result.getResponseBody()));
                } catch (Exception e) {
                    resultNode.put("data", result.getResponseBody());
                }
            }

            return ToolResult.builder()
                    .callId(toolCall.getCallId())
                    .toolCode(toolCall.getToolCode())
                    .success(result.getSuccess())
                    .result(resultNode)
                    .errorMessage(result.getErrorMessage())
                    .build();

        } catch (Exception e) {
            log.error("API网关工具执行失败: toolCode={}, sourceRefId={}", toolCall.getToolCode(), binding.getSourceRefId(), e);
            return ToolResult.builder()
                    .callId(toolCall.getCallId())
                    .toolCode(toolCall.getToolCode())
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
}
