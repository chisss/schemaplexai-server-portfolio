package com.schemaplexai.service.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.AgentConfigMapper;
import com.schemaplexai.model.entity.AgentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Agent 执行引擎参数加载器
 *
 * <p>从 sf_agent_config 表读取影响 Agentic Loop 行为的关键参数，
 * 支持按 Agent 维度独立配置，未配置时回退到系统默认值。</p>
 *
 * <h3>支持的配置键（configKey）</h3>
 * <ul>
 *   <li>{@code engine.maxRounds} — 最大推理轮次，默认 50</li>
 *   <li>{@code engine.maxToolCallsPerRound} — 每轮最大工具调用数，默认 8</li>
 *   <li>{@code engine.maxMessages} — 短期记忆最大消息数，默认 100</li>
 *   <li>{@code engine.maxModelRetries} — 模型调用最大重试次数，默认 2</li>
 *   <li>{@code engine.maxQualityReflections} — 最大质量反思轮次，默认 1</li>
 *   <li>{@code engine.maxUnexpectedToolCallRecoveries} — 非预期工具调用最大恢复次数，默认 1</li>
 *   <li>{@code engine.maxToolResultMessageLength} — 工具结果最大长度，默认 12000</li>
 *   <li>{@code engine.modelCallTimeoutSeconds} — 模型调用超时秒数，默认 60</li>
 *   <li>{@code engine.fallbackEvidenceLimit} — 降级收敛证据条数上限，默认 10</li>
 *   <li>{@code engine.fallbackTextLimit} — 降级收敛单条证据文本长度，默认 320</li>
 *   <li>{@code engine.logContentLimit} — 日志内容截断长度，默认 500</li>
 *   <li>{@code engine.toolRequestSummaryLimit} — 工具请求摘要截断长度，默认 240</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentEngineConfigLoader {

    // ---- 配置键常量 ----
    public static final String KEY_MAX_ROUNDS                         = "engine.maxRounds";
    public static final String KEY_MAX_TOOL_CALLS_PER_ROUND           = "engine.maxToolCallsPerRound";
    public static final String KEY_MAX_MESSAGES                       = "engine.maxMessages";
    public static final String KEY_MAX_MODEL_RETRIES                  = "engine.maxModelRetries";
    public static final String KEY_MAX_QUALITY_REFLECTIONS            = "engine.maxQualityReflections";
    public static final String KEY_SHADOW_QUALITY_REVIEW_ENABLED      = "engine.shadowQualityReviewEnabled";
    public static final String KEY_SHADOW_QUALITY_REVIEW_AWAIT_MILLIS = "engine.shadowQualityReviewAwaitMillis";
    public static final String KEY_MAX_UNEXPECTED_TOOL_CALL_RECOVERIES = "engine.maxUnexpectedToolCallRecoveries";
    public static final String KEY_MAX_TOOL_RESULT_MESSAGE_LENGTH     = "engine.maxToolResultMessageLength";
    public static final String KEY_MODEL_CALL_TIMEOUT_SECONDS         = "engine.modelCallTimeoutSeconds";
    public static final String KEY_FALLBACK_EVIDENCE_LIMIT            = "engine.fallbackEvidenceLimit";
    public static final String KEY_FALLBACK_TEXT_LIMIT                = "engine.fallbackTextLimit";
    public static final String KEY_LOG_CONTENT_LIMIT                  = "engine.logContentLimit";
    public static final String KEY_TOOL_REQUEST_SUMMARY_LIMIT         = "engine.toolRequestSummaryLimit";

    // ---- 系统默认值 ----
    private static final int  DEFAULT_MAX_ROUNDS                          = 50;
    private static final int  DEFAULT_MAX_TOOL_CALLS_PER_ROUND           = 8;
    private static final int  DEFAULT_MAX_MESSAGES                       = 100;
    private static final int  DEFAULT_MAX_MODEL_RETRIES                  = 2;
    private static final int  DEFAULT_MAX_QUALITY_REFLECTIONS            = 1;
    private static final boolean DEFAULT_SHADOW_QUALITY_REVIEW_ENABLED   = true;
    private static final long DEFAULT_SHADOW_QUALITY_REVIEW_AWAIT_MILLIS = 350L;
    private static final int  DEFAULT_MAX_UNEXPECTED_TOOL_CALL_RECOVERIES = 1;
    private static final int  DEFAULT_MAX_TOOL_RESULT_MESSAGE_LENGTH     = 12000;
    private static final long DEFAULT_MODEL_CALL_TIMEOUT_MILLIS          = TimeUnit.SECONDS.toMillis(60);
    private static final int  DEFAULT_FALLBACK_EVIDENCE_LIMIT            = 10;
    private static final int  DEFAULT_FALLBACK_TEXT_LIMIT                = 320;
    private static final int  DEFAULT_LOG_CONTENT_LIMIT                  = 500;
    private static final int  DEFAULT_TOOL_REQUEST_SUMMARY_LIMIT         = 240;

    private final AgentConfigMapper agentConfigMapper;

    /**
     * 加载指定 Agent 的引擎参数，构建 {@link AgentEngineParams}。
     *
     * @param agentId Agent ID
     * @param ctx     执行上下文（ctx 中的值优先于数据库配置）
     */
    public AgentEngineParams load(String agentId, AgentExecutionContext ctx) {
        Map<String, String> configMap = loadConfigMap(agentId);

        int maxRounds = ctx.getMaxRounds() > 0
                ? ctx.getMaxRounds()
                : getInt(configMap, KEY_MAX_ROUNDS, DEFAULT_MAX_ROUNDS);

        int maxToolCallsPerRound = ctx.getMaxToolCallsPerRound() > 0
                ? ctx.getMaxToolCallsPerRound()
                : getInt(configMap, KEY_MAX_TOOL_CALLS_PER_ROUND, DEFAULT_MAX_TOOL_CALLS_PER_ROUND);

        int maxMessages = ctx.getMaxMessages() > 0
                ? ctx.getMaxMessages()
                : getInt(configMap, KEY_MAX_MESSAGES, DEFAULT_MAX_MESSAGES);

        return AgentEngineParams.builder()
                .maxRounds(maxRounds)
                .maxToolCallsPerRound(maxToolCallsPerRound)
                .maxMessages(maxMessages)
                .maxModelRetries(getInt(configMap, KEY_MAX_MODEL_RETRIES, DEFAULT_MAX_MODEL_RETRIES))
                .maxQualityReflections(getInt(configMap, KEY_MAX_QUALITY_REFLECTIONS, DEFAULT_MAX_QUALITY_REFLECTIONS))
                .shadowQualityReviewEnabled(getBoolean(configMap, KEY_SHADOW_QUALITY_REVIEW_ENABLED, DEFAULT_SHADOW_QUALITY_REVIEW_ENABLED))
                .shadowQualityReviewAwaitMillis(getLong(configMap, KEY_SHADOW_QUALITY_REVIEW_AWAIT_MILLIS, DEFAULT_SHADOW_QUALITY_REVIEW_AWAIT_MILLIS))
                .maxUnexpectedToolCallRecoveries(getInt(configMap, KEY_MAX_UNEXPECTED_TOOL_CALL_RECOVERIES, DEFAULT_MAX_UNEXPECTED_TOOL_CALL_RECOVERIES))
                .maxToolResultMessageLength(getInt(configMap, KEY_MAX_TOOL_RESULT_MESSAGE_LENGTH, DEFAULT_MAX_TOOL_RESULT_MESSAGE_LENGTH))
                .modelCallTimeoutMillis(getTimeoutMillis(configMap))
                .fallbackEvidenceLimit(getInt(configMap, KEY_FALLBACK_EVIDENCE_LIMIT, DEFAULT_FALLBACK_EVIDENCE_LIMIT))
                .fallbackTextLimit(getInt(configMap, KEY_FALLBACK_TEXT_LIMIT, DEFAULT_FALLBACK_TEXT_LIMIT))
                .logContentLimit(getInt(configMap, KEY_LOG_CONTENT_LIMIT, DEFAULT_LOG_CONTENT_LIMIT))
                .toolRequestSummaryLimit(getInt(configMap, KEY_TOOL_REQUEST_SUMMARY_LIMIT, DEFAULT_TOOL_REQUEST_SUMMARY_LIMIT))
                .build();
    }

    // ---- 私有辅助方法 ----

    private Map<String, String> loadConfigMap(String agentId) {
        try {
            List<AgentConfig> configs = agentConfigMapper.selectList(
                    new LambdaQueryWrapper<AgentConfig>()
                            .eq(AgentConfig::getAgentId, agentId)
                            .likeRight(AgentConfig::getConfigKey, "engine.")
            );
            return configs.stream()
                    .collect(Collectors.toMap(AgentConfig::getConfigKey, AgentConfig::getConfigValue, (a, b) -> a));
        } catch (Exception e) {
            log.warn("加载 Agent 引擎配置失败，使用默认值: agentId={}, error={}", agentId, e.getMessage());
            return Map.of();
        }
    }

    private int getInt(Map<String, String> map, String key, int defaultValue) {
        String val = map.get(key);
        if (val == null) return defaultValue;
        try {
            int parsed = Integer.parseInt(val.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("Agent 引擎配置值非法: key={}, value={}", key, val);
            return defaultValue;
        }
    }

    private long getLong(Map<String, String> map, String key, long defaultValue) {
        String val = map.get(key);
        if (val == null) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(val.trim());
            return parsed >= 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            log.warn("Agent 引擎配置值非法: key={}, value={}", key, val);
            return defaultValue;
        }
    }

    private boolean getBoolean(Map<String, String> map, String key, boolean defaultValue) {
        String val = map.get(key);
        if (val == null) {
            return defaultValue;
        }
        String normalized = val.trim().toLowerCase();
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return false;
        }
        log.warn("Agent 引擎布尔配置值非法: key={}, value={}", key, val);
        return defaultValue;
    }

    private long getTimeoutMillis(Map<String, String> map) {
        String val = map.get(KEY_MODEL_CALL_TIMEOUT_SECONDS);
        if (val == null) return DEFAULT_MODEL_CALL_TIMEOUT_MILLIS;
        try {
            long seconds = Long.parseLong(val.trim());
            return seconds > 0 ? TimeUnit.SECONDS.toMillis(seconds) : DEFAULT_MODEL_CALL_TIMEOUT_MILLIS;
        } catch (NumberFormatException e) {
            return DEFAULT_MODEL_CALL_TIMEOUT_MILLIS;
        }
    }
}
