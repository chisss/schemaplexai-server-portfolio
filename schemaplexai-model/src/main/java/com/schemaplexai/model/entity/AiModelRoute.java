package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI模型路由规则表实体 — 支持3层降级路由
 */
@Data
@TableName(value = "sf_ai_model_route", autoResultMap = true)
public class AiModelRoute implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    private String tenantId;

    /** 路由规则名称 */
    private String name;

    /** 任务类型（保留兼容） */
    private String taskType;

    /** 优先级（值越小优先级越高） */
    private Integer priority;

    /** 匹配维度: task_type/model_capability/cost_budget */
    private String matchDimension;

    /** 匹配条件JSON */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> matchCondition;

    /** 主模型ID */
    private String primaryModelId;

    /** 备用模型ID（第二层降级） */
    private String secondaryModelId;

    /** 兜底模型ID（第三层降级） */
    private String tertiaryModelId;

    /** 旧字段 - 兼容保留 */
    private String fallbackModelId;

    /** 主模型降级触发条件: {timeout_ms, error_rate, qps_limit} */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> primaryTriggerCondition;

    /** 备用模型降级触发条件 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> secondaryTriggerCondition;

    /** 描述 */
    private String description;

    /** 状态: active/inactive */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
