package com.schemaplexai.service.agent.tool.executor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.McpServerStatusEnum;
import com.schemaplexai.common.enums.SkillImplementationTypeEnum;
import com.schemaplexai.common.enums.SkillStatusEnum;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.enums.ToolExecutionStatusEnum;
import com.schemaplexai.dao.mapper.AgentToolConfigMapper;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.dao.mapper.SkillInstallationMapper;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.model.entity.AgentToolConfig;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.model.entity.SkillInstallation;
import com.schemaplexai.service.agent.tool.builtin.BuiltinSkillExecutor;
import com.schemaplexai.service.agent.tool.audit.ToolExecutionLogService;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.service.integration.mcp.McpClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

@Slf4j
@Component
@RequiredArgsConstructor
public class SkillToolExecutor implements ToolExecutor {

    private final SkillMapper skillMapper;
    private final SkillInstallationMapper skillInstallationMapper;
    private final McpServerMapper mcpServerMapper;
    private final AgentToolConfigMapper agentToolConfigMapper;
    private final McpClientService mcpClientService;
    private final ObjectMapper objectMapper;
    private final ToolExecutionLogService logService;
    private final List<BuiltinSkillExecutor> builtinExecutors;

    @Override
    public String sourceType() {
        return SourceTypeEnum.SKILL.getCode();
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall) {
        LocalDateTime startAt = LocalDateTime.now();
        String toolCode = toolCall != null ? toolCall.getToolCode() : null;
        try {
            if (binding == null || !StringUtils.hasText(binding.getSourceRefId())) {
                logService.logExecution(tenantId, agentId, null, toolCall != null ? toolCall.getCallId() : null,
                        SourceTypeEnum.SKILL.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "Skill 工具绑定缺少 sourceRefId");
                return failure(toolCall, "Skill 工具绑定缺少 sourceRefId");
            }

            Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                    .eq(Skill::getId, binding.getSourceRefId())
                    .last("LIMIT 1"));
            if (skill == null) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.SKILL.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "Skill 不存在");
                return failure(toolCall, "Skill 不存在: " + binding.getSourceRefId());
            }
            if (!SkillStatusEnum.ACTIVE.getCode().equalsIgnoreCase(skill.getStatus())) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.SKILL.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "Skill 未启用");
                return failure(toolCall, "Skill 未启用: " + skill.getName());
            }
            if (!isSkillInstalledOrOwned(tenantId, skill)) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.SKILL.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "Skill 未安装或无访问权限");
                return failure(toolCall, "Skill 未安装或无访问权限: " + skill.getName());
            }

            Map<String, Object> implementation = skill.getImplementation();
            if (implementation == null) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.SKILL.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "Skill 缺少 implementation 配置");
                return failure(toolCall, "Skill 缺少 implementation 配置");
            }

            String implementationType = String.valueOf(implementation.getOrDefault("type", "")).trim().toLowerCase();
            SkillImplementationTypeEnum typeEnum = SkillImplementationTypeEnum.fromCode(implementationType);
            if (typeEnum == null) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.SKILL.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "Skill implementation.type 为空或不支持");
                return failure(toolCall, "Skill implementation.type 为空或不支持: " + implementationType);
            }

            ToolResult result = switch (typeEnum) {
                case MCP -> executeMcpSkill(tenantId, skill, toolCall, implementation);
                case BUILTIN -> executeBuiltinSkill(tenantId, agentId, skill, toolCall, implementation, binding.getId());
                case SCRIPT, API -> failure(toolCall, "Skill 执行类型暂未接入: " + implementationType);
            };

            LocalDateTime endAt = LocalDateTime.now();
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    SourceTypeEnum.SKILL.getCode(), toolCode, result.isSuccess() ? ToolExecutionStatusEnum.SUCCESS.getCode() : ToolExecutionStatusEnum.FAILED.getCode(),
                    startAt, endAt, convertArguments(toolCall.getArguments()),
                    result.getResult() != null ? Map.of("result", result.getResult()) : null,
                    result.getErrorMessage());
            return result;
        } catch (Exception exception) {
            log.error("Skill 执行失败: toolCode={}", toolCode, exception);
            logService.logExecution(tenantId, agentId, null, toolCall != null ? toolCall.getCallId() : null,
                    SourceTypeEnum.SKILL.getCode(), toolCode,  ToolExecutionStatusEnum.ERROR.getCode(), startAt, LocalDateTime.now(), null, null, exception.getMessage());
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

        Map<String, Object> output = mcpClientService.toolsCall(mcpServer, toolName, mergedArguments);

        Map<String, Object> wrappedOutput = new LinkedHashMap<>();
        wrappedOutput.put("skillId", skill.getId());
        wrappedOutput.put("skillName", skill.getName());
        wrappedOutput.put("toolName", toolName);
        wrappedOutput.put("output", output);

        return success(toolCall, wrappedOutput);
    }

    private ToolResult executeBuiltinSkill(String tenantId, String agentId, Skill skill,
                                           ToolCall toolCall, Map<String, Object> implementation, String bindingId) {
        String className = firstText(implementation, "class");
        if (!StringUtils.hasText(className)) {
            return failure(toolCall, "Builtin Skill 缺少 class 配置");
        }

        // 优先通过 skillCode 精确匹配
        BuiltinSkillExecutor executor = builtinExecutors.stream()
                .filter(e -> skill.getName() != null && skill.getName().equals(e.getSkillCode()))
                .findFirst()
                .orElseGet(() -> builtinExecutors.stream()
                        .filter(e -> className.endsWith(e.getClass().getSimpleName()))
                        .findFirst()
                        .orElse(null));

        if (executor == null) {
            return failure(toolCall, "未找到对应的 Builtin Skill 执行器: " + className);
        }

        // 从 sf_agent_tool_config 加载配置
        Map<String, Object> config = loadToolConfig(bindingId);

        return executor.execute(tenantId, agentId, toolCall, config);
    }

    private Map<String, Object> loadToolConfig(String bindingId) {
        if (!StringUtils.hasText(bindingId)) {
            return Map.of();
        }
        try {
            AgentToolConfig toolConfig = agentToolConfigMapper.selectOne(
                    new LambdaQueryWrapper<AgentToolConfig>()
                            .eq(AgentToolConfig::getBindingId, bindingId)
                            .last("LIMIT 1"));
            if (toolConfig != null && toolConfig.getConfigValue() != null) {
                return toolConfig.getConfigValue();
            }
        } catch (Exception e) {
            log.warn("加载工具配置失败: bindingId={}, error={}", bindingId, e.getMessage());
        }
        return Map.of();
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
