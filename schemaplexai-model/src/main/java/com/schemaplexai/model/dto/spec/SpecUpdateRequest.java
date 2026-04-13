package com.schemaplexai.model.dto.spec;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 更新Spec请求DTO
 */
@Data
public class SpecUpdateRequest {

    /** Spec名称 */
    private String name;

    /** 分类 */
    private String category;

    /** 需求类型 */
    private String specType;

    /** 优先级 */
    private String priority;

    /** 描述 */
    private String description;

    /** 扩展画像 */
    private Map<String, Object> profileData;

    /** 标签 */
    private List<String> tags;

    /** 关联工作流模板ID */
    private String workflowId;

    /** Jira 或需求单号 */
    private String jiraTicket;

    /** 目标研发分支 */
    private String targetBranch;
}
