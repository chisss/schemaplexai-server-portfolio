package com.schemaplexai.service.agent.tool.executor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.McpServerStatusEnum;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.enums.ToolExecutionStatusEnum;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.service.agent.tool.audit.ToolExecutionLogService;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.service.integration.mcp.McpClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolExecutor implements ToolExecutor {

    private final McpServerMapper mcpServerMapper;
    private final McpClientService mcpClientService;
    private final ObjectMapper objectMapper;
    private final ToolExecutionLogService logService;

    @Override
    public String sourceType() {
        return SourceTypeEnum.MCP.getCode();
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall) {
        LocalDateTime startAt = LocalDateTime.now();
        String toolCode = toolCall != null ? toolCall.getToolCode() : null;
        try {
            if (binding == null || !StringUtils.hasText(binding.getSourceRefId())) {
                logService.logExecution(tenantId, agentId, null, toolCall != null ? toolCall.getCallId() : null,
                        SourceTypeEnum.MCP.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "MCP 工具绑定缺少 sourceRefId");
                return failure(toolCall, "MCP 工具绑定缺少 sourceRefId");
            }
            String toolName = resolveToolName(binding, toolCall);
            if (!StringUtils.hasText(toolName)) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.MCP.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "MCP 工具名为空");
                return failure(toolCall, "MCP 工具名为空");
            }

            McpServer mcpServer = mcpServerMapper.selectOne(new LambdaQueryWrapper<McpServer>()
                    .eq(McpServer::getId, binding.getSourceRefId())
                    .eq(McpServer::getTenantId, tenantId)
                    .last("LIMIT 1"));
            if (mcpServer == null) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.MCP.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "MCP Server 不存在");
                return failure(toolCall, "MCP Server 不存在: " + binding.getSourceRefId());
            }
            if (!McpServerStatusEnum.ACTIVE.getCode().equalsIgnoreCase(mcpServer.getStatus())) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.MCP.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "MCP Server 非 active 状态");
                return failure(toolCall, "MCP Server 非 active 状态: " + mcpServer.getName());
            }
            if (!containsTool(mcpServer, toolName)) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.MCP.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "MCP Server 未发现工具");
                return failure(toolCall, "MCP Server 未发现工具: " + toolName);
            }

            Map<String, Object> args = convertArguments(toolCall != null ? toolCall.getArguments() : null);
            Map<String, Object> output = mcpClientService.toolsCall(mcpServer, toolName, args);
            LocalDateTime endAt = LocalDateTime.now();
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    SourceTypeEnum.MCP.getCode(), toolCode,  ToolExecutionStatusEnum.SUCCESS.getCode(), startAt, endAt, args, output, null);
            return success(toolCall, output);
        } catch (Exception exception) {
            log.error("MCP 工具执行异常: tenantId={}, agentId={}, toolCode={}",
                    tenantId, agentId, toolCode, exception);
            logService.logExecution(tenantId, agentId, null, toolCall != null ? toolCall.getCallId() : null,
                    SourceTypeEnum.MCP.getCode(), toolCode,  ToolExecutionStatusEnum.ERROR.getCode(), startAt, LocalDateTime.now(), null, null, exception.getMessage());
            return failure(toolCall, "MCP 工具执行异常: " + exception.getMessage());
        }
    }

    private String resolveToolName(AgentToolBinding binding, ToolCall toolCall) {
        if (binding != null && binding.getConfigOverride() != null) {
            Object configured = binding.getConfigOverride().get("toolName");
            if (configured != null && StringUtils.hasText(String.valueOf(configured))) {
                return String.valueOf(configured).trim();
            }
        }
        if (toolCall != null && StringUtils.hasText(toolCall.getToolCode())) {
            return toolCall.getToolCode().trim();
        }
        return null;
    }

    private boolean containsTool(McpServer mcpServer, String toolCode) {
        if (CollectionUtils.isEmpty(mcpServer.getTools())) {
            return true;
        }
        for (Object toolObject : mcpServer.getTools()) {
            if (!(toolObject instanceof Map<?, ?> toolMap)) {
                continue;
            }
            Object toolName = toolMap.get("name");
            if (toolName != null && toolCode.equals(String.valueOf(toolName))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> convertArguments(JsonNode argumentsNode) {
        if (argumentsNode == null || argumentsNode.isNull()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(argumentsNode, new TypeReference<>() {});
        } catch (IllegalArgumentException exception) {
            log.warn("MCP 参数转换失败: {}", exception.getMessage());
            return Map.of();
        }
    }

    private ToolResult success(ToolCall toolCall, Object payload) {
        return ToolResult.builder()
                .callId(toolCall != null ? toolCall.getCallId() : null)
                .toolCode(toolCall != null ? toolCall.getToolCode() : null)
                .success(true)
                .result(objectMapper.valueToTree(payload))
                .build();
    }

    private ToolResult failure(ToolCall toolCall, String message) {
        return ToolResult.builder()
                .callId(toolCall != null ? toolCall.getCallId() : null)
                .toolCode(toolCall != null ? toolCall.getToolCode() : null)
                .success(false)
                .result(objectMapper.nullNode())
                .errorMessage(message)
                .build();
    }
}
