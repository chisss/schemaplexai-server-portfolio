package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Spec模板表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sf_spec_template")
public class SpecTemplate extends BaseEntity {

    /** 模板名称 */
    private String name;

    /** 模板分类: feature-development/bug-fix/refactoring/data-analysis/config-change */
    private String category;

    /** 文档类型: requirements/design/tasks */
    private String docType;

    /** 模板内容（Markdown） */
    private String content;

    /** 是否内置模板 */
    private Boolean isBuiltin;

    /** 使用次数 */
    private Integer usageCount;

    /** 描述 */
    private String description;
}
