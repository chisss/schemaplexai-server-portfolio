package com.schemaplexai.service.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import com.schemaplexai.common.enums.SandboxProfileEnum;
import com.schemaplexai.common.enums.WorkspaceScope;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.TenantRuntimePolicyMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.TenantRuntimePolicy;
import com.schemaplexai.service.workspace.WorkspacePathResolver;
import lombok.RequiredArgsConstructor;
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

    private final TenantRuntimePolicyMapper tenantRuntimePolicyMapper;
    private final AgentToolBindingMapper agentToolBindingMapper;
    private final WorkspacePathResolver workspacePathResolver;

    public SandboxPolicy resolve(Agent agent, AgentExecutionContext context) {
        String tenantId = context.getTenantId();
        TenantRuntimePolicy runtimePolicy = StringUtils.hasText(tenantId)
                ? tenantRuntimePolicyMapper.selectById(tenantId)
                : null;
        SandboxProfileEnum profile = SandboxProfileEnum.fromCode(runtimePolicy != null ? runtimePolicy.getSandboxProfile() : null);
        AgentRuntimeEngineEnum runtimeEngine = AgentRuntimeEngineEnum.fromCode(context.getRuntimeEngine());
        Set<String> allowedToolCodes = resolveAllowedToolCodes(agent, context);
        Set<Path> allowedPathPrefixes = resolveAllowedPathPrefixes(runtimePolicy);

        return SandboxPolicy.builder()
                .tenantId(tenantId)
                .agentId(context.getAgentId())
                .executionId(context.getExecutionId())
                .teamMemberId(context.getTeamMemberId())
                .workspaceScope(WorkspaceScope.PROJECT)
                .sandboxProfile(profile)
                .runtimeEngine(runtimeEngine)
                .allowedToolCodes(allowedToolCodes)
                .allowedPathPrefixes(allowedPathPrefixes)
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
        snapshot.put("allowedPathPrefixes", policy.getAllowedPathPrefixes() != null
                ? policy.getAllowedPathPrefixes().stream().map(Path::toString).toList()
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

    private Set<Path> resolveAllowedPathPrefixes(TenantRuntimePolicy runtimePolicy) {
        Path root = StringUtils.hasText(runtimePolicy != null ? runtimePolicy.getWorkspaceRootPath() : null)
                ? workspacePathResolver.validateWithinWorkspaceRoot(runtimePolicy.getWorkspaceRootPath())
                : workspacePathResolver.getWorkspaceRoot();
        Path currentProjectRoot = Path.of("").toAbsolutePath().normalize();
        Set<Path> allowedPrefixes = new LinkedHashSet<>();
        allowedPrefixes.add(root);
        allowedPrefixes.add(currentProjectRoot);
        return allowedPrefixes;
    }
}
