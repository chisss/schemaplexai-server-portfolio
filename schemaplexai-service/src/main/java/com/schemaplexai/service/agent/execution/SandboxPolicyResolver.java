package com.schemaplexai.service.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import com.schemaplexai.common.enums.SandboxProfileEnum;
import com.schemaplexai.common.enums.WorkspaceScope;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.TenantRuntimePolicyMapper;
import com.schemaplexai.dao.mapper.WorkspaceMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.TenantRuntimePolicy;
import com.schemaplexai.model.entity.Workspace;
import com.schemaplexai.service.workspace.WorkspacePathResolver;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 沙箱策略解析器
 */
@Component
@RequiredArgsConstructor
public class SandboxPolicyResolver {

    private static final int DEFAULT_MAX_EXECUTION_MINUTES = 30;
    private static final long DEFAULT_MAX_TOKEN_BUDGET = 200_000L;

    @Value("${schemaplexai.sandbox.allowed-commands:ls,cat,head,tail,grep,find,wc,sort,uniq,diff,echo,pwd,date,whoami}")
    private String defaultAllowedCommandsConfig;

    private final TenantRuntimePolicyMapper tenantRuntimePolicyMapper;
    private final AgentToolBindingMapper agentToolBindingMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspacePathResolver workspacePathResolver;

    private Set<String> defaultAllowedCommands = Set.of();

    @PostConstruct
    void init() {
        defaultAllowedCommands = parseAllowedCommands(defaultAllowedCommandsConfig);
    }

    public SandboxPolicy resolve(Agent agent, AgentExecutionContext context) {
        String tenantId = context.getTenantId();
        TenantRuntimePolicy runtimePolicy = StringUtils.hasText(tenantId)
                ? tenantRuntimePolicyMapper.selectById(tenantId)
                : null;
        SandboxProfileEnum profile = SandboxProfileEnum.fromCode(runtimePolicy != null ? runtimePolicy.getSandboxProfile() : null);
        AgentRuntimeEngineEnum runtimeEngine = AgentRuntimeEngineEnum.fromCode(context.getRuntimeEngine());
        Set<String> allowedToolCodes = resolveAllowedToolCodes(agent, context);
        Set<Path> allowedPathPrefixes = resolveAllowedPathPrefixes(runtimePolicy, tenantId);
        Path defaultWorkingDirectory = resolveDefaultWorkingDirectory(context);
        Set<Path> workspacePathAliases = resolveWorkspacePathAliases(runtimePolicy, context);

        return SandboxPolicy.builder()
                .tenantId(tenantId)
                .agentId(context.getAgentId())
                .executionId(context.getExecutionId())
                .teamMemberId(context.getTeamMemberId())
                .workspaceScope(WorkspaceScope.PROJECT)
                .sandboxProfile(profile)
                .runtimeEngine(runtimeEngine)
                .allowedToolCodes(allowedToolCodes)
                .allowedCommands(resolveAllowedCommands(runtimePolicy))
                .allowedPathPrefixes(allowedPathPrefixes)
                .defaultWorkingDirectory(defaultWorkingDirectory)
                .workspacePathAliases(workspacePathAliases)
                .maxExecutionMinutes(runtimePolicy != null && runtimePolicy.getMaxExecutionMinutes() != null
                        ? runtimePolicy.getMaxExecutionMinutes()
                        : DEFAULT_MAX_EXECUTION_MINUTES)
                .maxTokenBudget(DEFAULT_MAX_TOKEN_BUDGET)
                .networkEgressEnabled(profile != SandboxProfileEnum.STRICT)
                .build();
    }

    public Map<String, Object> toSnapshot(SandboxPolicy policy) {
        if (policy == null) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("tenantId", policy.getTenantId());
        snapshot.put("agentId", policy.getAgentId());
        snapshot.put("executionId", policy.getExecutionId());
        snapshot.put("teamMemberId", policy.getTeamMemberId());
        snapshot.put("workspaceScope", policy.getWorkspaceScope() != null ? policy.getWorkspaceScope().getCode() : null);
        snapshot.put("sandboxProfile", policy.getSandboxProfile() != null ? policy.getSandboxProfile().getCode() : null);
        snapshot.put("runtimeEngine", policy.getRuntimeEngine() != null ? policy.getRuntimeEngine().getCode() : null);
        snapshot.put("allowedToolCodes", policy.getAllowedToolCodes());
        snapshot.put("allowedCommands", policy.getAllowedCommands());
        snapshot.put("allowedPathPrefixes", policy.getAllowedPathPrefixes() != null
                ? policy.getAllowedPathPrefixes().stream().map(Path::toString).toList()
                : List.of());
        snapshot.put("defaultWorkingDirectory",
                policy.getDefaultWorkingDirectory() != null ? policy.getDefaultWorkingDirectory().toString() : null);
        snapshot.put("workspacePathAliases", policy.getWorkspacePathAliases() != null
                ? policy.getWorkspacePathAliases().stream().map(Path::toString).toList()
                : List.of());
        snapshot.put("maxExecutionMinutes", policy.getMaxExecutionMinutes());
        snapshot.put("maxTokenBudget", policy.getMaxTokenBudget());
        snapshot.put("networkEgressEnabled", policy.isNetworkEgressEnabled());
        return snapshot;
    }

