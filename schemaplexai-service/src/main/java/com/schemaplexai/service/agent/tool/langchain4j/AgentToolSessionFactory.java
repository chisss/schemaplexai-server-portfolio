package com.schemaplexai.service.agent.tool.langchain4j;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.ApiGatewayMapper;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.ApiGateway;
import com.schemaplexai.model.entity.BuiltinTool;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.service.ai.LangChain4jToolSpecProvider;
import com.schemaplexai.service.ai.ToolNameNormalizer;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.SandboxGuard;
import com.schemaplexai.service.agent.tool.executor.ApiGatewayToolExecutor;
import com.schemaplexai.service.agent.tool.executor.BuiltinToolExecutor;
import com.schemaplexai.service.agent.tool.executor.SkillToolExecutor;
import com.schemaplexai.service.agent.tool.model.ToolDefinition;
import com.schemaplexai.service.integration.mcp.LangChain4jMcpClientFactory;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import com.schemaplexai.service.desktop.DesktopExecutionTarget;
import com.schemaplexai.service.desktop.DesktopToolBroker;
import com.schemaplexai.service.desktop.DesktopToolExecutor;
import com.schemaplexai.service.desktop.DesktopToolSchema;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import com.schemaplexai.service.agent.execution.AgentExecutionEvidenceService;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolServiceContext;
import dev.langchain4j.skills.ActivateSkillToolConfig;
import dev.langchain4j.skills.DefaultSkill;
import dev.langchain4j.skills.ReadResourceToolConfig;
import dev.langchain4j.skills.SkillResource;
import dev.langchain4j.skills.Skills;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 构建执行期 LangChain4j ToolServiceContext
 */
