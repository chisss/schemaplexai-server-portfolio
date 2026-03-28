package com.schemaplexai.service.tool.config.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.AgentToolConfigMapper;
import com.schemaplexai.dao.mapper.ToolConfigTemplateMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.AgentToolConfig;
import com.schemaplexai.model.entity.ToolConfigTemplate;
import com.schemaplexai.service.tool.config.ToolConfigService;
import com.schemaplexai.service.tool.security.CredentialEncryptionService;
import com.schemaplexai.service.tool.security.ToolSecurityValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具配置服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolConfigServiceImpl implements ToolConfigService {

    private final ToolConfigTemplateMapper toolConfigTemplateMapper;
    private final AgentToolConfigMapper agentToolConfigMapper;
    private final AgentToolBindingMapper agentToolBindingMapper;
    private final CredentialEncryptionService credentialEncryptionService;
    private final ToolSecurityValidator toolSecurityValidator;

    @Override
    public Map<String, Object> getTemplateSchema(String toolCode) {
        if (!StringUtils.hasText(toolCode)) {
            return Map.of();
        }
        ToolConfigTemplate template = toolConfigTemplateMapper.selectOne(
                new LambdaQueryWrapper<ToolConfigTemplate>()
                        .eq(ToolConfigTemplate::getToolCode, toolCode.trim())
                        .last("LIMIT 1"));
        if (template == null || template.getConfigSchema() == null) {
            return Map.of();
        }
        return new LinkedHashMap<>(template.getConfigSchema());
    }

    @Override
    public Map<String, Object> resolveEffectiveConfig(String tenantId, AgentToolBinding binding) {
        if (binding == null || !StringUtils.hasText(binding.getId())) {
            return Map.of();
        }
        toolSecurityValidator.validateTenantIsolation(tenantId, binding.getTenantId());

        Map<String, Object> schema = getTemplateSchema(binding.getToolCode());
        Map<String, Object> defaults = extractDefaultValues(schema);

        AgentToolConfig configEntity = findByBindingId(binding.getId());
        Map<String, Object> instance = configEntity == null || configEntity.getConfigValue() == null
                ? Map.of()
                : credentialEncryptionService.decryptSensitiveFields(configEntity.getConfigValue(), schema);

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(defaults);
        merged.putAll(instance);
        if (binding.getConfigOverride() != null && !binding.getConfigOverride().isEmpty()) {
            merged.putAll(binding.getConfigOverride());
        }
        toolSecurityValidator.validateRuntimePathConfig(merged);
        return merged;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveBindingConfig(String tenantId, String bindingId, String toolCode, Map<String, Object> configValue) {
        AgentToolBinding binding = requireBinding(tenantId, bindingId);
        if (StringUtils.hasText(toolCode) && !toolCode.trim().equalsIgnoreCase(binding.getToolCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "工具编码与绑定不一致");
        }

        Map<String, Object> schema = getTemplateSchema(binding.getToolCode());
        Map<String, Object> safeConfig = configValue == null ? Map.of() : new LinkedHashMap<>(configValue);
        toolSecurityValidator.validateRuntimePathConfig(safeConfig);
        Map<String, Object> encrypted = credentialEncryptionService.encryptSensitiveFields(safeConfig, schema);

        AgentToolConfig existed = findByBindingId(binding.getId());
        if (existed == null) {
            AgentToolConfig entity = new AgentToolConfig();
            entity.setBindingId(binding.getId());
            entity.setConfigValue(encrypted);
            agentToolConfigMapper.insert(entity);
            log.info("新增工具绑定配置成功: bindingId={}", binding.getId());
            return;
        }
        existed.setConfigValue(encrypted);
        agentToolConfigMapper.updateById(existed);
        log.info("更新工具绑定配置成功: bindingId={}", binding.getId());
    }

    private AgentToolBinding requireBinding(String tenantId, String bindingId) {
        if (!StringUtils.hasText(bindingId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "bindingId 不能为空");
        }
        AgentToolBinding binding = agentToolBindingMapper.selectById(bindingId);
        if (binding == null) {
            throw new BusinessException(ResultCode.CONFIG_NOT_FOUND, "工具绑定不存在");
        }
        toolSecurityValidator.validateTenantIsolation(tenantId, binding.getTenantId());
        return binding;
    }

    private AgentToolConfig findByBindingId(String bindingId) {
        return agentToolConfigMapper.selectOne(
                new LambdaQueryWrapper<AgentToolConfig>()
                        .eq(AgentToolConfig::getBindingId, bindingId)
                        .last("LIMIT 1"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDefaultValues(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return Map.of();
        }
        Object propertiesObj = schema.get("properties");
        if (!(propertiesObj instanceof Map<?, ?> properties)) {
            return Map.of();
        }

        Map<String, Object> defaults = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> property)) {
                continue;
            }
            if (property.containsKey("default")) {
                defaults.put(key, property.get("default"));
            }
        }
        return defaults;
    }
}
