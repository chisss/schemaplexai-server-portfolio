package com.schemaplexai.service.agent.tool.langchain4j;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.BuiltinTool;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.service.ai.LangChain4jToolSpecProvider;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.tool.executor.BuiltinToolExecutor;
import com.schemaplexai.service.agent.tool.executor.SkillToolExecutor;
import com.schemaplexai.service.agent.tool.model.ToolDefinition;
import com.schemaplexai.service.integration.mcp.LangChain4jMcpClientFactory;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolServiceContext;
import dev.langchain4j.service.tool.search.ToolSearchService;
import dev.langchain4j.service.tool.search.simple.SimpleToolSearchStrategy;
import dev.langchain4j.skills.ActivateSkillToolConfig;
import dev.langchain4j.skills.DefaultSkill;
import dev.langchain4j.skills.ReadResourceToolConfig;
import dev.langchain4j.skills.SkillResource;
import dev.langchain4j.skills.Skills;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
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
    private final BuiltinToolExecutor builtinToolExecutor;
    private final SkillToolExecutor skillToolExecutor;
    private final LangChain4jToolSpecProvider toolSpecProvider;
    private final LangChain4jMcpClientFactory mcpClientFactory;

    private final ToolSearchService toolSearchService = new ToolSearchService(SimpleToolSearchStrategy.builder().build());

    public AgentToolSession openSession(AgentExecutionContext ctx, String conversationId) {
        List<AgentToolBinding> bindings = resolveBindings(ctx.getTenantId(), ctx.getAgentId(), ctx.getOverrideToolBindings());
        LinkedHashMap<String, ToolSpecification> availableTools = new LinkedHashMap<>();
        LinkedHashMap<String, dev.langchain4j.service.tool.ToolExecutor> executors = new LinkedHashMap<>();
        LinkedHashMap<String, List<ExecutableSkillTool>> lazyLoadedSkillTools = new LinkedHashMap<>();
        List<AutoCloseable> closeables = new ArrayList<>();

        InvocationContext invocationContext = buildInvocationContext(ctx, conversationId, 0);
        ToolProviderRequest providerRequest = ToolProviderRequest.builder()
                .invocationContext(invocationContext)
                .userMessage(UserMessage.from("初始化工具上下文"))
                .build();

        String skillPromptBlock = mergeSkillProviders(ctx, bindings, providerRequest, availableTools, executors, lazyLoadedSkillTools);
        mergeDirectMcpProviders(bindings, providerRequest, availableTools, executors, closeables);
        mergeLocalBindings(ctx, bindings, availableTools, executors);

        List<ToolSpecification> toolSpecifications = new ArrayList<>(availableTools.values());
        ToolServiceContext baseContext = ToolServiceContext.builder()
                .availableTools(toolSpecifications)
                .toolSpecifications(toolSpecifications)
                .effectiveTools(toolSpecifications)
                .toolExecutors(executors)
                .build();
        return new AgentToolSession(baseContext, toolSearchService, ctx, conversationId, skillPromptBlock, closeables, lazyLoadedSkillTools);
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
            dev.langchain4j.service.tool.ToolExecutor executableExecutor = new LangChain4jBindingToolExecutor(
                    objectMapper,
                    skillToolExecutor,
                    ctx.getTenantId(),
                    ctx.getAgentId(),
                    binding,
                    ctx.getSandboxPolicy()
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
        mergeProviderResult(wrapSkillToolProvider(skillBundle.toolProvider()), providerRequest, availableTools, executors);

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

    private void mergeDirectMcpProviders(List<AgentToolBinding> bindings,
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
                    .last("LIMIT 1"));
            if (server == null) {
                continue;
            }
            try {
                McpClient mcpClient = mcpClientFactory.create(server);
                closeables.add(mcpClient);
                McpToolProvider provider = McpToolProvider.builder()
                        .mcpClients(mcpClient)
                        .failIfOneServerFails(false)
                        .filterToolNames(entry.getValue())
                        .toolSpecificationMapper((client, spec) -> spec.toBuilder()
                                .addMetadata(ToolSpecification.METADATA_SEARCH_BEHAVIOR, SearchBehavior.SEARCHABLE)
                                .build())
                        .build();
                mergeProviderResult(provider, providerRequest, availableTools, executors);
            } catch (Exception exception) {
                log.warn("MCP 工具提供器初始化失败: serverId={}, error={}", entry.getKey(), exception.getMessage());
            }
        }
    }

    private void mergeLocalBindings(AgentExecutionContext ctx,
                                    List<AgentToolBinding> bindings,
                                    Map<String, ToolSpecification> availableTools,
                                    Map<String, dev.langchain4j.service.tool.ToolExecutor> executors) {
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
            SearchBehavior searchBehavior = SourceTypeEnum.BUILTIN.getCode().equals(sourceType)
                    ? SearchBehavior.ALWAYS_VISIBLE
                    : SearchBehavior.SEARCHABLE;
            ToolSpecification specification = toolSpecProvider.toToolSpecification(definition, searchBehavior);
            availableTools.putIfAbsent(specification.name(), specification);

            com.schemaplexai.service.agent.tool.executor.ToolExecutor delegate =
                    SourceTypeEnum.BUILTIN.getCode().equals(sourceType) ? builtinToolExecutor : skillToolExecutor;
            executors.putIfAbsent(specification.name(),
                    new LangChain4jBindingToolExecutor(
                            objectMapper,
                            delegate,
                            ctx.getTenantId(),
                            ctx.getAgentId(),
                            binding,
                            ctx.getSandboxPolicy()
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
                                     Map<String, dev.langchain4j.service.tool.ToolExecutor> executors) {
        ToolProviderResult providerResult = toolProvider.provideTools(providerRequest);
        if (providerResult == null || providerResult.tools() == null) {
            return;
        }
        providerResult.tools().forEach((toolSpecification, toolExecutor) -> {
            availableTools.putIfAbsent(toolSpecification.name(), toolSpecification);
            executors.putIfAbsent(toolSpecification.name(), toolExecutor);
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
        private final ToolSearchService toolSearchService;
        private final AgentExecutionContext executionContext;
        private final String conversationId;
        private final String skillPromptBlock;
        private final List<AutoCloseable> closeables;
        private final Map<String, List<ExecutableSkillTool>> lazyLoadedSkillTools;

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
            ToolServiceContext effectiveContext = toolSearchService.adjust(baseContext, chatMemory, invocationContext);
            effectiveContext = mergeActivatedSkillTools(effectiveContext, chatMemory);
            return new RoundToolContext(effectiveContext, invocationContext);
        }

        public String augmentSystemPrompt(String systemPrompt) {
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
                                   InvocationContext invocationContext) {
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
}
