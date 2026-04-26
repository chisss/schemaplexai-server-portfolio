package com.schemaplexai.service.agent.hook;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.AgentHookConfigMapper;
import com.schemaplexai.model.entity.AgentHookConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hook 执行器（参照 Gemini CLI HookRunner）
 *
 * <p>按 order 顺序执行同类型的所有 Hook，支持短路（BEFORE_* 类型返回 false 时停止）。
 * 单个 Hook 执行超时或异常不影响其他 Hook。</p>
 *
 * <p>支持两种模式：
 * <ul>
 *   <li>无 agentId 调用：执行所有内置 Hook（按默认 order）</li>
 *   <li>有 agentId 调用：查询 sf_agent_hook_config 表，仅执行该 Agent 启用的 Hook，
 *       使用 DB 中的 priority 覆盖默认 order；若该 Agent 无任何配置记录则回退到全部内置 Hook</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class AgentHookExecutor {

    private static final long HOOK_TIMEOUT_MS = 500;

    private final List<AgentHook> hooks;
    private final Map<String, AgentHook> hookByName;
    private final AgentHookConfigMapper hookConfigMapper;

    private volatile Map<AgentHookType, List<AgentHook>> hooksByType;

    public AgentHookExecutor(List<AgentHook> hooks, AgentHookConfigMapper hookConfigMapper) {
        this.hooks = hooks;
        this.hookConfigMapper = hookConfigMapper;
        this.hookByName = hooks.stream()
                .collect(Collectors.toMap(AgentHook::name, Function.identity(), (a, b) -> a));
    }

    /**
     * 触发指定类型的所有 Hook（无 Agent 维度过滤，兼容旧调用方式）
     */
    public boolean fire(AgentHookType type, AgentHookContext context) {
        List<AgentHook> typed = getHooksByType(type);
        return doFire(typed, type, context);
    }

    /**
     * 触发指定类型的 Hook（按 Agent 维度过滤，使用 DB 配置的 priority 排序）
     */
    public boolean fire(AgentHookType type, AgentHookContext context, String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return fire(type, context);
        }
        List<AgentHook> typed = resolveHooksForAgent(type, agentId);
        return doFire(typed, type, context);
    }

    private boolean doFire(List<AgentHook> typed, AgentHookType type, AgentHookContext context) {
        if (typed.isEmpty()) return true;

        for (AgentHook hook : typed) {
            try {
                long start = System.currentTimeMillis();
                boolean result = hook.execute(context);
                long elapsed = System.currentTimeMillis() - start;
                if (elapsed > HOOK_TIMEOUT_MS) {
                    log.warn("[Hook] {} 执行耗时 {}ms 超过阈值 {}ms", hook.name(), elapsed, HOOK_TIMEOUT_MS);
                }
                if (!result && type.name().startsWith("BEFORE_")) {
                    log.info("[Hook] {} 阻止了后续执行 (type={})", hook.name(), type);
                    return false;
                }
            } catch (Exception e) {
                log.warn("[Hook] {} 执行异常，跳过: {}", hook.name(), e.getMessage());
            }
        }
        return true;
    }

    private List<AgentHook> resolveHooksForAgent(AgentHookType type, String agentId) {
        try {
            List<AgentHookConfig> configs = hookConfigMapper.selectList(
                    new LambdaQueryWrapper<AgentHookConfig>()
                            .eq(AgentHookConfig::getAgentId, agentId)
                            .eq(AgentHookConfig::getHookType, type.name())
                            .eq(AgentHookConfig::getEnabled, true)
                            .orderByAsc(AgentHookConfig::getPriority));
            if (configs.isEmpty()) {
                return getHooksByType(type);
            }
            return configs.stream()
                    .map(cfg -> hookByName.get(cfg.getHookCode()))
                    .filter(h -> h != null)
                    .toList();
        } catch (Exception e) {
            log.warn("[Hook] 查询 Agent Hook 配置失败，回退到默认: agentId={}, error={}", agentId, e.getMessage());
            return getHooksByType(type);
        }
    }

    private List<AgentHook> getHooksByType(AgentHookType type) {
        if (hooksByType == null) {
            synchronized (this) {
                if (hooksByType == null) {
                    Map<AgentHookType, List<AgentHook>> temp = new ConcurrentHashMap<>();
                    hooks.stream()
                            .collect(Collectors.groupingBy(AgentHook::type))
                            .forEach((key, value) -> {
                                value.sort(Comparator.comparingInt(AgentHook::order));
                                temp.put(key, value);
                            });
                    hooksByType = temp;
                }
            }
        }
        return hooksByType.getOrDefault(type, List.of());
    }
}
