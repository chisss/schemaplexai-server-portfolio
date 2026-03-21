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
 * 上下文条目表实体
 */
@Data
@TableName(value = "sf_context_item", autoResultMap = true)
public class ContextItem implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 上下文ID */
    private String contextId;

    /** 条目类型: document/code/config/api */
    private String itemType;

    /** 条目标题 */
    private String title;

    /** 条目内容 */
    private String content;

    /** 来源URL */
    private String sourceUrl;

    /** 元数据 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    /** Token估算 */
    private Integer tokenCount;

    /** 排序 */
    private Integer sortOrder;

    /** 是否为 Agent 专属指令文件（对应 CLAUDE.md/AGENTS.md） */
    private Boolean isAgentInstructions;

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
