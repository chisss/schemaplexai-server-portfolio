package com.schemaplexai.service.agent.tool.executor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.McpServerStatusEnum;
import com.schemaplexai.common.enums.SkillStatusEnum;
import com.schemaplexai.common.enums.ToolExecutionStatusEnum;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.dao.mapper.SkillInstallationMapper;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.model.entity.SkillInstallation;
import com.schemaplexai.service.agent.tool.audit.ToolExecutionLogService;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.agent.tool.model.ToolResult;
import com.schemaplexai.service.integration.mcp.McpClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SkillToolExecutor implements ToolExecutor {

    private final SkillMapper skillMapper;
    private final SkillInstallationMapper skillInstallationMapper;
    private final McpServerMapper mcpServerMapper;
    private final McpClientService mcpClientService;
    private final ObjectMapper objectMapper;
    private final ToolExecutionLogService logService;

    @Override
    public String sourceType() {
        return "skill";
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall) {
        LocalDateTime startAt = LocalDateTime.now();
        String toolCode = toolCall != null ? toolCall.getToolCode() : null;
        try {
            if (binding == null || !StringUtils.hasText(binding.getSourceRefId())) {
                logService.logExecution(tenantId, agentId, null, toolCall != null ? toolCall.getCallId() : null,
                        "skill", toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "Skill 工具绑定缺少 sourceRefId");
                return failure(toolCall, "Skill 工具绑定缺少 sourceRefId");
            }

            Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                    .eq(Skill::getId, binding.getSourceRefId())
                    .last("LIMIT 1"));
            if (skill == null) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        "skill", toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "Skill 不存在");
                return failure(toolCall, "Skill 不存在: " + binding.getSourceRefId());
            }
            if (!SkillStatusEnum.ACTIVE.getCode().equalsIgnoreCase(skill.getStatus())) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        "skill", toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "Skill 未启用");
                return failure(toolCall, "Skill 未启用: " + skill.getName());
            }
            if (!isSkillInstalledOrOwned(tenantId, skill)) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        "skill", toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "Skill 未安装或无访问权限");
                return failure(toolCall, "Skill 未安装或无访问权限: " + skill.getName());
            }

            Map<String, Object> implementation = skill.getImplementation();
            if (implementation == null) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        "skill", toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "Skill 缺少 implementation 配置");
                return failure(toolCall, "Skill 缺少 implementation 配置");
            }

            String implementationType = String.valueOf(implementation.getOrDefault("type", "")).trim().toLowerCase();
            if (!StringUtils.hasText(implementationType)) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        "skill", toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "Skill implementation.type 为空");
                return failure(toolCall, "Skill implementation.type 为空");
            }

            ToolResult result = switch (implementationType) {
                case "mcp" -> executeMcpSkill(tenantId, skill, toolCall, implementation);
                case "script", "api" -> failure(toolCall, "Skill 执行类型暂未接入: " + implementationType);
                default -> failure(toolCall, "不支持的 Skill 执行类型: " + implementationType);
            };

            LocalDateTime endAt = LocalDateTime.now();
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    "skill", toolCode, result.isSuccess() ? ToolExecutionStatusEnum.SUCCESS.getCode() : ToolExecutionStatusEnum.FAILED.getCode(),
                    startAt, endAt, convertArguments(toolCall.getArguments()),
                    result.getResult() != null ? Map.of("result", result.getResult()) : null,
                    result.getErrorMessage());
            return result;
        } catch (Exception exception) {
            log.error("Skill 执行失败: toolCode={}", toolCode, exception);
            logService.logExecution(tenantId, agentId, null, toolCall != null ? toolCall.getCallId() : null,
                    "skill", toolCode,  ToolExecutionStatusEnum.ERROR.getCode(), startAt, LocalDateTime.now(), null, null, exception.getMessage());
            return failure(toolCall, "Skill 执行异常: " + exception.getMessage());
        }
    }

    private ToolResult executeMcpSkill(String tenantId, Skill skill, ToolCall toolCall, Map<String, Object> implementation) {
        String mcpServerId = firstText(implementation, "mcpServerId", "mcp_server_id", "serverId");
        if (!StringUtils.hasText(mcpServerId)) {
            return failure(toolCall, "MCP Skill 缺少 mcpServerId");
        }

        String toolName = firstText(implementation, "toolName", "tool_code", "name");
        if (!StringUtils.hasText(toolName)) {
            toolName = toolCall != null ? toolCall.getToolCode() : null;
        }
        if (!StringUtils.hasText(toolName)) {
            return failure(toolCall, "MCP Skill 缺少 toolName");
        }

        McpServer mcpServer = mcpServerMapper.selectOne(new LambdaQueryWrapper<McpServer>()
                .eq(McpServer::getId, mcpServerId)
                .eq(McpServer::getTenantId, tenantId)
                .last("LIMIT 1"));
        if (mcpServer == null) {
            return failure(toolCall, "MCP Server 不存在: " + mcpServerId);
        }
        if (!McpServerStatusEnum.ACTIVE.getCode().equalsIgnoreCase(mcpServer.getStatus())) {
            return failure(toolCall, "MCP Server 非 active 状态: " + mcpServer.getName());
        }

        Map<String, Object> mergedArguments = new LinkedHashMap<>();
        Object defaultArguments = implementation.get("defaultArgs");
        if (defaultArguments instanceof Map<?, ?> defaultArgumentMap) {
            for (Map.Entry<?, ?> entry : defaultArgumentMap.entrySet()) {
                mergedArguments.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        mergedArguments.putAll(convertArguments(toolCall != null ? toolCall.getArguments() : null));

        Map<String, Object> output = mcpClientService.toolsCall(
                mcpServer.getUrl(),
                mcpServer.getAuthType(),
                mcpServer.getAuthConfig(),
                toolName,
                mergedArguments
        );

        Map<String, Object> wrappedOutput = new LinkedHashMap<>();
        wrappedOutput.put("skillId", skill.getId());
        wrappedOutput.put("skillName", skill.getName());
        wrappedOutput.put("toolName", toolName);
        wrappedOutput.put("output", output);

        return success(toolCall, wrappedOutput);
    }

    private boolean isSkillInstalledOrOwned(String tenantId, Skill skill) {
        if (StringUtils.hasText(skill.getTenantId()) && skill.getTenantId().equals(tenantId)) {
            return true;
        }
        Long installedCount = skillInstallationMapper.selectCount(new LambdaQueryWrapper<SkillInstallation>()
                .eq(SkillInstallation::getTenantId, tenantId)
                .eq(SkillInstallation::getSkillId, skill.getId())
                .eq(SkillInstallation::getStatus, "installed"));
        return installedCount != null && installedCount > 0;
    }

    private String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private Map<String, Object> convertArguments(JsonNode argumentsNode) {
        if (argumentsNode == null || argumentsNode.isNull()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(argumentsNode, new TypeReference<>() {});
        } catch (IllegalArgumentException exception) {
            log.warn("Skill 参数转换失败: {}", exception.getMessage());
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
