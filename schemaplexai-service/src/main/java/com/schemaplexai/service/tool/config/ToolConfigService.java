package com.schemaplexai.service.tool.config;

import com.schemaplexai.model.entity.AgentToolBinding;

import java.util.Map;

/**
 * 工具配置服务
 */
public interface ToolConfigService {

    /**
     * 获取工具配置模板 Schema
     *
     * @param toolCode 工具编码
     * @return schema map，不存在返回空 map
     */
    Map<String, Object> getTemplateSchema(String toolCode);

    /**
     * 解析工具生效配置（模板默认值 + 绑定实例配置 + 绑定覆盖配置）
     *
     * @param tenantId 当前租户
     * @param binding  工具绑定
     * @return 生效配置
     */
    Map<String, Object> resolveEffectiveConfig(String tenantId, AgentToolBinding binding);

    /**
     * 保存绑定配置（敏感字段加密）
     *
     * @param tenantId    当前租户
     * @param bindingId   绑定 ID
     * @param toolCode    工具编码
     * @param configValue 待保存配置
     */
    void saveBindingConfig(String tenantId, String bindingId, String toolCode, Map<String, Object> configValue);
}
