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
 * 上下文快照表实体
 */
@Data
@TableName(value = "sf_context_snapshot", autoResultMap = true)
public class ContextSnapshot implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    @TableField(fill = FieldFill.INSERT)
    private String tenantId;

    /** 所属上下文ID */
    private String contextId;

    /** 关联Spec版本ID */
    private String specVersionId;

    /** 快照名称 */
    private String snapshotName;

    /** 快照数据（完整条目序列化） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> snapshotData;

    /** 条目数量 */
    private Integer itemCount;

    /** Token总量 */
    private Integer totalTokens;

    /** 创建人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
