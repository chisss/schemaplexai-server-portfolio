package com.schemaplexai.service.integration.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 协议客户端服务
 * 负责与 MCP Server 通信，执行健康检查和工具发现
 */
@Slf4j
@Service
public class McpClientService {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public McpClientService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 健康检查 - 向 MCP Server 发送 ping 请求
     *
     * @param serverUrl  MCP Server 地址
     * @param authType   认证类型
     * @param authConfig 认证配置
     * @return true 表示健康
     */
    public boolean healthCheck(String serverUrl, String authType, Map<String, Object> authConfig) {
        try {
            // MCP 协议: 发送 initialize 请求检查服务是否可达
            Map<String, Object> request = buildJsonRpcRequest("initialize", Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "SchemaPlexAI", "version", "1.0.0")
            ));

            String responseBody = sendRequest(serverUrl, request, authType, authConfig);
            if (responseBody != null) {
                Map<String, Object> response = objectMapper.readValue(responseBody, new TypeReference<>() {});
                // 检查是否返回 result（非 error）
                return response.containsKey("result");
            }
            return false;
        } catch (Exception e) {
            log.warn("MCP Server 健康检查失败: url={}, error={}", serverUrl, e.getMessage());
            return false;
        }
    }

    /**
     * 发现工具 - 调用 MCP Server 的 tools/list 端点
     *
     * @param serverUrl  MCP Server 地址
     * @param authType   认证类型
     * @param authConfig 认证配置
     * @return 工具列表
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> discoverTools(String serverUrl, String authType, Map<String, Object> authConfig) {
        try {
            Map<String, Object> request = buildJsonRpcRequest("tools/list", Map.of());
            String responseBody = sendRequest(serverUrl, request, authType, authConfig);
            if (responseBody != null) {
                Map<String, Object> response = objectMapper.readValue(responseBody, new TypeReference<>() {});
                if (response.containsKey("result")) {
                    Map<String, Object> result = (Map<String, Object>) response.get("result");
                    Object tools = result.get("tools");
                    if (tools instanceof List<?> toolList) {
                        return (List<Map<String, Object>>) toolList;
                    }
                }
            }
            return new ArrayList<>();
        } catch (Exception e) {
            log.error("MCP Server 工具发现失败: url={}, error={}", serverUrl, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 调用 MCP 工具
     *
     * @param serverUrl  MCP Server 地址
     * @param toolName   工具名称
     * @param arguments  工具参数
     * @param authType   认证类型
     * @param authConfig 认证配置
     * @return 工具执行结果
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toolsCall(String serverUrl, String authType, Map<String, Object> authConfig,
                                         String toolName, Map<String, Object> arguments) {
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments != null ? arguments : Map.of());

            Map<String, Object> request = buildJsonRpcRequest("tools/call", params);
            String responseBody = sendRequest(serverUrl, request, authType, authConfig);
            if (responseBody != null) {
                Map<String, Object> response = objectMapper.readValue(responseBody, new TypeReference<>() {});
                if (response.containsKey("result")) {
                    Object result = response.get("result");
                    if (result instanceof Map<?, ?> resultMap) {
                        return (Map<String, Object>) resultMap;
                    }
                    return Map.of("content", result);
                }
                if (response.containsKey("error")) {
                    Object errorObj = response.get("error");
                    String errorMsg = errorObj instanceof Map ?
                        String.valueOf(((Map<?, ?>) errorObj).get("message")) : String.valueOf(errorObj);
                    throw new RuntimeException("MCP 调用失败: " + errorMsg);
                }
            }
            throw new RuntimeException("MCP Server 无响应");
        } catch (Exception e) {
            log.error("MCP 工具调用失败: tool={}, error={}", toolName, e.getMessage());
            throw new RuntimeException("MCP 工具调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 JSON-RPC 2.0 请求体
     */
    private Map<String, Object> buildJsonRpcRequest(String method, Map<String, Object> params) {
        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", System.currentTimeMillis());
        request.put("method", method);
        request.put("params", params);
        return request;
    }

    /**
     * 发送 HTTP 请求到 MCP Server
     */
    private String sendRequest(String serverUrl, Map<String, Object> body, String authType, Map<String, Object> authConfig) throws IOException {
        String jsonBody = objectMapper.writeValueAsString(body);
        Request.Builder requestBuilder = new Request.Builder()
                .url(serverUrl)
                .post(RequestBody.create(jsonBody, JSON_TYPE))
                .header("Content-Type", "application/json");

        // 添加认证头
        if ("api_key".equals(authType) && authConfig != null) {
            String apiKey = String.valueOf(authConfig.getOrDefault("api_key", ""));
            String headerName = String.valueOf(authConfig.getOrDefault("header_name", "Authorization"));
            requestBuilder.header(headerName, apiKey);
        } else if ("oauth".equals(authType) && authConfig != null) {
            String token = String.valueOf(authConfig.getOrDefault("access_token", ""));
            requestBuilder.header("Authorization", "Bearer " + token);
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
            log.warn("MCP Server 请求失败: url={}, code={}", serverUrl, response.code());
            return null;
        }
    }
}
