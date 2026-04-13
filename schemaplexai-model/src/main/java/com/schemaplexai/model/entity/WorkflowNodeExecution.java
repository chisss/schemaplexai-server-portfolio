package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
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
 * 工作流节点执行记录实体
 */
@Data
@TableName(value = "sf_workflow_node_execution", autoResultMap = true)
public class WorkflowNodeExecution implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 所属实例ID */
    private String instanceId;

    /** 节点ID（对应definition中的nodeId） */
    private String nodeId;

    /** 节点类型 */
    private String nodeType;

    /** 节点标签 */
    private String nodeLabel;

    /** 执行状态: pending/running/completed/failed/skipped */
    private String status;

    /** 输入数据 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> inputData;

    /** 输出数据 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> outputData;

    /** 错误信息 */
    private String errorMessage;

    /** 关联的 Agent 执行 ID（Agent 节点执行时回填） */
    private String agentExecutionId;

    /** 关联评审会话ID */
    private String reviewSessionId;

    /** 动作跳转地址 */
    private String actionUrl;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
