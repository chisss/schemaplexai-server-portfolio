package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工作流模板表实体
 */
@Data
@TableName(value = "sf_workflow_template", autoResultMap = true)
public class WorkflowTemplate implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID，NULL表示系统级模板 */
    @TableField(fill = FieldFill.INSERT)
    private String tenantId;

    /** 模板名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 分类: feature-development/bug-fix/refactoring/data-analysis/config-change */
    private String category;

    /** 节点和边定义(ReactFlow格式) */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> definition;

    /** 推荐Agent技能标签 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> recommendedAgentSkills;

    /** 是否内置 */
    private Boolean isBuiltin;

    /** 模板状态: draft/published/archived */
    private String status;

    /** 触发类型: trigger_manual/trigger_cron/trigger_event */
    private String triggerType;

    /** Flowable 流程定义ID */
    private String processDefinitionId;

    /** 创建人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新人 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
