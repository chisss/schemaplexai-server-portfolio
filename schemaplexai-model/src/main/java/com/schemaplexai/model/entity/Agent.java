package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * Agent主表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_agent", autoResultMap = true)
public class Agent extends BaseEntity {

    /** Agent名称 */
    private String name;

    /** Agent类型: team/solo */
    private String agentType;

    /** 描述 */
    private String description;

    /** 默认AI模型 */
    private String aiModel;

    /** 状态: active/inactive/running/error */
    private String status;

    /** 最大并发数 */
    private Integer maxConcurrency;

    /** 触发类型: manual/scheduled/event */
    private String triggerType;

    /** 触发配置（定时cron/事件类型等） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> triggerConfig;

    /** 技能标签数组 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> skills;

    /** 工作类型/团队模板code */
    private String workType;

    /** 配置是否完成 */
    private Boolean configCompleted;

    /** Agent能力标签，逗号分隔，对应字典 agent_tag */
    private String agentTag;
}
