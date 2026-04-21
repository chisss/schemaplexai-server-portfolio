package com.schemaplexai.service.agent.tool.executor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.model.dto.gateway.ApiGatewayTestRequest;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.gateway.ApiGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiGatewayToolExecutor implements ToolExecutor {

    private final ApiGatewayService apiGatewayService;
    private final ObjectMapper objectMapper;

    @Override
    public String sourceType() {
        return SourceTypeEnum.API_GATEWAY.getCode();
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall) {
        try {
            ApiGatewayTestRequest request = buildRequest(toolCall);
            var result = apiGatewayService.execute(binding.getSourceRefId(), request, "agent", agentId);

            var resultNode = objectMapper.createObjectNode();
            resultNode.put("success", result.getSuccess());
            resultNode.put("statusCode", result.getStatusCode() != null ? result.getStatusCode() : 0);
            resultNode.put("durationMs", result.getDurationMs() != null ? result.getDurationMs() : 0);
            if (result.getResponseHeaders() != null && !result.getResponseHeaders().isEmpty()) {
                resultNode.set("headers", objectMapper.valueToTree(result.getResponseHeaders()));
            }
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

    private ApiGatewayTestRequest buildRequest(ToolCall toolCall) throws Exception {
        ApiGatewayTestRequest request = new ApiGatewayTestRequest();
        if (toolCall.getArguments() == null || toolCall.getArguments().isNull()) {
            return request;
        }

        if (toolCall.getArguments().has("headers") && toolCall.getArguments().get("headers").isObject()) {
            request.setHeaders(readMap(toolCall.getArguments().get("headers")));
        }
        if (toolCall.getArguments().has("queryParams") && toolCall.getArguments().get("queryParams").isObject()) {
            request.setQueryParams(readMap(toolCall.getArguments().get("queryParams")));
        }
        if (toolCall.getArguments().has("body") && !toolCall.getArguments().get("body").isNull()) {
            var bodyNode = toolCall.getArguments().get("body");
            if (bodyNode.isTextual()) {
                request.setBody(bodyNode.asText());
            } else {
                request.setBody(objectMapper.writeValueAsString(bodyNode));
            }
        }

        if (!StringUtils.hasText(request.getBody())
                && (request.getHeaders() == null || request.getHeaders().isEmpty())
                && (request.getQueryParams() == null || request.getQueryParams().isEmpty())) {
            return new ApiGatewayTestRequest();
        }
        return request;
    }

    private Map<String, Object> readMap(com.fasterxml.jackson.databind.JsonNode node) {
        return objectMapper.convertValue(node, new TypeReference<>() {
        });
    }
}
