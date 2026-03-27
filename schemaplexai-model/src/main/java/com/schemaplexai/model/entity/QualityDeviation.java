package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 偏离记录表实体
 */
@Data
@TableName("sf_quality_deviation")
public class QualityDeviation implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    private String tenantId;

    /** Spec ID */
    private String specId;

    /** 任务ID */
    private String taskId;

    /** Agent执行记录ID */
    private String agentExecutionId;

    /** 偏离类型: structural/semantic/performance/security */
    private String deviationType;

    /** 评估维度编码 */
    private String dimensionCode;

    /** 评估规则编码 */
    private String ruleCode;

    /** 来源类型: manual/agent/workflow/system */
    private String sourceType;

    /** 来源Agent ID */
    private String sourceAgentId;

    /** 严重程度: critical/warning/info */
    private String severity;

    /** 标题 */
    private String title;

    /** 描述 */
    private String description;

    /** 期望值 */
    private String expectedValue;

    /** 实际值 */
    private String actualValue;

    /** 相关文件路径 */
    private String filePath;

    /** 行号 */
    private Integer lineNumber;

    /** 状态: open/acknowledged/resolved/ignored */
    private String status;

    /** 解决备注 */
    private String remark;

    /** 解决人 */
    private String resolvedBy;

    /** 解决时间 */
    private LocalDateTime resolvedAt;

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
