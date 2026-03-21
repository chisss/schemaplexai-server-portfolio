package com.schemaplexai.model.dto.cicd;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 创建CICD Pipeline请求
 */
@Data
public class CicdPipelineCreateRequest {

    /** 关联集成ID */
    private String integrationId;

    /** 关联工作空间ID */
    private String workspaceId;

    /** Pipeline名称 */
    @NotBlank(message = "Pipeline名称不能为空")
    @Size(max = 200, message = "名称不能超过200个字符")
    private String name;

    /** Pipeline类型: jenkins/gitlab_ci/github_actions */
    @NotBlank(message = "Pipeline类型不能为空")
    private String pipelineType;

    /** Pipeline配置 */
    private Map<String, Object> config;

    /** 触发规则 */
    private Map<String, Object> triggerRules;

    /** 描述 */
    private String description;
}
