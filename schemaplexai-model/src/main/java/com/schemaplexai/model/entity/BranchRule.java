package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 分支规则表实体
 */
@Data
@TableName(value = "sf_branch_rule", autoResultMap = true)
public class BranchRule implements Serializable {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String tenantId;
    private String integrationProjectId;
    private String branchPattern;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> protectionRules;
    private Boolean autoCreateForSpec;
    private String branchNameTemplate;
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