@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class AgentToolSessionFactory {

    private static final String ACTIVATE_SKILL_TOOL_NAME = "activate_skill";
    private static final String ACTIVATE_SKILL_PARAMETER_NAME = "skill_name";
    private static final String READ_SKILL_RESOURCE_TOOL_NAME = "read_skill_resource";
    private static final String READ_SKILL_RESOURCE_SKILL_NAME_PARAMETER = "skill_name";
    private static final String READ_SKILL_RESOURCE_PATH_PARAMETER = "relative_path";
    private static final String ACTIVATED_SKILL_NAME_ATTRIBUTE = "activatedSkillName";

    private final ObjectMapper objectMapper;
    private final AgentToolBindingMapper agentToolBindingMapper;
    private final BuiltinToolMapper builtinToolMapper;
    private final SkillMapper skillMapper;
    private final McpServerMapper mcpServerMapper;
    private final ApiGatewayMapper apiGatewayMapper;
    private final BuiltinToolExecutor builtinToolExecutor;
    private final SkillToolExecutor skillToolExecutor;
    private final ApiGatewayToolExecutor apiGatewayToolExecutor;
    private final LangChain4jToolSpecProvider toolSpecProvider;
    private final LangChain4jMcpClientFactory mcpClientFactory;

    private AgentExecutionEvidenceService evidenceService;

    @Autowired
    void setEvidenceService(AgentExecutionEvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }
    private final SandboxGuard sandboxGuard;
    private final ObjectProvider<SecurityRuntimeGuardService> securityRuntimeGuardServiceProvider;
    private final DesktopToolBroker desktopToolBroker;

    private final LangChainToolSearchAdapter toolSearchAdapter = new LangChainToolSearchAdapter();

    /** 兼容既有云端工具调用方；本机目标仍按缺少通道拒绝，禁止回退到云端目录。 */
    public AgentToolSessionFactory(ObjectMapper objectMapper, AgentToolBindingMapper agentToolBindingMapper,
                                   BuiltinToolMapper builtinToolMapper, SkillMapper skillMapper,
                                   McpServerMapper mcpServerMapper, ApiGatewayMapper apiGatewayMapper,
                                   BuiltinToolExecutor builtinToolExecutor, SkillToolExecutor skillToolExecutor,
                                   ApiGatewayToolExecutor apiGatewayToolExecutor, LangChain4jToolSpecProvider toolSpecProvider,
                                   LangChain4jMcpClientFactory mcpClientFactory, SandboxGuard sandboxGuard,
                                   ObjectProvider<SecurityRuntimeGuardService> securityRuntimeGuardServiceProvider) {
        this(objectMapper, agentToolBindingMapper, builtinToolMapper, skillMapper, mcpServerMapper, apiGatewayMapper,
                builtinToolExecutor, skillToolExecutor, apiGatewayToolExecutor, toolSpecProvider, mcpClientFactory,
                sandboxGuard, securityRuntimeGuardServiceProvider, null);
    }

    public AgentToolSession openSession(AgentExecutionContext ctx, String conversationId) {
        DesktopExecutionTarget target = DesktopExecutionTarget.from(ctx.getInputContext());
        if (target != null) {
            if (desktopToolBroker == null) {
                throw new IllegalStateException("本机工具通道不可用，禁止在云端代执行");
            }
            desktopToolBroker.validateTarget(ctx.getTenantId(), ctx.getUserId(), target);
        }
        List<AgentToolBinding> bindings = resolveBindings(ctx.getTenantId(), ctx.getAgentId(), ctx.getOverrideToolBindings());
        LinkedHashMap<String, ToolSpecification> availableTools = new LinkedHashMap<>();
        LinkedHashMap<String, dev.langchain4j.service.tool.ToolExecutor> executors = new LinkedHashMap<>();
        LinkedHashMap<String, List<ExecutableSkillTool>> lazyLoadedSkillTools = new LinkedHashMap<>();
        LinkedHashMap<String, ToolIoTypeEnum> ioTypeIndex = new LinkedHashMap<>();
        List<AutoCloseable> closeables = new ArrayList<>();

        InvocationContext invocationContext = buildInvocationContext(ctx, conversationId, 0);
        ToolProviderRequest providerRequest = ToolProviderRequest.builder()
                .invocationContext(invocationContext)
                .userMessage(UserMessage.from("初始化工具上下文"))
                .build();

        String skillPromptBlock = mergeSkillProviders(ctx, bindings, providerRequest, availableTools, executors, lazyLoadedSkillTools);
        mergeDirectMcpProviders(ctx, bindings, providerRequest, availableTools, executors, closeables);
        mergeLocalBindings(ctx, bindings, availableTools, executors, ioTypeIndex);

        // MCP 和 Skill 工具默认 READ_WRITE
        for (String toolName : availableTools.keySet()) {
            ioTypeIndex.putIfAbsent(toolName, ToolIoTypeEnum.READ_WRITE);
        }

        List<ToolSpecification> toolSpecifications = new ArrayList<>(availableTools.values());
        ToolServiceContext baseContext = ToolServiceContext.builder()
                .availableTools(toolSpecifications)
                .toolSpecifications(toolSpecifications)
                .effectiveTools(toolSpecifications)
                .toolExecutors(executors)
                .build();
        AgentToolSession session = new AgentToolSession(baseContext, toolSearchAdapter, ctx, conversationId,
                skillPromptBlock, closeables, lazyLoadedSkillTools, ioTypeIndex);
        session.evidenceService = evidenceService;
        session.objectMapper = objectMapper;
        return session;
    }

    private String mergeSkillProviders(AgentExecutionContext ctx,
                                       List<AgentToolBinding> bindings,
                                       ToolProviderRequest providerRequest,
                                       Map<String, ToolSpecification> availableTools,
                                       Map<String, dev.langchain4j.service.tool.ToolExecutor> executors,
                                       Map<String, List<ExecutableSkillTool>> lazyLoadedSkillTools) {
        LinkedHashMap<String, Skill> boundSkills = new LinkedHashMap<>();
        LinkedHashMap<String, List<ExecutableSkillTool>> executableToolsBySkill = new LinkedHashMap<>();
        for (AgentToolBinding binding : bindings) {
            String sourceType = normalizeSourceType(binding.getSourceType());
            if (!SourceTypeEnum.SKILL.getCode().equals(sourceType) || !StringUtils.hasText(binding.getSourceRefId())) {
                continue;
            }
            Skill skillEntity = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                    .eq(Skill::getId, binding.getSourceRefId())
                    .last("LIMIT 1"));
            if (skillEntity == null || !StringUtils.hasText(skillEntity.getName())) {
                continue;
            }
            boundSkills.putIfAbsent(skillEntity.getName(), skillEntity);

            ToolDefinition definition = resolveLocalToolDefinition(binding);
            if (definition == null || !StringUtils.hasText(definition.getCode())) {
                continue;
            }
            ToolSpecification executableSpecification = toolSpecProvider.toToolSpecification(
                    definition, resolveSkillSearchBehavior(skillEntity));
            dev.langchain4j.service.tool.ToolExecutor executableExecutor = guard(
                    new LangChain4jBindingToolExecutor(
                            objectMapper,
                            skillToolExecutor,
                            ctx.getTenantId(),
                            ctx.getAgentId(),
                            binding,
                            ctx.getSandboxPolicy()
                    ),
                    ctx,
                    binding,
                    SourceTypeEnum.SKILL.getCode()
            );
            executableToolsBySkill.computeIfAbsent(skillEntity.getName(), key -> new ArrayList<>())
                    .add(new ExecutableSkillTool(skillEntity.getName(), executableSpecification, executableExecutor));
        }
        if (boundSkills.isEmpty()) {
            return null;
        }

        List<dev.langchain4j.skills.Skill> skills = new ArrayList<>(boundSkills.size());
        for (Skill skillEntity : boundSkills.values()) {
            List<ExecutableSkillTool> executableTools = executableToolsBySkill.getOrDefault(skillEntity.getName(), List.of());
            skills.add(DefaultSkill.builder()
                    .name(skillEntity.getName())
                    .description(resolveSkillDescription(skillEntity))
                    .content(resolveSkillContent(skillEntity, executableTools, shouldExposeSkillImmediately(skillEntity)))
                    .resources(resolveSkillResources(skillEntity))
                    .build());
        }
        Skills skillBundle = Skills.builder()
                .skills(skills)
                .activateSkillToolConfig(ActivateSkillToolConfig.builder()
                        .name(ACTIVATE_SKILL_TOOL_NAME)
                        .description("激活指定 Skill，并在下一轮按需暴露该 Skill 对应的执行工具")
                        .parameterName(ACTIVATE_SKILL_PARAMETER_NAME)
                        .parameterDescription("要激活的 Skill 名称")
                        .build())
                .readResourceToolConfig(ReadResourceToolConfig.builder()
                        .name(READ_SKILL_RESOURCE_TOOL_NAME)
                        .description("读取指定 Skill 的资源内容")
                        .skillNameParameterName(READ_SKILL_RESOURCE_SKILL_NAME_PARAMETER)
                        .skillNameParameterDescription("目标 Skill 名称")
                        .relativePathParameterName(READ_SKILL_RESOURCE_PATH_PARAMETER)
                        .relativePathParameterDescription("要读取的资源相对路径")
                        .build())
                .build();
        mergeProviderResult(wrapSkillToolProvider(skillBundle.toolProvider()), providerRequest, availableTools, executors,
                ctx, SourceTypeEnum.SKILL.getCode());

        executableToolsBySkill.forEach((skillName, executableTools) -> {
            Skill skill = boundSkills.get(skillName);
            if (skill == null) {
                return;
            }
            if (shouldExposeSkillImmediately(skill)) {
                for (ExecutableSkillTool executableTool : executableTools) {
                    availableTools.putIfAbsent(executableTool.specification().name(), executableTool.specification());
                    executors.putIfAbsent(executableTool.specification().name(), executableTool.executor());
                }
                return;
            }
            lazyLoadedSkillTools.put(skillName, List.copyOf(executableTools));
        });
        return skillBundle.formatAvailableSkills();
    }

    private void mergeDirectMcpProviders(AgentExecutionContext ctx,
                                         List<AgentToolBinding> bindings,
                                         ToolProviderRequest providerRequest,
                                         Map<String, ToolSpecification> availableTools,
                                         Map<String, dev.langchain4j.service.tool.ToolExecutor> executors,
                                         List<AutoCloseable> closeables) {
        Map<String, List<String>> serverToolNames = new LinkedHashMap<>();
        for (AgentToolBinding binding : bindings) {
            String sourceType = normalizeSourceType(binding.getSourceType());
            if (!SourceTypeEnum.MCP.getCode().equals(sourceType) || !StringUtils.hasText(binding.getSourceRefId())) {
                continue;
            }
            serverToolNames.computeIfAbsent(binding.getSourceRefId(), key -> new ArrayList<>()).add(binding.getToolCode());
        }

        for (Map.Entry<String, List<String>> entry : serverToolNames.entrySet()) {
            McpServer server = mcpServerMapper.selectOne(new LambdaQueryWrapper<McpServer>()
                    .eq(McpServer::getId, entry.getKey())
                    .eq(McpServer::getTenantId, ctx.getTenantId())
                    .last("LIMIT 1"));
            if (server == null) {
                continue;
            }
            LangChain4jMcpClientFactory.ClientLease lease = null;
            try {
                lease = mcpClientFactory.borrow(ctx.getTenantId(), ctx.getUserId(), server);
                closeables.add(lease);
                McpClient mcpClient = lease.client();
                McpToolProvider provider = McpToolProvider.builder()
                        .mcpClients(mcpClient)
                        .failIfOneServerFails(false)
                        .filterToolNames(entry.getValue())
                        .toolSpecificationMapper((client, spec) -> spec.toBuilder()
                                .addMetadata(ToolSpecification.METADATA_SEARCH_BEHAVIOR, SearchBehavior.SEARCHABLE)
                                .build())
                        .build();
                mergeProviderResult(provider, providerRequest, availableTools, executors, ctx, SourceTypeEnum.MCP.getCode());
            } catch (Exception exception) {
                if (lease != null) lease.invalidate();
                log.warn("MCP 工具提供器初始化失败: serverId={}, error={}", entry.getKey(), exception.getMessage());
            }
        }
    }

    private void mergeLocalBindings(AgentExecutionContext ctx,
                                    List<AgentToolBinding> bindings,
                                    Map<String, ToolSpecification> availableTools,
                                    Map<String, dev.langchain4j.service.tool.ToolExecutor> executors,
                                    Map<String, ToolIoTypeEnum> ioTypeIndex) {
        DesktopExecutionTarget target = DesktopExecutionTarget.from(ctx.getInputContext());
        for (AgentToolBinding binding : bindings) {
            String sourceType = normalizeSourceType(binding.getSourceType());
            if (SourceTypeEnum.MCP.getCode().equals(sourceType)
                    || SourceTypeEnum.SKILL.getCode().equals(sourceType)) {
                continue;
            }

            ToolDefinition definition = resolveLocalToolDefinition(binding);
            if (definition == null || !StringUtils.hasText(definition.getCode())) {
                continue;
            }
            boolean desktopBuiltin = target != null && SourceTypeEnum.BUILTIN.getCode().equals(sourceType)
                    && definition.getCode().startsWith("sys.");
            if (desktopBuiltin) {
                boolean supportsExactEdit = "sys.edit".equals(definition.getCode()) && desktopToolBroker.supportsExactEdit(
                        ctx.getTenantId(), ctx.getUserId(), target.deviceId());
                boolean supportsReadRange = "sys.read".equals(definition.getCode()) && desktopToolBroker.supportsReadRange(
                        ctx.getTenantId(), ctx.getUserId(), target.deviceId());
                JsonNode desktopSchema = DesktopToolSchema.forCode(definition.getCode(), objectMapper, supportsExactEdit,
                        supportsReadRange);
                if (desktopSchema == null) {
                    continue;
                }
                definition.setInputSchema(desktopSchema);
            }
            SearchBehavior searchBehavior = SourceTypeEnum.BUILTIN.getCode().equals(sourceType)
                    ? SearchBehavior.ALWAYS_VISIBLE
                    : SearchBehavior.SEARCHABLE;
            ToolSpecification specification = toolSpecProvider.toToolSpecification(definition, searchBehavior);
            if (availableTools.putIfAbsent(specification.name(), specification) != null) {
                continue;
            }
            ioTypeIndex.put(specification.name(), definition.getIoType());

            com.schemaplexai.service.agent.tool.executor.ToolExecutor delegate;
            if (SourceTypeEnum.API_GATEWAY.getCode().equals(sourceType)) {
                delegate = apiGatewayToolExecutor;
            } else if (SourceTypeEnum.BUILTIN.getCode().equals(sourceType)) {
                delegate = desktopBuiltin
                        ? new DesktopToolExecutor(desktopToolBroker, ctx, target, sandboxGuard)
                        : builtinToolExecutor;
            } else {
                delegate = skillToolExecutor;
            }
            executors.putIfAbsent(specification.name(),
                    guard(
                            new LangChain4jBindingToolExecutor(
                                    objectMapper,
                                    delegate,
                                    ctx.getTenantId(),
                                    ctx.getAgentId(),
                                    binding,
                                    ctx.getSandboxPolicy()
                            ),
                            ctx,
                            binding,
                            normalizeSourceType(binding.getSourceType())
                    ));
        }
    }

    private ToolDefinition resolveLocalToolDefinition(AgentToolBinding binding) {
        String sourceType = normalizeSourceType(binding.getSourceType());
        if (SourceTypeEnum.BUILTIN.getCode().equals(sourceType)) {
            BuiltinTool builtinTool = builtinToolMapper.selectOne(new LambdaQueryWrapper<BuiltinTool>()
                    .eq(BuiltinTool::getCode, binding.getToolCode())
                    .eq(BuiltinTool::getEnabled, true)
                    .last("LIMIT 1"));
            return ToolDefinition.builder()
                    .code(binding.getToolCode())
                    .name(builtinTool != null && StringUtils.hasText(builtinTool.getName()) ? builtinTool.getName() : binding.getToolCode())
                    .description(builtinTool != null && StringUtils.hasText(builtinTool.getDescription())
                            ? builtinTool.getDescription()
                            : "系统内置工具: " + binding.getToolCode())
                    .inputSchema(parseBuiltinSchema(builtinTool))
                    .sourceType(sourceType)
                    .userVisible(true)
                    .ioType(builtinTool != null && StringUtils.hasText(builtinTool.getIoType())
                            ? ToolIoTypeEnum.fromCode(builtinTool.getIoType())
                            : ToolIoTypeEnum.READ_WRITE)
                    .build();
        }
        if (SourceTypeEnum.SKILL.getCode().equals(sourceType) && StringUtils.hasText(binding.getSourceRefId())) {
            Skill skill = skillMapper.selectOne(new LambdaQueryWrapper<Skill>()
                    .eq(Skill::getId, binding.getSourceRefId())
                    .last("LIMIT 1"));
            if (skill == null) {
                return null;
            }
            return ToolDefinition.builder()
                    .code(binding.getToolCode())
                    .name(StringUtils.hasText(skill.getDisplayName()) ? skill.getDisplayName() : binding.getToolCode())
                    .description(resolveSkillDescription(skill))
                    .inputSchema(buildSkillSchema(skill.getParameters()))
                    .sourceType(sourceType)
                    .userVisible(true)
                    .build();
        }
        if (SourceTypeEnum.API_GATEWAY.getCode().equals(sourceType) && StringUtils.hasText(binding.getSourceRefId())) {
            ApiGateway gateway = apiGatewayMapper.selectById(binding.getSourceRefId());
            if (gateway == null || !"active".equals(gateway.getStatus())) {
                return null;
            }
            var schemaNode = objectMapper.createObjectNode();
            schemaNode.put("type", "object");
            var propsNode = schemaNode.putObject("properties");
            propsNode.putObject("body").put("type", "string").put("description", "请求体内容(JSON字符串)");
            return ToolDefinition.builder()
                    .code(binding.getToolCode())
                    .name(gateway.getName())
                    .description("API网关: " + gateway.getMethod() + " " + gateway.getUrl()
                            + (StringUtils.hasText(gateway.getDescription()) ? " - " + gateway.getDescription() : ""))
                    .inputSchema(schemaNode)
                    .sourceType(sourceType)
                    .userVisible(true)
                    .build();
        }
        return null;
    }

    private JsonNode parseBuiltinSchema(BuiltinTool builtinTool) {
        if (builtinTool == null || !StringUtils.hasText(builtinTool.getInputSchema())) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(builtinTool.getInputSchema());
        } catch (Exception exception) {
            log.warn("解析内置工具 Schema 失败: toolCode={}, error={}", builtinTool.getCode(), exception.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private JsonNode buildSkillSchema(List<Object> parameters) {
        JsonNode schemaNode = objectMapper.valueToTree(Map.of(
                "type", "object",
                "properties", buildSkillProperties(parameters),
                "required", buildRequiredFields(parameters)
        ));
        return schemaNode != null ? schemaNode : objectMapper.createObjectNode();
    }

    private Map<String, Object> buildSkillProperties(List<Object> parameters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        if (parameters == null) {
            return properties;
        }
        for (Object parameter : parameters) {
            if (!(parameter instanceof Map<?, ?> parameterMap)) {
                continue;
            }
            String name = firstText(parameterMap, "name");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("type", firstText(parameterMap, "type", "valueType", "dataType", "kind", "schemaType"));
            String description = firstText(parameterMap, "description", "label", "title");
            if (StringUtils.hasText(description)) {
                definition.put("description", description);
            }
            Object defaultValue = parameterMap.get("default");
            if (defaultValue != null) {
                definition.put("default", defaultValue);
            }
            Object enumValues = parameterMap.get("enumValues");
            if (enumValues instanceof List<?> enumList && !enumList.isEmpty()) {
                definition.put("enum", enumList);
            }
            properties.put(name, definition);
        }
        return properties;
    }

    private List<String> buildRequiredFields(List<Object> parameters) {
        List<String> required = new ArrayList<>();
        if (parameters == null) {
            return required;
        }
        for (Object parameter : parameters) {
            if (!(parameter instanceof Map<?, ?> parameterMap)) {
                continue;
            }
            String name = firstText(parameterMap, "name");
            Object requiredFlag = parameterMap.get("required");
            if (StringUtils.hasText(name) && Boolean.TRUE.equals(requiredFlag)) {
                required.add(name);
            }
        }
        return required;
    }

    private List<SkillResource> resolveSkillResources(Skill skill) {
        List<SkillResource> resources = new ArrayList<>();
        if (skill.getResources() == null) {
            return resources;
        }
        for (Object resourceObject : skill.getResources()) {
            if (!(resourceObject instanceof Map<?, ?> resourceMap)) {
                continue;
            }
            String relativePath = firstText(resourceMap, "relativePath", "path", "name");
            String content = firstText(resourceMap, "content", "text");
            if (!StringUtils.hasText(relativePath) || !StringUtils.hasText(content)) {
                continue;
            }
            resources.add(SkillResource.builder()
                    .relativePath(relativePath)
                    .content(content)
                    .build());
        }
        return resources;
    }

    private String resolveSkillDescription(Skill skill) {
        if (StringUtils.hasText(skill.getDescription())) {
            return skill.getDescription().trim();
        }
        return "平台技能: " + skill.getName();
    }

    private String resolveSkillContent(Skill skill,
                                       List<ExecutableSkillTool> executableTools,
                                       boolean immediatelyVisible) {
        String executableToolSummary = executableTools.stream()
                .map(tool -> tool.specification().name())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("无");
        if (StringUtils.hasText(skill.getSkillPrompt())) {
            return skill.getSkillPrompt().trim()
                    + "\n\n执行工具: " + executableToolSummary
                    + (immediatelyVisible
                    ? "\n该 Skill 的执行工具已直接可见，可按参数约束直接调用。"
                    : "\n请先调用 " + ACTIVATE_SKILL_TOOL_NAME + " 激活该 Skill，再使用对应执行工具。");
        }
        StringBuilder builder = new StringBuilder();
        builder.append("技能名称: ").append(skill.getName()).append("\n");
        if (StringUtils.hasText(skill.getDescription())) {
            builder.append("用途: ").append(skill.getDescription()).append("\n");
        }
        builder.append("执行工具: ").append(executableToolSummary).append("\n");
        if (!CollectionUtils.isEmpty(skill.getParameters())) {
            builder.append("输入参数: ");
            List<String> parameterNames = new ArrayList<>();
            for (Object parameterObject : skill.getParameters()) {
                if (parameterObject instanceof Map<?, ?> parameterMap) {
                    String name = firstText(parameterMap, "name");
                    if (StringUtils.hasText(name)) {
                        parameterNames.add(name);
                    }
                }
            }
            builder.append(String.join(", ", parameterNames)).append("\n");
        }
        if (immediatelyVisible) {
            builder.append("该 Skill 对应工具已直接可见，可在必要时直接调用。");
        } else {
            builder.append("先调用 ").append(ACTIVATE_SKILL_TOOL_NAME)
                    .append(" 激活 Skill，再调用对应工具，不要在缺少输入时猜测参数。");
        }
        return builder.toString();
    }

    private SearchBehavior resolveSkillSearchBehavior(Skill skill) {
        Map<String, Object> exposureConfig = skill.getExposureConfig();
        String searchBehavior = exposureConfig != null ? firstText(exposureConfig, "searchBehavior") : null;
        if ("always_visible".equalsIgnoreCase(searchBehavior)) {
            return SearchBehavior.ALWAYS_VISIBLE;
        }
        return SearchBehavior.SEARCHABLE;
    }

    private boolean shouldExposeSkillImmediately(Skill skill) {
        Map<String, Object> exposureConfig = skill.getExposureConfig();
        if (exposureConfig == null || exposureConfig.isEmpty()) {
            return false;
        }
        String searchBehavior = firstText(exposureConfig, "searchBehavior");
        if ("always_visible".equalsIgnoreCase(searchBehavior)) {
            return true;
        }
        boolean progressiveDisclosure = readBoolean(exposureConfig, "progressiveDisclosure", true);
        boolean lazyLoad = readBoolean(exposureConfig, "lazyLoad", true);
        return !progressiveDisclosure && !lazyLoad;
    }

    private void mergeProviderResult(ToolProvider toolProvider,
                                     ToolProviderRequest providerRequest,
                                     Map<String, ToolSpecification> availableTools,
                                     Map<String, dev.langchain4j.service.tool.ToolExecutor> executors,
                                     AgentExecutionContext ctx,
                                     String sourceType) {
        ToolProviderResult providerResult = toolProvider.provideTools(providerRequest);
        if (providerResult == null || providerResult.tools() == null) {
            return;
        }
        providerResult.tools().forEach((toolSpecification, toolExecutor) -> {
            ToolSpecification normalizedSpecification = ToolNameNormalizer.normalizeSpecification(toolSpecification);
            String originalToolName = toolSpecification.name();
            String normalizedToolName = normalizedSpecification != null ? normalizedSpecification.name() : originalToolName;
            dev.langchain4j.service.tool.ToolExecutor normalizedExecutor =
                    normalizedToolName != null && normalizedToolName.equals(originalToolName)
                            ? toolExecutor
                            : new AliasedToolExecutor(toolExecutor, originalToolName);
            dev.langchain4j.service.tool.ToolExecutor guardedExecutor = guard(normalizedExecutor, ctx, null, sourceType);
            availableTools.putIfAbsent(normalizedToolName, normalizedSpecification);
            executors.putIfAbsent(normalizedToolName, guardedExecutor);
        });
    }

    private ToolProvider wrapSkillToolProvider(ToolProvider delegate) {
        return providerRequest -> {
            ToolProviderResult providerResult = delegate.provideTools(providerRequest);
            if (providerResult == null || providerResult.tools() == null) {
                return providerResult;
            }
            Map<ToolSpecification, dev.langchain4j.service.tool.ToolExecutor> wrappedTools = new LinkedHashMap<>();
            providerResult.tools().forEach((toolSpecification, toolExecutor) -> {
                if (ACTIVATE_SKILL_TOOL_NAME.equals(toolSpecification.name())) {
                    wrappedTools.put(toolSpecification, new SkillActivationTrackingToolExecutor(
                            objectMapper, toolExecutor, ACTIVATE_SKILL_PARAMETER_NAME));
                    return;
                }
                wrappedTools.put(toolSpecification, toolExecutor);
            });
            return ToolProviderResult.builder().addAll(wrappedTools).build();
        };
    }

    private InvocationContext buildInvocationContext(AgentExecutionContext ctx, String conversationId, int round) {
        InvocationParameters invocationParameters = InvocationParameters.from(Map.of(
                "tenantId", ctx.getTenantId(),
                "agentId", ctx.getAgentId(),
                "executionId", ctx.getExecutionId(),
                "round", round
        ));
        return InvocationContext.builder()
                .invocationId(UUID.randomUUID())
                .interfaceName("AgentExecutionEngine")
                .methodName("runAgenticLoop")
                .chatMemoryId(conversationId)
                .invocationParameters(invocationParameters)
                .timestamp(Instant.now())
                .build();
    }

    private List<AgentToolBinding> resolveBindings(String tenantId, String agentId, List<AgentToolBinding> overrideBindings) {
        if (!CollectionUtils.isEmpty(overrideBindings)) {
            return overrideBindings.stream()
                    .filter(binding -> Boolean.TRUE.equals(binding.getEnabled()))
                    .sorted((left, right) -> Integer.compare(
                            left.getPriority() != null ? left.getPriority() : 100,
                            right.getPriority() != null ? right.getPriority() : 100))
                    .toList();
        }
        return agentToolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentToolBinding>()
                        .eq(AgentToolBinding::getTenantId, tenantId)
                        .eq(AgentToolBinding::getAgentId, agentId)
                        .eq(AgentToolBinding::getEnabled, true)
                        .orderByAsc(AgentToolBinding::getPriority)
                        .orderByAsc(AgentToolBinding::getCreatedAt)
        );
    }

    /**
     * 统一安全装饰器：主链所有工具执行（本地/Skill/MCP）必须经过该入口
     */
    private dev.langchain4j.service.tool.ToolExecutor guard(dev.langchain4j.service.tool.ToolExecutor delegate,
                                                            AgentExecutionContext ctx,
                                                            AgentToolBinding binding,
                                                            String sourceType) {
        return new GuardedToolExecutor(
                delegate,
                objectMapper,
                ctx.getTenantId(),
                ctx.getAgentId(),
                binding,
                sourceType,
                ctx.getSandboxPolicy(),
                sandboxGuard,
                securityRuntimeGuardServiceProvider.getObject(),
                ctx.getExecutionId()
        );
    }

    private String normalizeSourceType(String sourceType) {
        return StringUtils.hasText(sourceType) ? sourceType.trim().toLowerCase() : SourceTypeEnum.BUILTIN.getCode();
    }

    private String firstText(Map<?, ?> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private boolean readBoolean(Map<String, Object> source, String key, boolean defaultValue) {
        if (source == null || !source.containsKey(key)) {
            return defaultValue;
        }
        Object value = source.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value != null) {
            return Boolean.parseBoolean(String.valueOf(value));
        }
        return defaultValue;
    }

    @Getter
    @RequiredArgsConstructor
    public static class AgentToolSession implements AutoCloseable {

        private final ToolServiceContext baseContext;
        private final LangChainToolSearchAdapter toolSearchAdapter;
        private final AgentExecutionContext executionContext;
        private final String conversationId;
        private final String skillPromptBlock;
        private final List<AutoCloseable> closeables;
        private final Map<String, List<ExecutableSkillTool>> lazyLoadedSkillTools;
        private final Map<String, ToolIoTypeEnum> ioTypeIndex;
        private AgentExecutionEvidenceService evidenceService;
        private ObjectMapper objectMapper;

        public RoundToolContext buildRoundContext(ChatMemory chatMemory, int round) {
            InvocationContext invocationContext = InvocationContext.builder()
                    .invocationId(UUID.randomUUID())
                    .interfaceName("AgentExecutionEngine")
                    .methodName("runAgenticLoop")
                    .chatMemoryId(conversationId)
                    .invocationParameters(InvocationParameters.from(Map.of(
                            "tenantId", executionContext.getTenantId(),
                            "agentId", executionContext.getAgentId(),
                            "executionId", executionContext.getExecutionId(),
                            "round", round
                    )))
                    .timestamp(Instant.now())
                    .build();
            ToolServiceContext effectiveContext = toolSearchAdapter.adjust(baseContext, chatMemory, invocationContext);
            Map<String, ToolIoTypeEnum> effectiveIoTypes = new LinkedHashMap<>(ioTypeIndex);
            // 框架在此处只新增工具搜索执行器，搜索仅查询本轮工具目录。
            effectiveContext.toolExecutors().forEach((name, executor) -> {
                if (baseContext.toolExecutors().get(name) != executor) {
                    effectiveIoTypes.put(name, ToolIoTypeEnum.READ);
                }
            });
            effectiveContext = mergeActivatedSkillTools(effectiveContext, chatMemory);
            if (evidenceService != null) {
                effectiveContext = withEvidenceTools(effectiveContext, chatMemory, effectiveIoTypes);
            }
            return new RoundToolContext(effectiveContext, invocationContext, effectiveIoTypes);
        }

        private ToolServiceContext withEvidenceTools(ToolServiceContext context, ChatMemory memory,
                                                     Map<String, ToolIoTypeEnum> ioTypes) {
            boolean hasArtifacts = memory.messages().stream().anyMatch(message ->
                    message instanceof ToolExecutionResultMessage result
                            && result.attributes().containsKey(AgentExecutionEvidenceService.ARTIFACT_ATTRIBUTE));
            List<String> members = executionContext.getTeamMemberEvidenceIds();
            if (!hasArtifacts && (members == null || members.isEmpty())) return context;

            var executors = new LinkedHashMap<>(context.toolExecutors());
            var effective = new ArrayList<>(context.effectiveTools());
            var available = new ArrayList<>(context.availableTools());
            if (hasArtifacts) {
                addEvidenceTool(executors, effective, ioTypes, "read_agent_artifact",
                        "读取当前执行的产物。传入 artifact_ref:// 后的 ID，从 offset=0 开始，按返回的 nextOffset 翻页。",
                        (id, offset) -> objectMapper.writeValueAsString(evidenceService.readArtifactPage(
                                executionContext.getTenantId(), executionContext.getExecutionId(), id, offset)));
            }
            if (members != null && !members.isEmpty()) {
                addEvidenceTool(executors, effective, ioTypes, "read_team_member_evidence",
                        "按需读取本队成员的实际输出，每次最多 4000 字符。可用执行 ID " + String.join(", ", members),
                        (id, offset) -> objectMapper.writeValueAsString(evidenceService.readMemberEvidencePage(executionContext, id, offset)));
            }
            effective.stream().filter(spec -> available.stream().noneMatch(existing -> existing.name().equals(spec.name())))
                    .toList().forEach(available::add);
            return context.toBuilder().toolExecutors(executors).effectiveTools(effective)
                    .availableTools(available).toolSpecifications(available).build();
        }

        private void addEvidenceTool(Map<String, dev.langchain4j.service.tool.ToolExecutor> executors,
                                     List<ToolSpecification> tools, Map<String, ToolIoTypeEnum> ioTypes,
                                     String name, String description, EvidenceReader reader) {
            var spec = ToolSpecification.builder().name(name).description(description)
                    .parameters(JsonObjectSchema.builder().addStringProperty("id").addIntegerProperty("offset")
                            .required("id").build()).build();
            executors.put(name, new dev.langchain4j.service.tool.ToolExecutor() {
                @Override
                public String execute(ToolExecutionRequest request, Object memoryId) {
                    return executeWithContext(request, null).resultText();
                }

                @Override
                public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext invocationContext) {
                    try {
                        var args = objectMapper.readTree(request.arguments());
                        var offset = args.path("offset");
                        if (!offset.isMissingNode() && (!offset.isIntegralNumber() || !offset.canConvertToInt())) {
                            throw new IllegalArgumentException("偏移无效");
                        }
                        return ToolExecutionResult.builder()
                                .resultText(reader.read(args.path("id").asText(), offset.asInt())).isError(false).build();
                    } catch (Exception failure) {
                        return ToolExecutionResult.builder().isError(true).resultText("引用不可用或超出本次执行范围").build();
                    }
                }
            });
            tools.removeIf(existing -> name.equals(existing.name()));
            tools.add(spec);
            ioTypes.put(name, ToolIoTypeEnum.READ);
        }

        @FunctionalInterface
        private interface EvidenceReader {
            String read(String id, int offset) throws Exception;
        }

        public String augmentSystemPrompt(String systemPrompt, int maxToolCallsPerRound) {
            if (evidenceService != null && executionContext.getTeamMemberEvidenceIds() != null
                    && !executionContext.getTeamMemberEvidenceIds().isEmpty()) {
                systemPrompt += "\n\n## 成员交付查证\n"
                        + "成员摘要可能省略细节。需要精确数字、原文或完整依据时，使用 read_team_member_evidence 按成员执行 ID 读取。"
                        + "从 offset=0 开始，按返回的 nextOffset 翻页，直到为 null。";
            }
            if (DesktopExecutionTarget.from(executionContext.getInputContext()) != null) {
                boolean supportsExactEdit = baseContext.availableTools().stream().anyMatch(tool ->
                        "sys_edit".equals(tool.name()) && tool.parameters() instanceof JsonObjectSchema schema
                                && schema.properties().containsKey("oldText"));
                String editingInstructions = supportsExactEdit
                        ? "sys_write 用完整 content 保存新文件。局部修改优先用 sys_edit，提供唯一 oldText 和 newText。"
                        : "sys.write 与 sys.edit 都接收 path 和完整 content。";
                String desktopInstructions = "\n\n## 本机工作区执行边界\n"
                        + "本任务的所有 sys.* 工具均在当前用户已授权的本机工作区执行。"
                        + "desktopWorkspaceId 只是授权标识，不是真实目录。"
                        + "path、workingDirectory、source、destination 等文件参数必须使用工作区相对路径，工作区根目录用 .。"
                        + "不要使用服务端工作区路径、绝对路径、..、~ 或推测用户设备目录；"
                        + "项目文件与开发环境以本机工具返回的实际内容为准，企业知识仍作为任务规则。"
                        + "\n修改已有文件前，仅查看完成改动所需的文件，可同轮读取相互独立的文件。"
                        + "文件未发生变化时，复用同一文件已成功读取且未截断的内容。文件已变化或现有证据不足时，再读取相关文件。"
                        + "写入新文件前确认父目录存在。若需使用 sys_mkdir，先单独创建目录，拿到成功回执后再提交依赖该目录的写入。"
                        + editingInstructions + "保存成功后按任务要求验证改动，优先用测试或构建实证。"
                        + "交付中只列相对于授权工作区的路径，Git 根验证报告是否与授权目录一致。"
                        + "需要可复核证据时，按原任务列出检查对象、输入、实际输出和退出码。"
                        + "测试数以最终构建回执的汇总为准。若先验证此前构建的 CLI、再完成最终构建，分别说明两者。\n"
                        + "任何本机工具失败、超时或离线都不得改在云端执行，也不得自动重试状态不明的写操作。";
                boolean supportsReadRange = baseContext.availableTools().stream().anyMatch(tool ->
                        "sys_read".equals(tool.name()) && tool.parameters() instanceof JsonObjectSchema schema
                                && schema.properties().containsKey("startLine"));
                if (supportsReadRange) {
                    desktopInstructions += "\n已知待修改的行号时，用 sys_read 的 startLine 和 endLine 读取必要片段。"
                            + "需要理解完整类或重写文件时读取全文。片段的行号仅用于定位，不属于原文。";
                }
                systemPrompt = (systemPrompt == null ? "" : systemPrompt) + desktopInstructions;
                String shellName = ToolNameNormalizer.normalize("sys.bash");
                if (StringUtils.hasText(executionContext.getDesktopVerificationCommand())
                        && baseContext.toolExecutors().get(shellName) instanceof GuardedToolExecutor shell
                        && shell.isDesktopTool()) {
                    systemPrompt += "\n每项 Shell 验收使用可独立核对退出码的调用，避免命令串联、管道和重定向。"
                            + "每轮最多可提交 " + maxToolCallsPerRound + " 笔工具调用。"
                            + "按此上限将同一产物上互不依赖的 CLI 检查成组提交，先逐项覆盖工单要求的正常和异常输入。"
                            + "无效输入的退出码和输出符合预期时，该用例已通过。"
                            + "同一产物未变化且回执已证明契约时，复用该回执。有具体证据缺口时才追加变体。"
                            + "核对 Java 输出时，JDK 自动打印的 Picked up JDK_JAVA_OPTIONS: 行属于启动器诊断。"
                            + "保留沙箱工具链环境，直接核对其余标准错误内容、标准输出和退出码。";
                    if (!StringUtils.hasText(executionContext.getParentExecutionId())) {
                        systemPrompt += "\n文件已核对且随后未被修改时，最终构建前复用已有读取证据。"
                                + "文件可能有变化或证据不足时，重新读取所需文件。";
                    }
                    if (!executionContext.isDesktopVerificationDelegated()) {
                        systemPrompt += "\n完成实现和测试的第一次可运行改动后，及时运行相关构建或测试。"
                                + "编译失败时集中修复回执中的相关错误，再重新构建。"
                                + "\n若你负责最终验证，先完成用户要求的各项检查和写入，收到并核对全部前序回执。"
                                + "在接下来的一轮只用 " + shellName + " 在工作区根目录（workingDirectory 为 .）执行以下命令。"
                                + "命令必须原样提交，退出码必须为 0，之后直接输出交付结果。"
                                + "若未取得有效回执，在交付说明中明确指出。\n"
                                + executionContext.getDesktopVerificationCommand();
                    }
                }
            }
            if (!StringUtils.hasText(skillPromptBlock)) {
                return systemPrompt;
            }
            if (!StringUtils.hasText(systemPrompt)) {
                return skillPromptBlock;
            }
            return systemPrompt + "\n\n## 可激活技能\n" + skillPromptBlock;
        }

        private ToolServiceContext mergeActivatedSkillTools(ToolServiceContext effectiveContext, ChatMemory chatMemory) {
            if (lazyLoadedSkillTools == null || lazyLoadedSkillTools.isEmpty() || chatMemory == null || chatMemory.messages() == null) {
                return effectiveContext;
            }
            LinkedHashSet<String> activatedSkillNames = new LinkedHashSet<>();
            for (ChatMessage message : chatMemory.messages()) {
                if (!(message instanceof ToolExecutionResultMessage toolMessage)) {
                    continue;
                }
                if (!ACTIVATE_SKILL_TOOL_NAME.equals(toolMessage.toolName()) || toolMessage.attributes() == null) {
                    continue;
                }
                Object activatedSkill = toolMessage.attributes().get(ACTIVATED_SKILL_NAME_ATTRIBUTE);
                if (activatedSkill != null && StringUtils.hasText(String.valueOf(activatedSkill))) {
                    activatedSkillNames.add(String.valueOf(activatedSkill).trim());
                }
            }
            if (activatedSkillNames.isEmpty()) {
                return effectiveContext;
            }

            LinkedHashMap<String, ToolSpecification> availableTools = new LinkedHashMap<>();
            for (ToolSpecification toolSpecification : effectiveContext.availableTools()) {
                availableTools.putIfAbsent(toolSpecification.name(), toolSpecification);
            }
            LinkedHashMap<String, ToolSpecification> effectiveTools = new LinkedHashMap<>();
            for (ToolSpecification toolSpecification : effectiveContext.effectiveTools()) {
                effectiveTools.putIfAbsent(toolSpecification.name(), toolSpecification);
            }
            LinkedHashMap<String, dev.langchain4j.service.tool.ToolExecutor> toolExecutors =
                    new LinkedHashMap<>(effectiveContext.toolExecutors());

            for (String skillName : activatedSkillNames) {
                List<ExecutableSkillTool> executableTools = lazyLoadedSkillTools.get(skillName);
                if (CollectionUtils.isEmpty(executableTools)) {
                    continue;
                }
                for (ExecutableSkillTool executableTool : executableTools) {
                    availableTools.putIfAbsent(executableTool.specification().name(), executableTool.specification());
                    effectiveTools.putIfAbsent(executableTool.specification().name(), executableTool.specification());
                    toolExecutors.putIfAbsent(executableTool.specification().name(), executableTool.executor());
                }
            }
            return effectiveContext.toBuilder()
                    .availableTools(new ArrayList<>(availableTools.values()))
                    .toolSpecifications(new ArrayList<>(availableTools.values()))
                    .effectiveTools(new ArrayList<>(effectiveTools.values()))
                    .toolExecutors(toolExecutors)
                    .build();
        }

        @Override
        public void close() {
            for (AutoCloseable closeable : closeables) {
                try {
                    closeable.close();
                } catch (Exception exception) {
                    log.debug("关闭工具会话资源失败: {}", exception.getMessage());
                }
            }
        }
    }

    public record RoundToolContext(ToolServiceContext toolServiceContext,
                                   InvocationContext invocationContext,
                                   Map<String, ToolIoTypeEnum> ioTypeIndex) {
        public boolean isDesktopTool(String name) {
            return toolServiceContext.toolExecutors().get(name) instanceof GuardedToolExecutor executor
                    && executor.isDesktopTool();
        }
    }

    private record ExecutableSkillTool(String skillName,
                                       ToolSpecification specification,
                                       dev.langchain4j.service.tool.ToolExecutor executor) {
    }

    @RequiredArgsConstructor
    private static class SkillActivationTrackingToolExecutor implements dev.langchain4j.service.tool.ToolExecutor {

        private final ObjectMapper objectMapper;
        private final dev.langchain4j.service.tool.ToolExecutor delegate;
        private final String skillNameParameter;

        @Override
        public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext invocationContext) {
            ToolExecutionResult executionResult = delegate.executeWithContext(request, invocationContext);
            if (executionResult == null || executionResult.isError()) {
                return executionResult;
            }
            String activatedSkillName = extractSkillName(request.arguments());
            if (!StringUtils.hasText(activatedSkillName)) {
                return executionResult;
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            if (executionResult.attributes() != null) {
                attributes.putAll(executionResult.attributes());
            }
            attributes.put(ACTIVATED_SKILL_NAME_ATTRIBUTE, activatedSkillName);
            return ToolExecutionResult.builder()
                    .isError(false)
                    .result(executionResult.result())
                    .resultText(executionResult.resultText())
                    .attributes(attributes)
                    .build();
        }

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            ToolExecutionResult executionResult = executeWithContext(request, null);
            return executionResult != null ? executionResult.resultText() : null;
        }

        private String extractSkillName(String arguments) {
            if (!StringUtils.hasText(arguments)) {
                return null;
            }
            try {
                JsonNode root = objectMapper.readTree(arguments);
                JsonNode value = root.path(skillNameParameter);
                if (value.isTextual() && StringUtils.hasText(value.asText())) {
                    return value.asText().trim();
                }
            } catch (Exception exception) {
                log.debug("解析技能激活参数失败: {}", exception.getMessage());
            }
            return null;
        }
    }

    @RequiredArgsConstructor
    private static class AliasedToolExecutor implements dev.langchain4j.service.tool.ToolExecutor {

        private final dev.langchain4j.service.tool.ToolExecutor delegate;
        private final String actualToolName;

        @Override
        public ToolExecutionResult executeWithContext(ToolExecutionRequest request, InvocationContext invocationContext) {
            return delegate.executeWithContext(rewriteRequest(request), invocationContext);
        }

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            return delegate.execute(rewriteRequest(request), memoryId);
        }

        private ToolExecutionRequest rewriteRequest(ToolExecutionRequest request) {
            if (request == null || !StringUtils.hasText(actualToolName)) {
                return request;
            }
            return ToolExecutionRequest.builder()
                    .id(request.id())
                    .name(actualToolName)
                    .arguments(request.arguments())
                    .build();
        }
    }
}
