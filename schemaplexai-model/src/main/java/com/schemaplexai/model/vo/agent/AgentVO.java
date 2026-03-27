package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent信息VO
 */
@Data
public class AgentVO {

    /** Agent ID */
    private String id;

    /** Agent名称 */
    private String name;

    /** Agent类型: solo/team */
    private String agentType;

    /** 描述 */
    private String description;

    /** 默认AI模型 */
    private String aiModel;

    /** 模型绑定类型: model/model_group */
    private String aiModelType;

    /** 当 aiModelType=model_group 时，绑定的模型组 ID */
    private String aiModelGroupId;

    /** 状态: active/inactive/running/error */
    private String status;

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

    /** 配置是否完成（综合状态） */
    private Boolean configCompleted;

    /** 团队配置是否完成（仅 team 类型有意义） */
    private Boolean teamConfigDone;

    /** 上下文绑定是否完成 */
    private Boolean contextBindingDone;

    /** Agent能力标签，逗号分隔，对应字典 agent_tag */
    private String agentTag;

    /** 是否内置Agent */
    private boolean builtin;

    /** 内置位置列表 */
    private List<String> builtinPositions;

    /** 配置列表 */
    private List<AgentConfigVO> configs;

    /** 团队成员列表 */
    private List<AgentTeamMemberVO> teamMembers;

    /** 上下文绑定列表 */
    private List<AgentContextBindingVO> contextBindings;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
