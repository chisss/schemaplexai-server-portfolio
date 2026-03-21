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

/**
 * 评审会话表实体
 */
@Data
@TableName(value = "sf_review_session", autoResultMap = true)
public class ReviewSession implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    @TableField(fill = FieldFill.INSERT)
    private String tenantId;

    /** Spec ID */
    private String specId;

    /** 文档类型: requirements/design/tasks */
    private String documentType;

    /** Spec Owner */
    private String owner;

    /** 评审人列表[{userId, role, status, submittedAt}] */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Object> reviewers;

    /** 截止时间 */
    private LocalDateTime deadline;

    /** 超时策略: auto_pass/escalate/remind */
    private String timeoutStrategy;

    /** 状态: pending/in_progress/completed/timeout */
    private String status;

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
