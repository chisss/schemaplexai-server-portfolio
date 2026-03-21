package com.schemaplexai.model.vo.agent;

/**
 * Agent配置VO
 */
public record AgentConfigVO(
        /** 配置ID */
        String id,
        /** 配置键 */
        String configKey,
        /** 配置值 */
        String configValue,
        /** 说明 */
        String description
) {}
