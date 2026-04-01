package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预算表实体
 */
@Data
@TableName("sf_budget")
public class Budget implements Serializable {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String tenantId;
    /** 预算级别: enterprise/project/team/user */
    private String budgetLevel;
    /** 关联对象ID */
    private String targetId;
    /** 关联对象名称 */
    private String targetName;
    /** 预算周期: daily/monthly/quarterly */
    private String budgetCycle;
    private BigDecimal budgetAmount;
    @TableField("alert_threshold_50")
    private Boolean alertThreshold50;
    @TableField("alert_threshold_80")
    private Boolean alertThreshold80;
    @TableField("alert_threshold_100")
    private Boolean alertThreshold100;
    /** 超限策略: alert/downgrade/block */
    private String overLimitStrategy;
    /** 状态: active/inactive */
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    @TableLogic
    private Integer deleted;
}
