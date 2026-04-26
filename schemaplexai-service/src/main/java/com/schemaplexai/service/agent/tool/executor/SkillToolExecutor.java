package com.schemaplexai.service.agent.tool.executor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.ToolConfigConstant;
import com.schemaplexai.common.enums.HttpMethodEnum;
import com.schemaplexai.common.enums.McpServerStatusEnum;
import com.schemaplexai.common.enums.SkillImplementationTypeEnum;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.enums.ToolExecutionStatusEnum;
import com.schemaplexai.dao.mapper.AgentToolConfigMapper;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.model.entity.AgentToolConfig;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.service.agent.tool.builtin.BuiltinSkillExecutor;
import com.schemaplexai.service.agent.tool.audit.ToolExecutionErrorCode;
import com.schemaplexai.service.agent.tool.audit.ToolExecutionLogService;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.agent.tool.validator.SkillAccessResult;
import com.schemaplexai.service.agent.tool.validator.SkillAccessValidator;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.service.integration.mcp.McpClientService;
import lombok.RequiredArgsConstructor;
import okhttp3.*;
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
    private final McpServerMapper mcpServerMapper;
    private final AgentToolConfigMapper agentToolConfigMapper;
    private final McpClientService mcpClientService;
    private final ObjectMapper objectMapper;
    private final ToolExecutionLogService logService;
    private final List<BuiltinSkillExecutor> builtinExecutors;
    private final OkHttpClient okHttpClient;
    private final SkillAccessValidator skillAccessValidator;

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
            SkillAccessResult accessResult = skillAccessValidator.validate(tenantId, skill);
            if (!accessResult.accessible()) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.SKILL.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(),
                        Map.of("accessStatus", accessResult.status()), null,
                        ToolExecutionErrorCode.SKILL_NOT_INSTALLED, accessResult.message());
                return failure(toolCall, accessResult.message() + ": " + skill.getName());
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
                case SCRIPT -> executeScriptSkill(skill, toolCall, implementation);
                case API -> executeApiSkill(tenantId, agentId, skill, toolCall, implementation, binding);
                case PROMPT_PACK -> executePromptPackSkill(skill, toolCall, implementation);
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

    private String firstText(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    /**
     * SCRIPT 类型 Skill 执行
     * <p>
     * implementation 中需包含 "script"（脚本内容）和可选的 "language"（默认 javascript）。
     * 当前仅支持 JavaScript（Nashorn/GraalJS），其他语言返回不支持提示。
     * </p>
     */
    private ToolResult executeScriptSkill(Skill skill, ToolCall toolCall, Map<String, Object> implementation) {
        String script = firstText(implementation, "script", "scriptContent", "code");
        if (!StringUtils.hasText(script)) {
            return failure(toolCall, "SCRIPT Skill 缺少 script 内容");
        }
        String language = firstText(implementation, "language", "lang");
        if (!StringUtils.hasText(language)) {
            language = "javascript";
        }

        if (!"javascript".equalsIgnoreCase(language) && !"js".equalsIgnoreCase(language)) {
            return failure(toolCall, "SCRIPT Skill 暂不支持语言: " + language);
        }

        try {
            javax.script.ScriptEngineManager manager = new javax.script.ScriptEngineManager();
            javax.script.ScriptEngine engine = manager.getEngineByName("js");
            if (engine == null) {
                engine = manager.getEngineByName("nashorn");
            }
            if (engine == null) {
                return failure(toolCall, "当前 JVM 不支持 JavaScript 脚本引擎");
            }

            // 注入调用参数
            Map<String, Object> args = convertArguments(toolCall != null ? toolCall.getArguments() : null);
            engine.put("args", args);
            engine.put("skillName", skill.getName());

            Object result = engine.eval(script);
            return success(toolCall, Map.of("output", result != null ? result.toString() : "null"));
        } catch (Exception e) {
            log.error("SCRIPT Skill 执行异常: skillId={}", skill.getId(), e);
            return failure(toolCall, "脚本执行失败: " + e.getMessage());
        }
    }

    /**
     * API 类型 Skill 执行
     * <p>
     * implementation 中需包含 "url"，可选 "method"（默认 POST）、"headers"。
     * 使用 OkHttpClient 发起 HTTP 请求。
     * </p>
     */
    private ToolResult executeApiSkill(String tenantId, String agentId, Skill skill, ToolCall toolCall,
                                       Map<String, Object> implementation, AgentToolBinding binding) {
        String url = firstText(implementation, "url", "apiUrl", "endpoint");
        if (!StringUtils.hasText(url)) {
            return failure(toolCall, "API Skill 缺少 url 配置");
        }

        String methodStr = firstText(implementation, "method", "httpMethod");
        HttpMethodEnum method = HttpMethodEnum.fromCode(methodStr);

        Map<String, Object> args = convertArguments(toolCall != null ? toolCall.getArguments() : null);

        // 合并默认参数
        Map<String, Object> mergedArgs = new LinkedHashMap<>();
        Object defaultArgs = implementation.get("defaultArgs");
        if (defaultArgs instanceof Map<?, ?> defaultArgMap) {
            for (Map.Entry<?, ?> entry : defaultArgMap.entrySet()) {
                mergedArgs.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        mergedArgs.putAll(args);

        try {
            Request.Builder requestBuilder = new Request.Builder();

            // 注入请求头
            Object headersObj = implementation.get("headers");
            if (headersObj instanceof Map<?, ?> headersMap) {
                for (Map.Entry<?, ?> entry : headersMap.entrySet()) {
                    if (entry.getValue() != null) {
                        requestBuilder.header(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                    }
                }
            }
            requestBuilder.header("Content-Type", "application/json");

            switch (method) {
                case GET -> {
                    HttpUrl parsedUrl = HttpUrl.parse(url);
                    if (parsedUrl == null) {
                        return failure(toolCall, "URL 格式非法: " + url);
                    }
                    HttpUrl.Builder urlBuilder = parsedUrl.newBuilder();
                    mergedArgs.forEach((k, v) -> urlBuilder.addQueryParameter(k, String.valueOf(v)));
                    requestBuilder.url(urlBuilder.build()).get();
                }
                case DELETE -> requestBuilder.url(url).delete();
                case PUT -> requestBuilder.url(url).put(buildJsonBody(mergedArgs));
                case PATCH -> requestBuilder.url(url).patch(buildJsonBody(mergedArgs));
                default -> requestBuilder.url(url).post(buildJsonBody(mergedArgs));
            }

            try (okhttp3.Response response = okHttpClient.newCall(requestBuilder.build()).execute()) {
                int statusCode = response.code();
                String responseBody = response.body() != null ? response.body().string() : "";

                if (responseBody.length() > ToolConfigConstant.HTTP_RESPONSE_MAX_LENGTH) {
                    responseBody = responseBody.substring(0, ToolConfigConstant.HTTP_RESPONSE_MAX_LENGTH)
                            + ToolConfigConstant.HTTP_RESPONSE_TRUNCATED_SUFFIX;
                }

                Object parsedBody;
                try {
                    parsedBody = objectMapper.readValue(responseBody, Object.class);
                } catch (Exception e) {
                    parsedBody = responseBody;
                }

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("statusCode", statusCode);
                result.put("body", parsedBody);
                result.put("success", statusCode >= 200 && statusCode < 300);
                return success(toolCall, result);
            }
        } catch (Exception e) {
            log.error("API Skill 执行异常: skillId={}, url={}", skill.getId(), url, e);
            return failure(toolCall, "API 调用失败: " + e.getMessage());
        }
    }

    /**
     * PROMPT_PACK 类型 Skill 执行
     * <p>
     * 将 Skill 提示词、资源摘要和调用参数整理为结构化执行指引，
     * 便于 Team Agent 在最终交付前快速吸收领域约束。
     * </p>
     */
    private ToolResult executePromptPackSkill(Skill skill, ToolCall toolCall, Map<String, Object> implementation) {
        Map<String, Object> args = convertArguments(toolCall != null ? toolCall.getArguments() : null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skillId", skill.getId());
        result.put("skillName", skill.getName());
        result.put("displayName", StringUtils.hasText(skill.getDisplayName()) ? skill.getDisplayName() : skill.getName());
        result.put("activationPrompt", skill.getSkillPrompt());
        result.put("sceneInput", args.getOrDefault("scene_input", ""));
        result.put("expectedOutput", args.getOrDefault("expected_output", ""));

        List<String> resourceSummaries = new java.util.ArrayList<>();
        if (skill.getResources() != null) {
            for (Object resourceObject : skill.getResources()) {
                if (!(resourceObject instanceof Map<?, ?> resourceMap)) {
                    continue;
                }
                String relativePath = textValue(resourceMap.get("relativePath"));
                String content = textValue(resourceMap.get("content"));
                if (!StringUtils.hasText(relativePath) && !StringUtils.hasText(content)) {
                    continue;
                }
                if (StringUtils.hasText(relativePath) && StringUtils.hasText(content)) {
                    resourceSummaries.add(relativePath + ": " + truncate(content, 280));
                    continue;
                }
                resourceSummaries.add(StringUtils.hasText(relativePath) ? relativePath : truncate(content, 280));
            }
        }
        result.put("resourceSummaries", resourceSummaries);

        Map<String, Object> exposureConfig = skill.getExposureConfig() != null ? skill.getExposureConfig() : Map.of();
        result.put("sceneGroup", exposureConfig.getOrDefault("sceneGroup", ""));
        result.put("progressiveDisclosure", exposureConfig.getOrDefault("progressiveDisclosure", Boolean.FALSE));

        String implementationContent = firstText(implementation, "content", "prompt", "instruction");
        if (StringUtils.hasText(implementationContent)) {
            result.put("implementationGuide", truncate(implementationContent, 600));
        }
        result.put("recommendedChecklist", buildPromptPackChecklist(skill, args, resourceSummaries));
        return success(toolCall, result);
    }

    private RequestBody buildJsonBody(Map<String, Object> params) throws Exception {
        byte[] json = objectMapper.writeValueAsBytes(params);
        return RequestBody.create(json, MediaType.parse("application/json"));
    }

    private List<String> buildPromptPackChecklist(Skill skill,
                                                  Map<String, Object> args,
                                                  List<String> resourceSummaries) {
        List<String> checklist = new java.util.ArrayList<>();
        checklist.add("先吸收已绑定上下文和上游成员已确认事实，再使用技能结论。");
        if (StringUtils.hasText(textValue(args.get("scene_input")))) {
            checklist.add("优先围绕 scene_input 中的客户目标、约束和样本数据输出。");
        }
        if (!resourceSummaries.isEmpty()) {
            checklist.add("引用资源包中的字段或规则时，要明确说明来源文件。");
        }
        if (StringUtils.hasText(skill.getDescription())) {
            checklist.add("交付内容需覆盖技能描述中的核心目标：" + truncate(skill.getDescription(), 120));
        }
        return checklist;
    }

    private String textValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String truncate(String content, int maxLength) {
        if (!StringUtils.hasText(content) || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
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
