package com.schemaplexai.service.agent.tool.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.model.dto.gateway.ApiGatewayTestRequest;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.vo.gateway.ApiGatewayTestResult;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.gateway.ApiGatewayService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiGatewayToolExecutorTest {

    @Test
    void shouldRouteAgentGatewayCallThroughServiceAndParseArguments() throws Exception {
        ApiGatewayService apiGatewayService = mock(ApiGatewayService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        ApiGatewayToolExecutor executor = new ApiGatewayToolExecutor(apiGatewayService, objectMapper);

        AgentToolBinding binding = new AgentToolBinding();
        binding.setSourceRefId("gateway-1");

        ToolCall toolCall = ToolCall.builder()
                .callId("call-1")
                .toolCode("schemaplexai.demo.finance.external-signal")
                .arguments(objectMapper.readTree("""
                        {
                          "headers": {
                            "X-Trace-Id": "trace-123"
                          },
                          "queryParams": {
                            "base": "USD"
                          },
                          "body": {
                            "requestType": "preview"
                          }
                        }
                        """))
                .build();

        ApiGatewayTestResult gatewayResult = new ApiGatewayTestResult();
        gatewayResult.setSuccess(true);
        gatewayResult.setStatusCode(200);
        gatewayResult.setDurationMs(128L);
        gatewayResult.setResponseHeaders(Map.of("content-type", "application/json"));
        gatewayResult.setResponseBody("{\"ok\":true}");
        when(apiGatewayService.execute(eq("gateway-1"), org.mockito.ArgumentMatchers.any(ApiGatewayTestRequest.class), eq("agent"), eq("agent-1")))
                .thenReturn(gatewayResult);

        var result = executor.execute("tenant-1", "agent-1", binding, toolCall);

        ArgumentCaptor<ApiGatewayTestRequest> requestCaptor = ArgumentCaptor.forClass(ApiGatewayTestRequest.class);
        verify(apiGatewayService).execute(eq("gateway-1"), requestCaptor.capture(), eq("agent"), eq("agent-1"));

        ApiGatewayTestRequest request = requestCaptor.getValue();
        assertThat(request.getHeaders()).containsEntry("X-Trace-Id", "trace-123");
        assertThat(request.getQueryParams()).containsEntry("base", "USD");
        assertThat(request.getBody()).isEqualTo("{\"requestType\":\"preview\"}");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResult().get("statusCode").asInt()).isEqualTo(200);
        assertThat(result.getResult().get("headers").get("content-type").asText()).isEqualTo("application/json");
    }
}
