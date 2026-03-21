package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 上下文表实体
 * 类名使用ContextEntity避免与Java关键字冲突
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_context", autoResultMap = true)
public class ContextEntity extends BaseEntity {

    /** 上下文名称 */
    private String name;

    /** 上下文级别: global/project/task/agent */
    private String contextLevel;

    /** 项目级上下文关联的项目 */
    private String projectId;

    /** 描述 */
    private String description;

    /** 状态: active/archived */
    private String status;

    /** 扩展元数据 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
}