    private Set<String> resolveAllowedToolCodes(Agent agent, AgentExecutionContext context) {
        Set<String> result = new LinkedHashSet<>();
        List<AgentToolBinding> overrideBindings = context.getOverrideToolBindings();
        if (!CollectionUtils.isEmpty(overrideBindings)) {
            overrideBindings.stream()
                    .filter(binding -> Boolean.TRUE.equals(binding.getEnabled()))
                    .map(AgentToolBinding::getToolCode)
                    .filter(StringUtils::hasText)
                    .forEach(result::add);
            return result;
        }
        if (agent == null || !StringUtils.hasText(agent.getId())) {
            return result;
        }
        agentToolBindingMapper.selectList(
                        new LambdaQueryWrapper<AgentToolBinding>()
                                .eq(AgentToolBinding::getAgentId, agent.getId())
                                .eq(AgentToolBinding::getEnabled, true))
                .stream()
                .map(AgentToolBinding::getToolCode)
                .filter(StringUtils::hasText)
                .forEach(result::add);
        return result;
    }

    private Set<Path> resolveAllowedPathPrefixes(TenantRuntimePolicy runtimePolicy, String tenantId) {
        Set<Path> allowedPrefixes = new LinkedHashSet<>();
        allowedPrefixes.add(workspacePathResolver.getWorkspaceRoot());
        String configuredRootPath = runtimePolicy != null ? runtimePolicy.getWorkspaceRootPath() : null;
        if (StringUtils.hasText(configuredRootPath)) {
            allowedPrefixes.add(resolveConfiguredWorkspaceRoot(configuredRootPath, tenantId));
        }
        return allowedPrefixes;
    }

    private Path resolveDefaultWorkingDirectory(AgentExecutionContext context) {
        String workspacePath = readContextText(context, "workspacePath");
        if (!StringUtils.hasText(workspacePath)) {
            return null;
        }
        try {
            return workspacePathResolver.validateWithinWorkspaceRoot(workspacePath);
        } catch (BusinessException exception) {
            return null;
        }
    }

    private Set<Path> resolveWorkspacePathAliases(TenantRuntimePolicy runtimePolicy, AgentExecutionContext context) {
        Set<Path> aliases = new LinkedHashSet<>();
        addAliasIfPresent(aliases, runtimePolicy != null ? runtimePolicy.getWorkspaceRootPath() : null);
        addAliasIfPresent(aliases, readContextText(context, "workspaceLocalPath"));
        return aliases;
    }

    private Path resolveConfiguredWorkspaceRoot(String configuredRootPath, String tenantId) {
        try {
            return workspacePathResolver.validateWithinWorkspaceRoot(configuredRootPath);
        } catch (BusinessException exception) {
            Path normalizedConfiguredPath = normalizePath(configuredRootPath);
            boolean matchedTenantWorkspace = StringUtils.hasText(tenantId)
                    && workspaceMapper.selectList(new LambdaQueryWrapper<Workspace>()
                    .eq(Workspace::getTenantId, tenantId))
                    .stream()
                    .map(Workspace::getLocalPath)
                    .filter(StringUtils::hasText)
                    .map(this::normalizePath)
                    .anyMatch(normalizedConfiguredPath::equals);
            if (matchedTenantWorkspace) {
                return normalizedConfiguredPath;
            }
            throw exception;
        }
    }

    private Path normalizePath(String pathValue) {
        Path normalized = Path.of(pathValue).toAbsolutePath().normalize();
        try {
            return java.nio.file.Files.exists(normalized) ? normalized.toRealPath() : normalized;
        } catch (java.io.IOException exception) {
            return normalized;
        }
    }

    private void addAliasIfPresent(Set<Path> aliases, String pathValue) {
        if (!StringUtils.hasText(pathValue)) {
            return;
        }
        aliases.add(normalizePath(pathValue));
    }

    private String readContextText(AgentExecutionContext context, String key) {
        if (context == null || context.getInputContext() == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = context.getInputContext().get(key);
        return value != null ? String.valueOf(value).trim() : null;
    }

    private Set<String> resolveAllowedCommands(TenantRuntimePolicy runtimePolicy) {
        if (runtimePolicy == null) {
            return defaultAllowedCommands;
        }
        List<String> configuredCommands = runtimePolicy.getSandboxAllowedCommands();
        if (configuredCommands == null) {
            return defaultAllowedCommands;
        }
        return configuredCommands.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        Set::copyOf
                ));
    }

    private Set<String> parseAllowedCommands(String configValue) {
        if (!StringUtils.hasText(configValue)) {
            return Set.of();
        }
        return List.of(configValue.split(",")).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        Set::copyOf
                ));
    }
}
