package com.schemaplexai.service.context;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 上下文 Redis 缓存服务
 *
 * <p>Key 规范：
 * <pre>
 * sf:ctx:agent:{agentId}:prompt        → Agent系统提示词全文       TTL 5min
 * sf:ctx:team:{teamAgentId}:shared      → 团队共享上下文 Hash       TTL 10min
 * sf:ctx:session:{execId}:history       → 对话历史 JSON            TTL 2h
 * sf:ctx:item:{itemId}                  → 单条上下文条目内容         TTL 30min
 * </pre>
 *
 * <p>所有方法均 try-catch，Redis 不可用时静默忽略，不影响主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextCacheService {

    private static final String PREFIX_AGENT_PROMPT = "sf:ctx:agent:%s:prompt";
    private static final String PREFIX_AGENT_PROMPT_VARIANT = "sf:ctx:agent:%s:prompt:%s";
    private static final String PREFIX_TEAM_SHARED = "sf:ctx:team:%s:shared";
    private static final String PREFIX_SESSION_HISTORY = "sf:ctx:session:%s:history";
    private static final String PREFIX_CTX_ITEM = "sf:ctx:item:%s";

    private static final Duration TTL_PROMPT = Duration.ofMinutes(5);
    private static final Duration TTL_TEAM_SHARED = Duration.ofMinutes(10);
    private static final Duration TTL_SESSION = Duration.ofHours(2);
    private static final Duration TTL_ITEM = Duration.ofMinutes(30);

    private final RedisTemplate<String, Object> redisTemplate;

    // =====================================================================
    //  Agent 系统提示词缓存
    // =====================================================================

    /**
     * 读取 Agent 系统提示词缓存
     *
     * @return 缓存的 prompt，不存在或 Redis 异常时返回 null
     */
    public String getAgentPrompt(String agentId) {
        return getAgentPrompt(agentId, null);
    }

    /**
     * 读取 Agent 系统提示词缓存（支持预算/模型变体）
     */
    public String getAgentPrompt(String agentId, String variant) {
        try {
            Object val = redisTemplate.opsForValue().get(promptKey(agentId, variant));
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            log.debug("读取 Agent prompt 缓存失败（降级到 DB）: agentId={}", agentId);
            return null;
        }
    }

    /**
     * 写入 Agent 系统提示词缓存
     */
    public void cacheAgentPrompt(String agentId, String prompt) {
        cacheAgentPrompt(agentId, null, prompt);
    }

    /**
     * 写入 Agent 系统提示词缓存（支持预算/模型变体）
     */
    public void cacheAgentPrompt(String agentId, String variant, String prompt) {
        if (!StringUtils.hasText(prompt)) return;
        try {
            redisTemplate.opsForValue().set(promptKey(agentId, variant), prompt, TTL_PROMPT);
        } catch (Exception e) {
            log.debug("写入 Agent prompt 缓存失败: agentId={}", agentId);
        }
    }

    /**
     * 失效 Agent 系统提示词缓存（上下文条目更新时调用）
     */
    public void evictAgentPrompt(String agentId) {
        try {
            redisTemplate.delete(promptKey(agentId));
        } catch (Exception e) {
            log.debug("删除 Agent prompt 缓存失败: agentId={}", agentId);
        }
    }

    // =====================================================================
    //  团队 Agent 共享上下文（Hash 结构）
    // =====================================================================

    /**
     * 更新/写入团队成员的产出摘要到共享 Hash
     *
     * @param teamAgentId Team Agent ID
     * @param subAgentId  子 Agent ID（作为 Hash key）
     * @param summary     产出摘要（前2000字符）
     */
    public void updateTeamSharedContext(String teamAgentId, String subAgentId, String summary) {
        if (!StringUtils.hasText(summary)) return;
        try {
            String key = teamKey(teamAgentId);
            redisTemplate.opsForHash().put(key, subAgentId, summary);
            redisTemplate.expire(key, TTL_TEAM_SHARED);
            log.debug("更新团队共享上下文: teamAgentId={}, subAgentId={}", teamAgentId, subAgentId);
        } catch (Exception e) {
            log.debug("写入团队共享上下文失败: teamAgentId={}", teamAgentId);
        }
    }

    /**
     * 获取整个团队的共享上下文（subAgentId → summary 映射）
     *
     * @return 共享上下文映射，Redis 异常时返回空 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> getTeamSharedContext(String teamAgentId) {
        try {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(teamKey(teamAgentId));
            Map<String, String> result = new HashMap<>();
            raw.forEach((k, v) -> result.put(k.toString(), v.toString()));
            return result;
        } catch (Exception e) {
            log.debug("读取团队共享上下文失败: teamAgentId={}", teamAgentId);
            return new HashMap<>();
        }
    }

    /**
     * 清除团队共享上下文（工作流实例完成时调用）
     */
    public void evictTeamSharedContext(String teamAgentId) {
        try {
            redisTemplate.delete(teamKey(teamAgentId));
        } catch (Exception e) {
            log.debug("删除团队共享上下文失败: teamAgentId={}", teamAgentId);
        }
    }

    // =====================================================================
    //  会话历史（对话记录）
    // =====================================================================

    /**
     * 追加对话历史条目（List 结构，右追加）
     */
    public void appendSessionHistory(String executionId, String messageJson) {
        try {
            String key = sessionKey(executionId);
            redisTemplate.opsForList().rightPush(key, messageJson);
            redisTemplate.expire(key, TTL_SESSION);
        } catch (Exception e) {
            log.debug("写入对话历史失败: executionId={}", executionId);
        }
    }

    /**
     * 读取全部对话历史
     *
     * @return JSON 字符串列表，Redis 异常时返回空列表
     */
    public List<String> getSessionHistory(String executionId) {
        try {
            var raw = redisTemplate.opsForList().range(sessionKey(executionId), 0, -1);
            if (raw == null) return List.of();
            return raw.stream().map(Object::toString).toList();
        } catch (Exception e) {
            log.debug("读取对话历史失败: executionId={}", executionId);
            return List.of();
        }
    }

    /**
     * 清除会话历史（执行完成后调用）
     */
    public void evictSessionHistory(String executionId) {
        try {
            redisTemplate.delete(sessionKey(executionId));
        } catch (Exception e) {
            log.debug("删除对话历史失败: executionId={}", executionId);
        }
    }

    // =====================================================================
    //  私有方法
    // =====================================================================

    private String promptKey(String agentId) {
        return String.format(PREFIX_AGENT_PROMPT, agentId);
    }

    private String promptKey(String agentId, String variant) {
        if (!StringUtils.hasText(variant)) {
            return promptKey(agentId);
        }
        return String.format(PREFIX_AGENT_PROMPT_VARIANT, agentId, variant.replaceAll("[^a-zA-Z0-9._-]", "_"));
    }

    private String teamKey(String teamAgentId) {
        return String.format(PREFIX_TEAM_SHARED, teamAgentId);
    }

    private String sessionKey(String executionId) {
        return String.format(PREFIX_SESSION_HISTORY, executionId);
    }
}
