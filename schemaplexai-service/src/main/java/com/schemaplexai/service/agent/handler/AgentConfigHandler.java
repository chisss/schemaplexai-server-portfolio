package com.schemaplexai.service.agent.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentConfigMapper;
import com.schemaplexai.model.dto.agent.AgentConfigRequest;
import com.schemaplexai.model.entity.AgentConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent配置处理器 — 配置CRUD + Redis缓存管理
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentConfigHandler {

    private static final String CACHE_PREFIX = "sf:agent:config:";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final AgentConfigMapper agentConfigMapper;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 加载Agent的所有配置
     */
    public List<AgentConfig> loadConfigs(String agentId) {
        return agentConfigMapper.selectList(
                new LambdaQueryWrapper<AgentConfig>().eq(AgentConfig::getAgentId, agentId)
        );
    }

    /**
     * 保存或更新单个配置项
     */
    public AgentConfig saveConfig(String agentId, AgentConfigRequest request) {
        // 查找已有配置
        var existing = agentConfigMapper.selectOne(
                new LambdaQueryWrapper<AgentConfig>()
                        .eq(AgentConfig::getAgentId, agentId)
                        .eq(AgentConfig::getConfigKey, request.getConfigKey())
        );

        if (existing != null) {
            existing.setConfigValue(request.getConfigValue());
            existing.setDescription(request.getDescription());
            existing.setUpdatedAt(LocalDateTime.now());
            agentConfigMapper.updateById(existing);
            log.info("更新Agent配置: agentId={}, key={}", agentId, request.getConfigKey());
            evictCache(agentId);
            return existing;
        }

        var config = new AgentConfig();
        config.setAgentId(agentId);
        config.setConfigKey(request.getConfigKey());
        config.setConfigValue(request.getConfigValue());
        config.setDescription(request.getDescription());
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        agentConfigMapper.insert(config);
        log.info("新增Agent配置: agentId={}, key={}", agentId, request.getConfigKey());
        evictCache(agentId);
        return config;
    }

    /**
     * 删除配置项（校验归属关系）
     */
    public void deleteConfig(String agentId, String configId) {
        var config = agentConfigMapper.selectById(configId);
        if (config == null || !agentId.equals(config.getAgentId())) {
            throw new BusinessException(ResultCode.AGENT_CONFIG_ERROR);
        }
        agentConfigMapper.deleteById(configId);
        evictCache(agentId);
        log.info("删除Agent配置: agentId={}, configId={}", agentId, configId);
    }

    /**
     * 删除Agent的所有配置（Agent删除时调用）
     */
    public void deleteAllConfigs(String agentId) {
        agentConfigMapper.delete(
                new LambdaQueryWrapper<AgentConfig>().eq(AgentConfig::getAgentId, agentId)
        );
        evictCache(agentId);
        log.info("清理Agent全部配置: agentId={}", agentId);
    }

    /**
     * 清除Agent配置缓存
     */
    public void evictCache(String agentId) {
        stringRedisTemplate.delete(CACHE_PREFIX + agentId);
    }

    /**
     * 判断Agent是否为内置Agent
     */
    public boolean isBuiltin(String agentId) {
        var builtinConfig = agentConfigMapper.selectOne(
                new LambdaQueryWrapper<AgentConfig>()
                        .eq(AgentConfig::getAgentId, agentId)
                        .eq(AgentConfig::getConfigKey, "is_builtin")
                        .last("LIMIT 1")
        );
        return builtinConfig != null && "true".equalsIgnoreCase(builtinConfig.getConfigValue());
    }
}
