package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

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
    private List<String> workspaceIds;

    /** Spec名称 */
    private String name;

    /** 语义版本号 */
    private String version;

    /** 生命周期状态: draft/requirements_review/requirements_approved/design_review/design_approved/tasks_review/ready/in_progress/completed/acceptance/archived */
    private String status;

    /** 优先级: low/medium/high/critical */
    private String priority;

    /** Spec拥有者 */
    private String owner;

    /** 分类: feature-development/bug-fix/refactoring/data-analysis/config-change */
    private String category;

    /** 需求类型: rd/marketing/qa/ops */
    private String specType;

    /** 标签数组 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    /** 描述 */
    private String description;

    /** 类型画像/扩展字段 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> profileData;

    /** 关联工作流模板ID */
    private String workflowId;

    /** 关联工作流实例ID */
    private String workflowInstanceId;

    /** 生命周期模式: legacy/workflow */
    private String lifecycleMode;

    /** 当前节点ID */
    private String currentNodeId;

    /** 当前节点类型 */
    private String currentNodeType;

    /** 当前节点标签 */
    private String currentNodeLabel;

    /** 工作流状态快照 */
    private String workflowStatusSnapshot;

    /** Jira 或需求单号 */
    private String jiraTicket;

    /** 目标研发分支 */
    private String targetBranch;

    /** 工作流产出的文档路径 */
    private String artifactDocPath;

    /** 主产物 ID */
    private String primaryArtifactId;
}
