package com.schemaplexai.model.dto.spec;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 创建Spec请求DTO
 */
@Data
public class SpecCreateRequest {

    /** Spec名称 */
    @NotBlank(message = "Spec名称不能为空")
    private String name;

    /** 分类: feature-development/bug-fix/refactoring/data-analysis/config-change */
    private String category;

    /** 需求类型: rd/marketing/qa/ops */
    private String specType;

    /** 描述 */
    private String description;

    /** 扩展画像 */
    private Map<String, Object> profileData;

    /** 优先级 */
    private String priority;

    /** 标签 */
    private List<String> tags;

    /** 关联项目ID（废弃，保留兼容性） */
    @Deprecated
    private String projectId;

    /** 关联工作空间ID列表（多选，一个Spec可跨多个系统） */
    private List<String> workspaceIds = new ArrayList<>();

    /** 关联工作流模板ID */
    @NotBlank(message = "关联工作流不能为空")
    private String workflowId;

    /** Jira 或需求单号 */
    private String jiraTicket;

    /** 目标研发分支 */
    private String targetBranch;
}
