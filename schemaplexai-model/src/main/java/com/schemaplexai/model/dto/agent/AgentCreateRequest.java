package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 创建Agent请求DTO
 */
@Data
public class AgentCreateRequest {

    /** Agent名称 */
    @NotBlank(message = "Agent名称不能为空")
    private String name;

    /** Agent类型: solo/team */
    @NotBlank(message = "Agent类型不能为空")
    private String agentType;

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

    /** 工作类型/团队模板code */
    private String workType;

    /** Agent能力标签，逗号分隔，对应字典 agent_tag */
    private String agentTag;

    /** 模型绑定类型: model/model_group，默认 model */
    private String aiModelType;

    /** 当 aiModelType=model_group 时，指定模型组 ID */
    private String aiModelGroupId;
}
