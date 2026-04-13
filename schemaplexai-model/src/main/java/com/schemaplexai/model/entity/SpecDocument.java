package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Spec文档表实体
 */
@Data
@TableName("sf_spec_document")
public class SpecDocument implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** Spec ID */
    private String specId;

    /** 文档类型: requirements/design/tasks */
    private String docType;

    /** 文档内容(Markdown) */
    private String content;

    /** 当前版本号 */
    private Integer version;

    /** 关联工作流节点ID */
    private String workflowNodeId;

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
}
