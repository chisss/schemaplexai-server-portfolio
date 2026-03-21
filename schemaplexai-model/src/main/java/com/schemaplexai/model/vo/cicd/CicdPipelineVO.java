package com.schemaplexai.model.vo.cicd;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * CICD Pipeline视图对象
 */
@Data
public class CicdPipelineVO {

    /** 主键ID */
    private String id;

    /** 关联集成ID */
    private String integrationId;

    /** 关联工作空间ID */
    private String workspaceId;

    /** Pipeline名称 */
    private String name;

    /** Pipeline类型: jenkins/gitlab_ci/github_actions */
    private String pipelineType;

    /** Pipeline配置 */
    private Map<String, Object> config;

    /** 触发规则 */
    private Map<String, Object> triggerRules;

    /** 状态 */
    private String status;

    /** 最近运行时间 */
    private LocalDateTime lastRunAt;

    /** 最近运行状态 */
    private String lastRunStatus;

    /** 描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 创建人名称 */
    private String createdByName;
}
