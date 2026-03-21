package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 上下文关联关系实体
 * 用于建立知识图谱中上下文节点之间的显式连线
 */
@Data
@TableName("sf_context_relation")
public class ContextRelation implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 来源上下文ID */
    @TableField("from_context_id")
    private String fromContextId;

    /** 目标上下文ID */
    @TableField("to_context_id")
    private String toContextId;

    /** 关联类型: depends_on/extends/references/belongs_to */
    private String relationType;

    /** 关联描述 */
    private String description;

    @TableField(fill = FieldFill.INSERT)
    private String tenantId;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
