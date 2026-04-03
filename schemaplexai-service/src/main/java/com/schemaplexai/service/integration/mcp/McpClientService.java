package com.schemaplexai.service.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.model.entity.McpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP 协议客户端服务
 * 负责与 MCP Server 通信，执行健康检查和工具发现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpClientService {

    private final ObjectMapper objectMapper;
    private final LangChain4jMcpClientFactory mcpClientFactory;

    /**
     * 健康检查 - 向 MCP Server 发送 ping 请求
     * @return true 表示健康
     */
    public boolean healthCheck(McpServer server) {
        try (McpClient mcpClient = mcpClientFactory.create(server)) {
            mcpClient.checkHealth();
            return true;
        } catch (Exception e) {
            log.warn("MCP Server 健康检查失败: serverId={}, error={}", server != null ? server.getId() : null, e.getMessage());
            return false;
        }
    }

    /**
     * 发现工具 - 调用 MCP Server 的 tools/list 端点
     * @return 工具列表
     */
    public List<Map<String, Object>> discoverTools(McpServer server) {
        try (McpClient mcpClient = mcpClientFactory.create(server)) {
            List<ToolSpecification> toolSpecifications = mcpClient.listTools();
            List<Map<String, Object>> tools = new ArrayList<>(toolSpecifications.size());
            for (ToolSpecification toolSpecification : toolSpecifications) {
                Map<String, Object> payload = new HashMap<>();
                payload.put("name", toolSpecification.name());
                payload.put("description", toolSpecification.description());
                payload.put("inputSchema", objectMapper.convertValue(toolSpecification.parameters(), Map.class));
                payload.put("metadata", toolSpecification.metadata());
                tools.add(payload);
            }
            return tools;
        } catch (Exception e) {
            log.error("MCP Server 工具发现失败: serverId={}, error={}", server != null ? server.getId() : null, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 调用 MCP 工具
     *
     * @param toolName   工具名称
     * @param arguments  工具参数
     * @return 工具执行结果
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toolsCall(McpServer server, String toolName, Map<String, Object> arguments) {
        try (McpClient mcpClient = mcpClientFactory.create(server)) {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .name(toolName)
                    .arguments(objectMapper.writeValueAsString(arguments != null ? arguments : Map.of()))
                    .build();
            ToolExecutionResult result = mcpClient.executeTool(request);
            if (result.result() instanceof Map<?, ?> resultMap) {
                return (Map<String, Object>) resultMap;
            }
            if (result.result() != null) {
                try {
                    return objectMapper.convertValue(result.result(), Map.class);
                } catch (IllegalArgumentException exception) {
                    log.debug("MCP 工具结果不是对象结构，降级为文本封装: tool={}, error={}", toolName, exception.getMessage());
                }
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("content", result.resultText());
            payload.put("attributes", result.attributes());
            return payload;
        } catch (Exception e) {
            log.error("MCP 工具调用失败: tool={}, error={}", toolName, e.getMessage());
            throw new RuntimeException("MCP 工具调用失败: " + e.getMessage(), e);
        }
    }
}
