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
import java.util.Map;

/**
 * 工作流实例表实体
 */
@Data
@TableName(value = "sf_workflow_instance", autoResultMap = true)
public class WorkflowInstance implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    @TableField(fill = FieldFill.INSERT)
    private String tenantId;

    /** 模板ID */
    private String templateId;

    /** 关联Spec */
    private String specId;

    /** 实例名称 */
    private String name;

    /** 状态: pending/running/paused/completed/failed */
    private String status;

    /** 当前执行节点ID */
    private String currentNodeId;

    /** 流程变量 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> variables;

    /** 实例化后的定义(快照) */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> definition;

    /** Flowable 流程实例ID */
    private String processInstanceId;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

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
