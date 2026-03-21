package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 意图缺陷表实体
 */
@Data
@TableName("sf_intent_defect")
public class IntentDefect implements Serializable {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String tenantId;
    private String specId;
    /** 文档类型: requirements/design/tasks */
    private String docType;
    /** 缺陷类型: ambiguity/contradiction/omission/vagueness */
    private String defectType;
    /** 严重程度: critical/warning/info */
    private String severity;
    private String title;
    private String description;
    private String location;
    private String suggestion;
    /** 状态: open/resolved/accepted */
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
