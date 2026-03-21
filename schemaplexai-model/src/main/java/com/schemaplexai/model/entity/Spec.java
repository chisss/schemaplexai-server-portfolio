package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Spec主表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_spec", autoResultMap = true)
public class Spec extends BaseEntity {

    /** 关联项目（废弃，保留兼容性，使用 workspaceIds 替代） */
    @Deprecated
    private String projectId;

    /** 关联的工作空间（项目系统）列表，一个Spec可跨多个系统 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> workspaceIds = new ArrayList<>();

    /** Spec名称 */
    private String name;

    /** 语义版本号 */
    private String version;

    /** 生命周期状态: draft/requirements_review/requirements_approved/design_review/design_approved/tasks_review/ready/in_progress/completed/acceptance/archived */
    private String status;

    /** Spec拥有者 */
    private String owner;

    /** 分类: feature-development/bug-fix/refactoring/data-analysis/config-change */
    private String category;

    /** 标签数组 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    /** 描述 */
    private String description;

    /** 关联工作流模板ID */
    private String workflowId;
}
