package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 交叉审查记录表实体
 */
@Data
@TableName(value = "sf_cross_review", autoResultMap = true)
public class CrossReview implements Serializable {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String tenantId;
    private String specId;
    private String taskId;
    private String modelAId;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> modelAResult;
    private String modelBId;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> modelBResult;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> mergedResult;
    /** 状态: pending/reviewing/completed/failed */
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
