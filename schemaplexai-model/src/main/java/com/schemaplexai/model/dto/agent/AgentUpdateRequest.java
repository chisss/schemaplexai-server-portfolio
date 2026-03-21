package com.schemaplexai.model.dto.agent;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 更新Agent请求DTO
 */
@Data
public class AgentUpdateRequest {

    /** Agent名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 默认AI模型 */
    private String aiModel;

    /** 最大并发数 */
    private Integer maxConcurrency;

    /** 触发类型: manual/scheduled/event */
    private String triggerType;

    /** 触发配置 */
    private Map<String, Object> triggerConfig;

    /** 技能标签 */
    private List<String> skills;

    /** Agent能力标签，逗号分隔，对应字典 agent_tag */
    private String agentTag;
}
